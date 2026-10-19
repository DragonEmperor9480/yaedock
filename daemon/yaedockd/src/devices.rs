//! Paired phones, kept in `devices.toml` in the config directory.
//!
//! Only a SHA-256 hash of each token is stored. The file is read on every
//! check, so `yaedockd unpair` takes effect without restarting the daemon.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::Context;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::paths;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    /// Unix time in seconds.
    pub paired_at: u64,
    token_sha256: String,
}

#[derive(Default, Serialize, Deserialize)]
struct DeviceFile {
    #[serde(default, rename = "device")]
    devices: Vec<Device>,
}

pub struct DeviceStore {
    path: PathBuf,
}

impl DeviceStore {
    pub fn open() -> anyhow::Result<Self> {
        Ok(Self {
            path: paths::config_dir()?.join("devices.toml"),
        })
    }

    pub fn list(&self) -> anyhow::Result<Vec<Device>> {
        Ok(self.read()?.devices)
    }

    /// Whether `token` belongs to the paired device `id`.
    pub fn authorize(&self, id: &str, token: &str) -> anyhow::Result<bool> {
        let hash = hash(token);
        Ok(self
            .read()?
            .devices
            .iter()
            .any(|device| device.id == id && device.token_sha256 == hash))
    }

    /// Pairs a device, replacing any earlier pairing with the same id, and
    /// returns its new token.
    pub fn add(&self, id: &str, name: &str) -> anyhow::Result<String> {
        let token = random_hex(32)?;
        let mut file = self.read()?;
        file.devices.retain(|device| device.id != id);
        file.devices.push(Device {
            id: id.to_string(),
            name: name.to_string(),
            paired_at: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |d| d.as_secs()),
            token_sha256: hash(&token),
        });
        self.write(&file)?;
        Ok(token)
    }

    /// Forgets a device. Returns whether it was paired.
    pub fn remove(&self, id: &str) -> anyhow::Result<bool> {
        let mut file = self.read()?;
        let before = file.devices.len();
        file.devices.retain(|device| device.id != id);
        if file.devices.len() == before {
            return Ok(false);
        }
        self.write(&file)?;
        Ok(true)
    }

    fn read(&self) -> anyhow::Result<DeviceFile> {
        match fs::read_to_string(&self.path) {
            Ok(text) => {
                toml::from_str(&text).with_context(|| format!("parsing {}", self.path.display()))
            }
            Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(DeviceFile::default()),
            Err(err) => Err(err).with_context(|| format!("reading {}", self.path.display())),
        }
    }

    fn write(&self, file: &DeviceFile) -> anyhow::Result<()> {
        let text = toml::to_string(file)?;
        paths::write_private(&self.path, text.as_bytes())
            .with_context(|| format!("writing {}", self.path.display()))
    }
}

fn hash(token: &str) -> String {
    hex(&Sha256::digest(token.as_bytes()))
}

/// `len` random bytes as lowercase hex.
pub fn random_hex(len: usize) -> anyhow::Result<String> {
    let mut bytes = vec![0; len];
    getrandom::fill(&mut bytes).map_err(|err| anyhow::anyhow!("no randomness: {err}"))?;
    Ok(hex(&bytes))
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
