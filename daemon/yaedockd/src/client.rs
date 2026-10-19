//! A test client that connects like the phone would and prints every message.

use anyhow::Context;
use futures_util::{SinkExt, StreamExt};
use rustls::pki_types::ServerName;
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::http::Uri;
use yaedock_proto::{ClientMessage, Hello, PROTOCOL_VERSION, ServerMessage};

/// Who the test client says it is.
pub struct Credentials {
    pub device_id: String,
    pub code: Option<String>,
    pub token: Option<String>,
}

/// Connects to the `wss://` `url`, accepting only the certificate with
/// `fingerprint`, says hello, sends each of `send` after the welcome, and
/// prints received messages as JSON lines. Stops after `count` messages if given.
pub async fn run(
    url: &str,
    fingerprint: &str,
    credentials: &Credentials,
    send: &[String],
    count: Option<u64>,
) -> anyhow::Result<()> {
    let uri: Uri = url.parse().context("invalid URL")?;
    if uri.scheme_str() != Some("wss") {
        anyhow::bail!("the daemon only accepts wss:// URLs");
    }
    let host = uri.host().context("URL has no host")?.to_string();
    let port = uri.port_u16().unwrap_or(crate::DEFAULT_PORT);
    let tcp = TcpStream::connect((host.as_str(), port)).await?;
    let connector = TlsConnector::from(crate::tls::pinned_client_config(fingerprint)?);
    // The certificate is pinned, so the name only matters for SNI.
    let name = ServerName::try_from(host).unwrap_or(ServerName::try_from("yaedock")?);
    let tls = connector.connect(name, tcp).await?;
    let (mut ws, _) = tokio_tungstenite::client_async(url, tls).await?;
    let hello = ClientMessage::Hello(Hello {
        version: PROTOCOL_VERSION,
        device_id: credentials.device_id.clone(),
        device_name: "yaedockd test client".into(),
        token: credentials.token.clone(),
        code: credentials.code.clone(),
    });
    ws.send(Message::text(serde_json::to_string(&hello)?))
        .await?;

    let mut received = 0;
    let mut welcomed = false;
    while count.is_none_or(|count| received < count) {
        let Some(frame) = ws.next().await else { break };
        let Message::Text(text) = frame? else {
            continue;
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
