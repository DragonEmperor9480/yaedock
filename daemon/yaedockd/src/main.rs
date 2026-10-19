mod client;
mod controller;
mod paths;
mod sampler;
mod session;
mod tls;
mod ws;

use std::io::IsTerminal;
use std::net::SocketAddr;
use std::sync::Arc;

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
use crate::session::Shared;
use crate::tls::Identity;

pub const DEFAULT_PORT: u16 = 47820;

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
        /// Address to listen on. Local only until TLS and pairing are in place.
        #[arg(long, default_value = "127.0.0.1:47820")]
        listen: SocketAddr,
    },
    /// Connect to a daemon like the phone would and print every message
    Client {
        /// Daemon address
        #[arg(default_value = "wss://127.0.0.1:47820")]
        url: String,
        /// SHA-256 fingerprint of the daemon's certificate; defaults to this
        /// machine's own daemon
        #[arg(long)]
        fingerprint: Option<String>,
        /// A JSON message to send after the welcome; may be repeated
        #[arg(short, long)]
        send: Vec<String>,
        /// Stop after this many messages
        #[arg(short = 'n', long)]
        count: Option<u64>,
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
            send,
            count,
        } => {
            let fingerprint = match fingerprint {
                Some(fingerprint) => fingerprint,
                None => Identity::load(&paths::config_dir()?)?.fingerprint(),
            };
            client::run(&url, &fingerprint, &send, count).await
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
    let shared = Arc::new(Shared {
        snapshots,
        controller,
        info: SystemInfo::read(),
        sensors,
        tick: sampler::DEFAULT_TICK,
    });

    let listener = TcpListener::bind(listen).await?;
    info!(fingerprint = %identity.fingerprint(), "listening on wss://{listen}");
    tokio::select! {
        result = ws::serve(listener, tls, shared) => result?,
        _ = tokio::signal::ctrl_c() => info!("shutting down"),
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
