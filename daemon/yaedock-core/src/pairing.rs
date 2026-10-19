//! One-time pairing codes.
//!
//! `yaedock pair` writes a code to `$XDG_RUNTIME_DIR/yaedock/pairing`; the
//! daemon checks codes from new phones against it and records the outcome
//! there, so `yaedock pair` can report it.

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

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}
