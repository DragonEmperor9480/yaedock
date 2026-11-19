//! Paired phones, kept in `devices.toml` in the config directory.
//!
//! Only a SHA-256 hash of each token is stored. The file is read on every
//! check, so `yaedock unpair` takes effect without restarting the daemon.

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
    /// USB serial numbers of this phone, learnt when it first connected by
    /// cable. A phone with one is switched to the cable as soon as it is
    /// plugged in.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub usb: Vec<String>,
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
            usb: Vec::new(),
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

    /// Notes that the paired phone `id` has the USB serial number `serial`.
    /// Returns false when nothing changed.
    pub fn remember_usb(&self, id: &str, serial: &str) -> anyhow::Result<bool> {
        let mut file = self.read()?;
        let Some(index) = file.devices.iter().position(|device| device.id == id) else {
            return Ok(false);
        };
        if file.devices[index].usb.iter().any(|known| known == serial) {
            return Ok(false);
        }
        // A serial belongs to one phone; it may have been paired anew.
        for device in &mut file.devices {
            device.usb.retain(|known| known != serial);
        }
        file.devices[index].usb.push(serial.to_string());
        self.write(&file)?;
        Ok(true)
    }

    /// Whether `serial` is the USB serial number of a paired phone.
    pub fn knows_usb(&self, serial: &str) -> anyhow::Result<bool> {
        Ok(self
            .read()?
            .devices
            .iter()
            .any(|device| device.usb.iter().any(|known| known == serial)))
    }

    /// Forgets the USB serial number `serial`, for a phone that turned the cable down.
    pub fn forget_usb(&self, serial: &str) -> anyhow::Result<()> {
        let mut file = self.read()?;
        if !file
            .devices
            .iter()
            .any(|device| device.usb.iter().any(|known| known == serial))
        {
            return Ok(());
        }
        for device in &mut file.devices {
            device.usb.retain(|known| known != serial);
        }
        self.write(&file)
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

#[cfg(test)]
mod tests {
    use super::*;

    fn store(name: &str) -> DeviceStore {
        let dir =
            std::env::temp_dir().join(format!("yaedock-devices-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        DeviceStore {
            path: dir.join("devices.toml"),
        }
    }

    #[test]
    fn remembers_a_phones_usb_serial() {
        let store = store("usb");
        store.add("a", "Phone A").unwrap();
        store.add("b", "Phone B").unwrap();
        assert!(!store.knows_usb("S1").unwrap());
        assert!(store.remember_usb("a", "S1").unwrap());
        assert!(!store.remember_usb("a", "S1").unwrap());
        assert!(!store.remember_usb("nobody", "S2").unwrap());
        assert!(store.knows_usb("S1").unwrap());
        // The serial moves with the phone that has it now.
        assert!(store.remember_usb("b", "S1").unwrap());
        let devices = store.list().unwrap();
        assert!(devices.iter().find(|d| d.id == "a").unwrap().usb.is_empty());
        assert_eq!(devices.iter().find(|d| d.id == "b").unwrap().usb, ["S1"]);
        store.forget_usb("S1").unwrap();
        assert!(!store.knows_usb("S1").unwrap());
        // Unpairing forgets the serials with the phone.
        store.remember_usb("a", "S3").unwrap();
        store.remove("a").unwrap();
        assert!(!store.knows_usb("S3").unwrap());
    }

    #[test]
    fn reads_a_file_written_before_usb() {
        let store = store("old");
        fs::write(
            &store.path,
            "[[device]]\nid = \"a\"\nname = \"A\"\npaired_at = 1\ntoken_sha256 = \"x\"\n",
        )
        .unwrap();
        assert!(store.list().unwrap()[0].usb.is_empty());
    }
}
