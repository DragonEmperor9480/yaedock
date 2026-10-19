mod client;
mod controller;
mod devices;
mod mdns;
mod pairing;
mod paths;
mod sampler;
mod session;
mod tls;
mod ws;

use std::io::IsTerminal;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::bail;
use clap::{Parser, Subcommand};
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tracing::info;
use tracing_subscriber::EnvFilter;
use yaedock_proto::ServerMessage;
use yaedock_sys::brightness::{BacklightBackend, BrightnessBackend};
use yaedock_sys::metrics::SystemInfo;
use yaedock_sys::volume;

use crate::controller::Controller;
use crate::devices::DeviceStore;
use crate::session::Shared;
use crate::tls::Identity;

pub const DEFAULT_PORT: u16 = 9481;

#[derive(Parser)]
#[command(version, about = "Stream PC stats to the YaeDock phone app")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Run the daemon
    Run {
        /// Address to listen on
        #[arg(long, default_value = "0.0.0.0:9481")]
        listen: SocketAddr,
    },
    /// Connect to a daemon like the phone would and print every message
    Client {
        /// Daemon address
        #[arg(default_value = "wss://127.0.0.1:9481")]
        url: String,
        /// SHA-256 fingerprint of the daemon's certificate; defaults to this
        /// machine's own daemon
        #[arg(long)]
        fingerprint: Option<String>,
        /// One-time pairing code from `yaedockd pair`
        #[arg(long)]
        code: Option<String>,
        /// Token received when pairing
        #[arg(long)]
        token: Option<String>,
        /// Device id to identify as
        #[arg(long, default_value = "yaedockd-client")]
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
    /// List paired phones
    Devices,
    /// Forget a paired phone
    Unpair {
        /// Device id, as shown by `yaedockd devices`
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
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_writer(std::io::stderr)
        .with_ansi(std::io::stderr().is_terminal())
        .init();

    match Cli::parse().command {
        Command::Run { listen } => run(listen).await,
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
        Command::Pair { port, host } => pairing::run_pair(port, host).await,
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

async fn run(listen: SocketAddr) -> anyhow::Result<()> {
    let identity = Identity::load_or_create(&paths::config_dir()?)?;
    let tls = TlsAcceptor::from(identity.server_config()?);
    let (sources, sensors) = sampler::default_sources();
    let snapshots = sampler::spawn(sources, sampler::DEFAULT_TICK);
    let volume = volume::detect().map(Arc::from);
    if volume.is_none() {
        tracing::warn!("no volume control found; volume commands will fail");
    }
    let controller = Controller::spawn(Arc::new(BacklightBackend::new()), volume).await;
    let info = SystemInfo::read();
    let hostname = info.hostname.clone();
    let shared = Arc::new(Shared {
        snapshots,
        controller,
        info,
        sensors,
        tick: sampler::DEFAULT_TICK,
        devices: DeviceStore::open()?,
        auth_lock: Mutex::new(()),
    });

    let listener = TcpListener::bind(listen).await?;
    info!(fingerprint = %identity.fingerprint(), "listening on wss://{listen}");
    let mdns = if listen.ip().is_loopback() {
        None
    } else {
        mdns::advertise(&hostname, listen.port(), &identity.fingerprint())
            .inspect_err(|err| tracing::warn!("mDNS advertising failed: {err}"))
            .ok()
    };
    let result = tokio::select! {
        result = ws::serve(listener, tls, shared) => result.map_err(Into::into),
        _ = tokio::signal::ctrl_c() => {
            info!("shutting down");
            Ok(())
        }
    };
    if let Some(mdns) = mdns {
        // Sends a goodbye so phones drop the PC from their lists right away.
        let _ = mdns.shutdown();
    }
    result
}

fn list_devices() -> anyhow::Result<()> {
    let devices = DeviceStore::open()?.list()?;
    if devices.is_empty() {
        println!("No paired phones. Pair one with `yaedockd pair`.");
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
