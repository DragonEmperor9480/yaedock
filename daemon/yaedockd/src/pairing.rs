//! One-time pairing codes.
//!
//! `yaedockd pair` writes a code to `$XDG_RUNTIME_DIR/yaedock/pairing`; the
//! daemon checks codes from new phones against it and records the outcome
//! there, so the `pair` command can report it.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::Context;
use serde::{Deserialize, Serialize};

use crate::paths;

/// How long a code stays valid.
pub const CODE_LIFETIME: Duration = Duration::from_secs(5 * 60);

/// Wrong codes allowed before pairing is locked.
const MAX_ATTEMPTS: u32 = 5;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Pairing {
    pub code: String,
    /// Unix time in seconds.
    pub expires: u64,
    #[serde(default)]
    pub attempts: u32,
    #[serde(default)]
    pub status: Status,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum Status {
    #[default]
    Waiting,
    Paired {
        device: String,
    },
    /// Too many wrong codes.
    Locked,
}

impl Pairing {
    /// A fresh random 6-digit code.
    pub fn new() -> anyhow::Result<Self> {
        let n = getrandom::u32().map_err(|err| anyhow::anyhow!("no randomness: {err}"))?;
        Ok(Self {
            code: format!("{:06}", n % 1_000_000),
            expires: now() + CODE_LIFETIME.as_secs(),
            attempts: 0,
            status: Status::Waiting,
        })
    }

    pub fn is_expired(&self) -> bool {
        now() >= self.expires
    }
}

fn path() -> anyhow::Result<PathBuf> {
    Ok(paths::runtime_dir()?.join("pairing"))
}

pub fn load() -> anyhow::Result<Option<Pairing>> {
    let path = path()?;
    match fs::read_to_string(&path) {
        Ok(text) => Ok(Some(
            toml::from_str(&text).with_context(|| format!("parsing {}", path.display()))?,
        )),
        Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(err) => Err(err).with_context(|| format!("reading {}", path.display())),
    }
}

pub fn save(pairing: &Pairing) -> anyhow::Result<()> {
    let path = path()?;
    paths::write_private(&path, toml::to_string(pairing)?.as_bytes())
        .with_context(|| format!("writing {}", path.display()))
}

pub fn remove() -> anyhow::Result<()> {
    match fs::remove_file(path()?) {
        Err(err) if err.kind() != io::ErrorKind::NotFound => Err(err.into()),
        _ => Ok(()),
    }
}

/// Checks a code sent by a new phone named `device`. On success the code is
/// used up; after too many wrong codes pairing is locked.
pub fn redeem(code: &str, device: &str) -> anyhow::Result<bool> {
    let Some(mut pairing) = load()? else {
        return Ok(false);
    };
    if pairing.status != Status::Waiting || pairing.is_expired() {
        return Ok(false);
    }
    if pairing.code == code {
        pairing.status = Status::Paired {
            device: device.to_string(),
        };
    } else {
        pairing.attempts += 1;
        if pairing.attempts >= MAX_ATTEMPTS {
            pairing.status = Status::Locked;
        }
    }
    save(&pairing)?;
    Ok(matches!(pairing.status, Status::Paired { .. }))
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

/// The `yaedockd pair` command: shows a QR code and waits until a phone pairs,
/// the code expires, or the user presses Ctrl+C.
pub async fn run_pair(port: u16, host: Option<std::net::IpAddr>) -> anyhow::Result<()> {
    use qrcode::QrCode;
    use qrcode::render::unicode::Dense1x2;

    let fingerprint = crate::tls::Identity::load_or_create(&paths::config_dir()?)?.fingerprint();
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
             or `systemctl --user start yaedockd`\n"
        );
    }

    let pairing = Pairing::new()?;
    save(&pairing)?;
    let uri = format!(
        "yaedock://pair?host={host}&port={port}&fp={fingerprint}&code={}",
        pairing.code
    );
    let qr = QrCode::new(&uri)?
        .render::<Dense1x2>()
        .quiet_zone(true)
        .build();
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
        match load()? {
            Some(current) if current.code != pairing.code => {
                // Another `pair` replaced our code; leave its file alone.
                anyhow::bail!("another pairing was started");
            }
            None => anyhow::bail!("the pairing file was removed"),
            Some(current) => match current.status {
                Status::Paired { device } => break Ok(format!("Paired with {device}.")),
                Status::Locked => {
                    break Err(anyhow::anyhow!(
                        "too many wrong codes; run `yaedockd pair` again"
                    ));
                }
                Status::Waiting if current.is_expired() => {
                    break Err(anyhow::anyhow!(
                        "the code expired; run `yaedockd pair` again"
                    ));
                }
                Status::Waiting => {}
            },
        }
    };
    remove()?;
    println!("{}", result?);
    Ok(())
}

/// The address other machines on the LAN reach this PC at: the source address
/// of the default route. Connecting a UDP socket sends no packets.
fn lan_address() -> Option<std::net::IpAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    Some(socket.local_addr().ok()?.ip())
}

/// Groups a hex fingerprint in fours for reading aloud.
fn group(hex: &str) -> String {
    hex.as_bytes()
        .chunks(4)
        .map(|chunk| String::from_utf8_lossy(chunk).into_owned())
        .collect::<Vec<_>>()
        .join(" ")
}
