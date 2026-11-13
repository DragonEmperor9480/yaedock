//! A test client that connects like the phone would and prints every message.

use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
use std::time::Instant;

use anyhow::Context;
use futures_util::{SinkExt, StreamExt};
use rustls::pki_types::ServerName;
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::http::Uri;
use yaedock_proto::{ClientMessage, Hello, PROTOCOL_VERSION, ServerMessage, VideoFrame};

const KEEPALIVE: std::time::Duration = std::time::Duration::from_secs(10);
/// How much sound goes in one frame.
const SOUND_PIECE: std::time::Duration = std::time::Duration::from_millis(20);
/// How long one of the camera's pictures lasts: 30 a second.
const PICTURE: std::time::Duration = std::time::Duration::from_micros(33_333);

/// Who the test client says it is.
pub struct Credentials {
    pub device_id: String,
    pub code: Option<String>,
    pub token: Option<String>,
}

/// Connects to the `wss://` `url`, accepting only the certificate with
/// `fingerprint`, says hello, sends each of `send` after the welcome, and
/// prints received messages as JSON lines. Stops after `count` messages if given.
/// The mirrored screen's video is written to `video` as an H.264 file, with a
/// line a second on standard error saying how much arrived; without it, video
/// is dropped. `sound` is a file of raw sound (48 kHz, one channel, signed
/// 16-bit) sent after the welcome as the phone's microphone would send it,
/// 20 ms at a time. `camera` is an H.264 file (Annex B) sent as the phone's
/// camera would send it, 30 pictures a second.
#[allow(clippy::too_many_arguments)]
pub async fn run(
    url: &str,
    fingerprint: &str,
    credentials: &Credentials,
    send: &[String],
    count: Option<u64>,
    video: Option<&Path>,
    sound: Option<&Path>,
    camera: Option<&Path>,
) -> anyhow::Result<()> {
    let uri: Uri = url.parse().context("invalid URL")?;
    if uri.scheme_str() != Some("wss") {
        anyhow::bail!("the daemon only accepts wss:// URLs");
    }
    let host = uri.host().context("URL has no host")?.to_string();
    let port = uri.port_u16().unwrap_or(yaedock_core::DEFAULT_PORT);
    let tcp = TcpStream::connect((host.as_str(), port)).await?;
    let connector = TlsConnector::from(yaedock_core::tls::pinned_client_config(fingerprint)?);
    // The certificate is pinned, so the name only matters for SNI.
    let name = ServerName::try_from(host).unwrap_or(ServerName::try_from("yaedock")?);
    let tls = connector.connect(name, tcp).await?;
    let (mut ws, _) = tokio_tungstenite::client_async(url, tls).await?;
    let hello = ClientMessage::Hello(Hello {
        version: PROTOCOL_VERSION,
        device_id: credentials.device_id.clone(),
        device_name: "yaedock test client".into(),
        token: credentials.token.clone(),
        code: credentials.code.clone(),
    });
    ws.send(Message::text(serde_json::to_string(&hello)?))
        .await?;

    let mut video = video.map(File::create).transpose()?;
    let mut sound = sound.map(File::open).transpose()?;
    let mut sound_tick = tokio::time::interval(SOUND_PIECE);
    let mut pictures = match camera {
        Some(file) => access_units(&std::fs::read(file)?).into_iter(),
        None => Vec::new().into_iter(),
    };
    let mut picture_tick = tokio::time::interval(PICTURE);
    // Frames, keyframes and bytes since the last report.
    let mut tally = (0u32, 0u32, 0usize);
    let mut reported = Instant::now();
    let mut received = 0;
    let mut welcomed = false;
    // The daemon drops clients it has not heard from for 30 s, so ping every 10 s.
    let mut keepalive =
        tokio::time::interval_at(tokio::time::Instant::now() + KEEPALIVE, KEEPALIVE);
    while count.is_none_or(|count| received < count) {
        let frame = tokio::select! {
            frame = ws.next() => frame,
            _ = keepalive.tick() => {
                let ping = serde_json::to_string(&ClientMessage::Ping)?;
                ws.send(Message::text(ping)).await?;
                continue;
            }
            _ = sound_tick.tick(), if welcomed && sound.is_some() => {
                // 20 ms of sound behind the byte that says what the frame is.
                let mut piece = vec![yaedock_proto::PHONE_SOUND; 1 + 1920];
                let file = sound.as_mut().expect("checked above");
                match file.read_exact(&mut piece[1..]) {
                    Ok(()) => ws.send(Message::binary(piece)).await?,
                    Err(_) => sound = None,
                }
                continue;
            }
            _ = picture_tick.tick(), if welcomed && pictures.len() > 0 => {
                let mut frame = vec![yaedock_proto::PHONE_VIDEO];
                frame.extend(pictures.next().expect("checked above"));
                ws.send(Message::binary(frame)).await?;
                continue;
            }
        };
        let Some(frame) = frame else { break };
        let text = match frame? {
            Message::Text(text) => text,
            Message::Binary(bytes) => {
                if let (Some(file), Some(frame)) = (&mut video, VideoFrame::from_bytes(&bytes)) {
                    file.write_all(&frame.data)?;
                    tally = (
                        tally.0 + 1,
                        tally.1 + u32::from(frame.key),
                        tally.2 + bytes.len(),
                    );
                    let since = reported.elapsed().as_secs_f64();
                    if since >= 1.0 {
                        eprintln!(
                            "video: {:.0} frames/s, {} keyframes, {:.2} Mbit/s",
                            f64::from(tally.0) / since,
                            tally.1,
                            tally.2 as f64 * 8.0 / since / 1e6
                        );
                        tally = (0, 0, 0);
                        reported = Instant::now();
                    }
                }
                continue;
            }
            _ => continue,
        };
        println!("{}", text.as_str());
        received += 1;
        match serde_json::from_str(&text)? {
            ServerMessage::Welcome(_) => {
                welcomed = true;
                for message in send {
                    // Check it parses, so typos fail here rather than as a bad_request.
                    serde_json::from_str::<ClientMessage>(message)?;
                    ws.send(Message::text(message.clone())).await?;
                }
            }
            // The daemon closes the connection after rejecting a hello.
            ServerMessage::Error(error) if !welcomed => {
                anyhow::bail!("the daemon rejected this client: {}", error.message);
            }
            ServerMessage::Ping => {
                let pong = serde_json::to_string(&ClientMessage::Pong)?;
                ws.send(Message::text(pong)).await?;
            }
            _ => {}
        }
    }
    // The daemon may already have closed the connection.
    let _ = ws.close(None).await;
    Ok(())
}

