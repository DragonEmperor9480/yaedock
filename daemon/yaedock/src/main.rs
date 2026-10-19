//! `yaedock`, the YaeDock command-line tool: pairs phones, manages paired
//! devices, and has commands for checking the daemon's building blocks.

mod client;
mod pair;

use std::io::IsTerminal;
use std::net::IpAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::bail;
use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;
use yaedock_core::devices::DeviceStore;
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, paths, sampler};
use yaedock_proto::ServerMessage;
use yaedock_sys::brightness::{BacklightBackend, BrightnessBackend};
use yaedock_sys::volume;

#[derive(Parser)]
#[command(
    version,
    about = "Pair phones with this PC and manage the YaeDock daemon"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Connect to a daemon like the phone would and print every message
    Client {
        /// Daemon address
        #[arg(default_value = "wss://127.0.0.1:9481")]
        url: String,
        /// SHA-256 fingerprint of the daemon's certificate; defaults to this
        /// machine's own daemon
        #[arg(long)]
        fingerprint: Option<String>,
        /// One-time pairing code from `yaedock pair`
        #[arg(long)]
        code: Option<String>,
        /// Token received when pairing
        #[arg(long)]
        token: Option<String>,
        /// Device id to identify as
        #[arg(long, default_value = "yaedock-client")]
        device_id: String,
        /// A JSON message to send after the welcome; may be repeated
        #[arg(short, long)]
        send: Vec<String>,
        /// Stop after this many messages
        #[arg(short = 'n', long)]
        count: Option<u64>,
    },
    /// Pair a new phone: show a QR code and wait for the phone to scan it
    Pair {
        /// Port the daemon listens on
        #[arg(long, default_value_t = DEFAULT_PORT)]
        port: u16,
        /// Address the phone should connect to; defaults to this PC's LAN address
        #[arg(long)]
        host: Option<IpAddr>,
    },
    /// Show whether the daemon is running and which phones are connected
    Status,
    /// List paired phones
    Devices,
    /// Forget a paired phone
    Unpair {
        /// Device id, as shown by `yaedock devices`
        id: String,
    },
    /// Print a `stats` message every tick, as the phone would receive it
    Stats {
        /// Stop after this many messages
        #[arg(short = 'n', long)]
        count: Option<u64>,
    },
    /// Show display brightness, or set it
    Brightness {
        /// New brightness in percent, 0 to 100
        #[arg(value_parser = clap::value_parser!(u8).range(0..=100))]
        percent: Option<u8>,
        /// Display id; needed only when there is more than one display
        #[arg(short, long)]
        display: Option<String>,
    },
    /// Show the default output's volume, or change it
    Volume {
        /// New volume in percent, 0 to 150
        #[arg(value_parser = clap::value_parser!(u8).range(0..=volume::MAX_PERCENT as i64))]
        percent: Option<u8>,
        /// Mute the output
        #[arg(long, conflicts_with = "unmute")]
        mute: bool,
        /// Unmute the output
        #[arg(long)]
        unmute: bool,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("warn")),
        )
        .with_writer(std::io::stderr)
        .with_ansi(std::io::stderr().is_terminal())
        .init();

    match Cli::parse().command {
        Command::Client {
            url,
            fingerprint,
            code,
            token,
            device_id,
            send,
            count,
        } => {
            let fingerprint = match fingerprint {
                Some(fingerprint) => fingerprint,
                None => Identity::load(&paths::config_dir()?)?.fingerprint(),
            };
            let credentials = client::Credentials {
                device_id,
                code,
                token,
            };
            client::run(&url, &fingerprint, &credentials, &send, count).await
        }
        Command::Pair { port, host } => pair::run(port, host).await,
        Command::Status => status().await,
        Command::Devices => list_devices(),
        Command::Unpair { id } => {
            if !DeviceStore::open()?.remove(&id)? {
                bail!("no paired device with id {id}");
            }
            println!("Unpaired {id}.");
            Ok(())
        }
        Command::Stats { count } => print_stats(count).await,
        Command::Brightness { percent, display } => brightness(percent, display),
        Command::Volume {
            percent,
            mute,
            unmute,
        } => set_volume(percent, (mute || unmute).then_some(mute)),
    }
}

async fn status() -> anyhow::Result<()> {
    let Some(status) = yaedock_core::control::status().await? else {
        println!("The daemon is not running. Start it with `yaedockd run`.");
        return Ok(());
    };
    let now = yaedock_core::pairing::now();
    println!(
        "Daemon {} running (pid {}) on wss://{}, up {}",
        status.version,
        status.pid,
        status.listen,
        ago(now.saturating_sub(status.started_at)),
    );
    println!("Fingerprint {}", status.fingerprint);
    if status.phones.is_empty() {
        println!("No phones connected.");
    }
    for phone in status.phones {
        println!(
            "{} ({}) connected from {} for {}",
            phone.name,
            phone.device_id,
            phone.address,
            ago(now.saturating_sub(phone.connected_at)),
        );
    }
    Ok(())
}

/// Formats a duration in seconds as `3d 4h`, `4h 12m` or `12m 5s`.
fn ago(seconds: u64) -> String {
    let (d, h, m, s) = (
        seconds / 86_400,
        seconds % 86_400 / 3_600,
        seconds % 3_600 / 60,
        seconds % 60,
    );
    match (d, h) {
        (0, 0) => format!("{m}m {s}s"),
        (0, _) => format!("{h}h {m}m"),
        _ => format!("{d}d {h}h"),
    }
}

fn list_devices() -> anyhow::Result<()> {
    let devices = DeviceStore::open()?.list()?;
    if devices.is_empty() {
        println!("No paired phones. Pair one with `yaedock pair`.");
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    for device in devices {
        let days = now.saturating_sub(device.paired_at) / 86_400;
        println!("{}\t{}\tpaired {days} days ago", device.id, device.name);
    }
    Ok(())
}

async fn print_stats(count: Option<u64>) -> anyhow::Result<()> {
    let (sources, _) = sampler::default_sources();
    let mut snapshots = sampler::spawn(sources, sampler::DEFAULT_TICK);
    let mut printed = 0;
    while count.is_none_or(|count| printed < count) {
        snapshots.changed().await?;
        let snapshot = snapshots.borrow_and_update().as_ref().clone();
        println!(
            "{}",
            serde_json::to_string(&ServerMessage::Stats(snapshot))?
        );
        printed += 1;
    }
    Ok(())
}

fn brightness(percent: Option<u8>, display: Option<String>) -> anyhow::Result<()> {
    let backend = BacklightBackend::new();
    if let Some(percent) = percent {
        let display = match display {
            Some(display) => display,
            None => match backend.displays()?.as_slice() {
                [only] => only.id.clone(),
                [] => bail!("no display with adjustable brightness found"),
                _ => bail!("more than one display; choose one with --display"),
            },
        };
        backend.set(&display, percent)?;
    }
    for display in backend.displays()? {
        println!("{}\t{}\t{}%", display.id, display.name, display.percent);
    }
    Ok(())
}

fn set_volume(percent: Option<u8>, muted: Option<bool>) -> anyhow::Result<()> {
    let Some(backend) = volume::detect() else {
        bail!("no volume control found; is PipeWire (wpctl) or PulseAudio (pactl) running?");
    };
    backend.set(percent, muted)?;
    let state = backend.get()?;
    let muted = if state.muted { " (muted)" } else { "" };
    println!("{}%{muted}", state.percent);
    Ok(())
}
