//! `yaedock pair`: shows a pairing QR code and waits for a phone.

use std::time::Duration;

use anyhow::Context;
use yaedock_core::pairing::{self, CODE_LIFETIME, Pairing, Status};
use yaedock_core::paths;
use yaedock_core::tls::Identity;

/// The `yaedock pair` command: shows a QR code and waits until a phone pairs,
/// the code expires, or the user presses Ctrl+C.
pub async fn run(port: u16, host: Option<std::net::IpAddr>) -> anyhow::Result<()> {
    let fingerprint = Identity::load_or_create(&paths::config_dir()?)?.fingerprint();
    let host = match host {
        Some(host) => host,
        None => lan_address().context("could not find this PC's LAN address; pass --host")?,
    };
    if tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .is_err()
    {
        eprintln!(
            "warning: the daemon does not seem to be running; start it with `yaedockd run` \
             or `yaedock service start`\n"
        );
    }

    let pairing = Pairing::new()?;
    pairing::save(&pairing)?;
    let qr = qr_text(&uri(host, port, &fingerprint, &pairing.code))?;
    println!("Scan this code with the YaeDock app:\n\n{qr}\n");
    println!("Or enter the details by hand:");
    println!("  Address:      {host}:{port}");
    println!(
        "  Code:         {} {}",
        &pairing.code[..3],
        &pairing.code[3..]
    );
    println!("  Fingerprint:  {}", group(&fingerprint));
    println!(
        "\nWaiting for a phone (the code expires in {} minutes)...",
        CODE_LIFETIME.as_secs() / 60
    );

    let mut poll = tokio::time::interval(Duration::from_millis(500));
    let result = loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => break Ok("Cancelled.".to_string()),
            _ = poll.tick() => {}
        }
        match pairing::load()? {
            Some(current) if current.code != pairing.code => {
                // Another `pair` replaced our code; leave its file alone.
                anyhow::bail!("another pairing was started");
            }
            None => anyhow::bail!("the pairing file was removed"),
            Some(current) => match current.status {
                Status::Paired { device } => break Ok(format!("Paired with {device}.")),
                Status::Locked => {
                    break Err(anyhow::anyhow!(
                        "too many wrong codes; run `yaedock pair` again"
                    ));
                }
                Status::Waiting if current.is_expired() => {
                    break Err(anyhow::anyhow!(
                        "the code expired; run `yaedock pair` again"
                    ));
                }
                Status::Waiting => {}
            },
        }
    };
    pairing::remove()?;
    println!("{}", result?);
    Ok(())
}

/// The pairing URI the phone scans, as described in protocol/protocol.md.
pub fn uri(host: std::net::IpAddr, port: u16, fingerprint: &str, code: &str) -> String {
    format!("yaedock://pair?host={host}&port={port}&fp={fingerprint}&code={code}")
}

/// Draws `text` as a QR code with half-height block characters.
pub fn qr_text(text: &str) -> anyhow::Result<String> {
    use qrcode::QrCode;
    use qrcode::render::unicode::Dense1x2;
    Ok(QrCode::new(text)?
        .render::<Dense1x2>()
        .quiet_zone(true)
        .build())
}

/// The address other machines on the LAN reach this PC at: the source address
/// of the default route. Connecting a UDP socket sends no packets.
pub fn lan_address() -> Option<std::net::IpAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    Some(socket.local_addr().ok()?.ip())
}

/// Groups a hex fingerprint in fours for reading aloud.
pub fn group(hex: &str) -> String {
    hex.as_bytes()
        .chunks(4)
        .map(|chunk| String::from_utf8_lossy(chunk).into_owned())
        .collect::<Vec<_>>()
        .join(" ")
}