/// Cuts an H.264 stream in Annex B form into its pictures: each ends with
/// the unit that holds the picture itself, behind any settings before it.
fn access_units(stream: &[u8]) -> Vec<Vec<u8>> {
    // Where each unit's start code begins.
    let mut starts: Vec<usize> = stream
        .windows(3)
        .enumerate()
        .filter(|(_, window)| *window == [0, 0, 1])
        .map(|(at, _)| {
            if at > 0 && stream[at - 1] == 0 {
                at - 1
            } else {
                at
            }
        })
        .collect();
    starts.push(stream.len());
    let mut units = Vec::new();
    let mut from = starts[0];
    for pair in starts.windows(2) {
        let unit = &stream[pair[0]..pair[1]];
        let kind = unit.iter().skip_while(|byte| **byte == 0).nth(1);
        if matches!(kind.map(|kind| kind & 0x1f), Some(1 | 5)) {
            units.push(stream[from..pair[1]].to_vec());
            from = pair[1];
        }
    }
    units
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_stream_is_cut_after_each_picture() {
        let stream = [
            0, 0, 0, 1, 0x67, 9, 0, 0, 0, 1, 0x68, 8, 0, 0, 1, 0x65, 7,
            7, // settings, keyframe
            0, 0, 0, 1, 0x41, 6, // a picture
            0, 0, 1, 0x41, 5, // and another
        ];
        let units = access_units(&stream);
        assert_eq!(units.len(), 3);
        assert_eq!(units[0], stream[..18]);
        assert_eq!(units[1], stream[18..24]);
        assert_eq!(units[2], stream[24..]);
        assert!(access_units(&[]).is_empty());
    }
}
