//! The local control socket: how `yaedock` asks the running daemon about its
//! state. Each request and reply is one line of JSON on a Unix socket in the
//! user's runtime directory, which only that user can open.

use std::path::PathBuf;

use anyhow::Context;
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

use crate::paths;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Request {
    Status,
}

/// What the running daemon reports about itself.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DaemonStatus {
    pub version: String,
    pub pid: u32,
    /// Address the daemon listens on, such as `0.0.0.0:9481`.
    pub listen: String,
    pub fingerprint: String,
    /// Unix time in seconds.
    pub started_at: u64,
    pub phones: Vec<ConnectedPhone>,
}

/// A phone connected to the daemon right now.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectedPhone {
    pub device_id: String,
    pub name: String,
    /// Where the phone connects from, such as `192.168.1.30:51234`.
    pub address: String,
    /// Unix time in seconds.
    pub connected_at: u64,
}

pub fn socket_path() -> anyhow::Result<PathBuf> {
    Ok(paths::runtime_dir()?.join("control.sock"))
}

/// Asks the running daemon for its status. Returns `None` if no daemon is
/// listening on the control socket.
pub async fn status() -> anyhow::Result<Option<DaemonStatus>> {
    let Ok(stream) = UnixStream::connect(socket_path()?).await else {
        return Ok(None);
    };
    let (read, mut write) = stream.into_split();
    let mut request = serde_json::to_string(&Request::Status)?;
    request.push('\n');
    write.write_all(request.as_bytes()).await?;
    let mut line = String::new();
    BufReader::new(read).read_line(&mut line).await?;
    let status = serde_json::from_str(&line).context("unexpected reply from the daemon")?;
    Ok(Some(status))
}
