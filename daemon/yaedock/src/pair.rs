//! `yaedock pair`: shows a pairing QR code and waits for a phone.

use std::time::Duration;

use yaedock_core::pairing::{self, CODE_LIFETIME, Pairing, Status};
use yaedock_core::paths;
use yaedock_core::tls::Identity;

/// The `yaedock pair` command: shows a QR code and waits until a phone pairs,
/// the code expires, or the user presses Ctrl+C.
pub async fn run(port: u16, host: Option<std::net::IpAddr>) -> anyhow::Result<()> {
    let fingerprint = Identity::load_or_create(&paths::config_dir()?)?.fingerprint();
    let host = match host {
        Some(host) => PairHost::Address(host),
        None => PairHost::find(),
    };
    if tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .is_err()
    {
        eprintln!(
            "warning: the daemon does not seem to be running; start it with `yaedock daemon` \
             or `yaedock service start`\n"
        );
    }

    let mut pairing = Pairing::new()?;
    let link = uri(&host.to_string(), port, &fingerprint, &pairing.code);
    pairing.uri = Some(link.clone());
    pairing::save(&pairing)?;
    match host {
        PairHost::Address(address) => {
            let qr = qr_text(&link)?;
            println!("Scan this code with the YaeDock app:\n\n{qr}\n");
            println!("Or plug the phone into this PC by USB cable, with the app installed.\n");
            println!("Or enter the details by hand:");
            println!("  Address:      {address}:{port}");
            println!(
                "  Code:         {} {}",
                &pairing.code[..3],
                &pairing.code[3..]
            );
            println!("  Fingerprint:  {}", group(&fingerprint));
        }
        PairHost::CableOnly(_) => {
            println!("This PC is not on a network, so pair by USB cable:");
            println!("plug the phone into this PC, with the YaeDock app installed.");
            println!(
                "\n(To pair over Wi-Fi instead, connect this PC to the phone's network and run \
                 `yaedock pair` again.)"
            );
        }
    }
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

/// Where the phone is told to find this PC when pairing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairHost {
    /// The PC's address on the network: a phone can scan the code or type it in.
    Address(std::net::IpAddr),
    /// No network, so only the USB cable can pair. The link names the PC as
    /// `<hostname>.local`; the cable does not use it, and the phone keeps it
    /// only until it finds the PC on a network.
    CableOnly(String),
}

impl PairHost {
    /// This PC's LAN address, or its `.local` name when it has none.
    pub fn find() -> Self {
        match lan_address() {
            Some(address) => Self::Address(address),
            None => Self::CableOnly(local_name(
                &yaedock_sys::metrics::SystemInfo::read().hostname,
            )),
        }
    }
}

impl std::fmt::Display for PairHost {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Address(address) => address.fmt(f),
            Self::CableOnly(name) => f.write_str(name),
        }
    }
}

/// `dragon.local` for a PC named `Dragon`; a name fit for a link.
fn local_name(hostname: &str) -> String {
    let name: String = hostname
        .split('.')
        .next()
        .unwrap_or_default()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .collect::<String>()
        .to_ascii_lowercase();
    let name = if name.is_empty() {
        "yaedock".into()
    } else {
        name
    };
    format!("{name}.local")
}

/// The pairing URI the phone scans, as described in protocol/protocol.md.
pub fn uri(host: &str, port: u16, fingerprint: &str, code: &str) -> String {
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
/// of the default route. Connecting a UDP socket sends no packets; with no
/// route at all (no network), the connect fails.
pub fn lan_address() -> Option<std::net::IpAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    Some(socket.local_addr().ok()?.ip()).filter(|ip| !ip.is_unspecified())
}

/// Groups a hex fingerprint in fours for reading aloud.
pub fn group(hex: &str) -> String {
    hex.as_bytes()
        .chunks(4)
        .map(|chunk| String::from_utf8_lossy(chunk).into_owned())
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_a_pc_without_a_network_by_its_local_name() {
        assert_eq!(local_name("Dragon"), "dragon.local");
        assert_eq!(local_name("my-pc.example.org"), "my-pc.local");
        assert_eq!(local_name("Shura's PC"), "shuraspc.local");
        assert_eq!(local_name(""), "yaedock.local");
    }

    #[test]
    fn a_cable_only_link_names_the_pc() {
        let host = PairHost::CableOnly("dragon.local".into());
        assert_eq!(
            uri(&host.to_string(), 9481, "ab", "123456"),
            "yaedock://pair?host=dragon.local&port=9481&fp=ab&code=123456"
        );
    }
}
