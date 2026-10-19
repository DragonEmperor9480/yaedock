//! Serves the control socket that `yaedock` uses to ask about the daemon.

use std::io;
use std::sync::Arc;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tracing::debug;
use yaedock_core::control::{self, DaemonStatus, Request};

use crate::session::Shared;

/// Facts about this daemon that do not change while it runs.
pub struct DaemonInfo {
    pub listen: String,
    pub fingerprint: String,
    pub started_at: u64,
}

/// Binds the control socket, replacing one left behind by a daemon that did
/// not shut down cleanly.
pub fn bind() -> anyhow::Result<UnixListener> {
    let path = control::socket_path()?;
    match std::fs::remove_file(&path) {
        Err(err) if err.kind() != io::ErrorKind::NotFound => return Err(err.into()),
        _ => {}
    }
    Ok(UnixListener::bind(path)?)
}

pub async fn serve(listener: UnixListener, info: Arc<DaemonInfo>, shared: Arc<Shared>) {
    loop {
        let Ok((stream, _)) = listener.accept().await else {
            continue;
        };
        let (info, shared) = (info.clone(), shared.clone());
        tokio::spawn(async move {
            if let Err(err) = handle(stream, &info, &shared).await {
                debug!("control connection failed: {err}");
            }
        });
    }
}

async fn handle(stream: UnixStream, info: &DaemonInfo, shared: &Shared) -> anyhow::Result<()> {
    let (read, mut write) = stream.into_split();
    let mut lines = BufReader::new(read).lines();
    while let Some(line) = lines.next_line().await? {
        let reply = match serde_json::from_str(&line)? {
            Request::Status => serde_json::to_string(&DaemonStatus {
                version: env!("CARGO_PKG_VERSION").into(),
                pid: std::process::id(),
                listen: info.listen.clone(),
                fingerprint: info.fingerprint.clone(),
                started_at: info.started_at,
                phones: shared.phones.list(),
            })?,
        };
        write.write_all(reply.as_bytes()).await?;
        write.write_all(b"\n").await?;
    }
    Ok(())
}

/// Removes the socket when the daemon stops.
pub fn unbind() {
    if let Ok(path) = control::socket_path() {
        let _ = std::fs::remove_file(path);
    }
}
