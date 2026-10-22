//! The YaeDock daemon: streams this PC's stats to paired phones and applies
//! their brightness and volume changes. It ships inside the `yaedock` program
//! and runs as `yaedock daemon`, normally started by the systemd user service.

mod control;
mod controller;
mod mdns;
mod session;
mod ws;

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tracing::info;
use yaedock_core::devices::DeviceStore;
use yaedock_core::tls::Identity;
use yaedock_core::{pairing, paths, sampler};
use yaedock_sys::brightness::BacklightBackend;
use yaedock_sys::media::MprisBackend;
use yaedock_sys::metrics::SystemInfo;
use yaedock_sys::recorder::{ObsBackend, ObsInstall, RecorderBackend};
use yaedock_sys::{power, volume};

use crate::control::DaemonInfo;
use crate::controller::{Backends, Controller};
use crate::session::{Phones, Shared};

/// Runs the daemon until Ctrl+C or SIGTERM: serves phones on `listen`, announces
/// itself over mDNS, and answers `yaedock` on the control socket.
pub async fn run(listen: SocketAddr) -> anyhow::Result<()> {
    let identity = Identity::load_or_create(&paths::config_dir()?)?;
    let tls = TlsAcceptor::from(identity.server_config()?);
    let sampler::Sources {
        sources,
        sensors,
        gpus,
    } = sampler::default_sources();
    // GPUs are only read while a phone is connected, so they are left alone otherwise.
    let phones = Arc::new(Phones::default());
    let watching = phones.clone();
    let snapshots = sampler::spawn(
        sources,
        sampler::DEFAULT_TICK,
        Arc::new(move || watching.any()),
    );
    let volume = volume::detect().map(Arc::from);
    if volume.is_none() {
        tracing::warn!("no volume control found yet; looking again every few seconds");
    }
    // Whether OBS's WebSocket server is on is checked on use, so turning it on
    // later needs no restart.
    let recorder = ObsInstall::find()
        .map(|install| Arc::new(ObsBackend::new(install)) as Arc<dyn RecorderBackend>);
    let controller = Controller::spawn(Backends {
        brightness: Arc::new(BacklightBackend::new()),
        volume,
        media: MprisBackend::new().map(|m| Arc::new(m) as _),
        power: power::detect().map(Arc::from),
        recorder: recorder.clone(),
    })
    .await;
    let info = SystemInfo::read();
    let hostname = info.hostname.clone();
    let shared = Arc::new(Shared {
        snapshots,
        controller,
        info,
        sensors,
        gpus,
        tick: sampler::DEFAULT_TICK,
        devices: DeviceStore::open()?,
        auth_lock: Mutex::new(()),
        phones,
        recorder,
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
    // Without the control socket `yaedock status` cannot see the daemon, but
    // phones still work, so a failure here is not fatal.
    match control::bind() {
        Ok(control) => {
            let info = Arc::new(DaemonInfo {
                listen: listen.to_string(),
                fingerprint: identity.fingerprint(),
                started_at: pairing::now(),
            });
            tokio::spawn(control::serve(control, info, shared.clone()));
        }
        Err(err) => tracing::warn!("control socket unavailable: {err:#}"),
    }

    let result = tokio::select! {
        result = ws::serve(listener, tls, shared) => result.map_err(Into::into),
        _ = shutdown_signal() => {
            info!("shutting down");
            Ok(())
        }
    };
    if let Some(mdns) = mdns {
        // Sends a goodbye so phones drop the PC from their lists right away.
        let _ = mdns.shutdown();
    }
    control::unbind();
    result
}

/// Waits for Ctrl+C, or SIGTERM, which is how systemd stops the service.
async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    match signal(SignalKind::terminate()) {
        Ok(mut term) => {
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {}
                _ = term.recv() => {}
            }
        }
        Err(_) => {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
}
