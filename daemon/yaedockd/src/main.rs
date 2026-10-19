//! `yaedockd`, the YaeDock daemon: streams this PC's stats to paired phones and
//! applies their brightness and volume changes. It is normally started by the
//! systemd user service; `yaedock` is the tool for pairing and managing it.

mod controller;
mod mdns;
mod session;
mod ws;

use std::io::IsTerminal;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use clap::{Parser, Subcommand};
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tracing::info;
use tracing_subscriber::EnvFilter;
use yaedock_core::devices::DeviceStore;
use yaedock_core::tls::Identity;
use yaedock_core::{paths, sampler};
use yaedock_sys::brightness::BacklightBackend;
use yaedock_sys::metrics::SystemInfo;
use yaedock_sys::volume;

use crate::controller::Controller;
use crate::session::Shared;

#[derive(Parser)]
#[command(
    version,
    about = "The YaeDock daemon. Use `yaedock` to pair phones and manage it.",
    arg_required_else_help = true
)]
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
