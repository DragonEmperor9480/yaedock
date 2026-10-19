//! Display brightness backends.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use tracing::debug;
use yaedock_proto::DisplayState;

use crate::ControlError;

/// Reads and changes display brightness.
pub trait BrightnessBackend: Send + Sync {
    /// Every controllable display with its current brightness.
    fn displays(&self) -> Result<Vec<DisplayState>, ControlError>;

    /// Sets a display's brightness in percent, 0 to 100.
    fn set(&self, display: &str, percent: u8) -> Result<(), ControlError>;
}

/// The laptop panel backlight from `/sys/class/backlight`.
///
/// Changes go through logind's `SetBrightness`, which needs no special
/// permissions. If logind is unavailable it writes the sysfs file directly,
/// which needs the udev rule from `packaging/udev`.
pub struct BacklightBackend {
    /// `None` when the laptop has no backlight device.
    device: Option<Backlight>,
    logind: Option<zbus::blocking::Connection>,
}

struct Backlight {
    name: String,
    path: PathBuf,
    max: u32,
}

impl BacklightBackend {
    pub fn new() -> Self {
        let device = find_backlight(Path::new("/sys/class/backlight"));
        let logind = zbus::blocking::Connection::system()
            .inspect_err(|err| debug!("no system bus, writing sysfs directly: {err}"))
            .ok();
        Self { device, logind }
    }

    fn device(&self, display: &str) -> Result<&Backlight, ControlError> {
        self.device
            .as_ref()
            .filter(|device| display_id(&device.name) == display)
            .ok_or_else(|| ControlError::UnknownDevice(display.to_string()))
    }
}

impl Default for BacklightBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl BrightnessBackend for BacklightBackend {
    fn displays(&self) -> Result<Vec<DisplayState>, ControlError> {
        let Some(device) = &self.device else {
            return Ok(Vec::new());
        };
        let raw = read_u32(&device.path.join("brightness"))?;
        Ok(vec![DisplayState {
            id: display_id(&device.name),
            name: "Built-in display".into(),
            percent: to_percent(raw, device.max),
        }])
    }

    fn set(&self, display: &str, percent: u8) -> Result<(), ControlError> {
        let device = self.device(display)?;
        let raw = to_raw(percent, device.max);
        if let Some(conn) = &self.logind {
            let result = conn.call_method(
                Some("org.freedesktop.login1"),
                "/org/freedesktop/login1/session/auto",
                Some("org.freedesktop.login1.Session"),
                "SetBrightness",
                &("backlight", device.name.as_str(), raw),
            );
            match result {
                Ok(_) => return Ok(()),
                Err(err) => debug!("logind SetBrightness failed, writing sysfs: {err}"),
            }
        }
        fs::write(device.path.join("brightness"), raw.to_string())?;
        Ok(())
    }
}

fn display_id(device: &str) -> String {
    format!("backlight:{device}")
}

/// Picks the backlight device to control. When a laptop exposes several for
/// the same panel, prefer `firmware`, then `platform`, then `raw`, as desktop
/// environments do.
fn find_backlight(root: &Path) -> Option<Backlight> {
    let mut best: Option<(u8, Backlight)> = None;
    for entry in fs::read_dir(root).ok()?.flatten() {
        let path = entry.path();
        let rank = match fs::read_to_string(path.join("type"))
            .unwrap_or_default()
            .trim()
        {
            "firmware" => 0,
            "platform" => 1,
            _ => 2,
        };
        let Ok(max) = read_u32(&path.join("max_brightness")) else {
            continue;
        };
        if max == 0
            || best
                .as_ref()
                .is_some_and(|(best_rank, _)| *best_rank <= rank)
        {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        best = Some((rank, Backlight { name, path, max }));
    }
    best.map(|(_, device)| device)
}

fn read_u32(path: &Path) -> io::Result<u32> {
    fs::read_to_string(path)?
        .trim()
        .parse()
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

fn to_percent(raw: u32, max: u32) -> u8 {
    ((raw.min(max) as f64 * 100.0 / max as f64).round()) as u8
}

/// Converts a percentage to a raw value, never 0, because on many panels 0
/// turns the backlight off completely.
fn to_raw(percent: u8, max: u32) -> u32 {
    ((percent.min(100) as f64 * max as f64 / 100.0).round() as u32).max(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percent_conversion() {
        assert_eq!(to_percent(24000, 24000), 100);
        assert_eq!(to_percent(12000, 24000), 50);
        assert_eq!(to_raw(50, 24000), 12000);
        assert_eq!(to_raw(0, 24000), 1);
        assert_eq!(to_raw(200, 255), 255);
    }

    #[test]
    fn prefers_firmware_backlight() {
        let root = std::env::temp_dir().join(format!("yaedock-backlight-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        for (name, kind) in [("intel_backlight", "raw"), ("acpi_video0", "firmware")] {
            let dir = root.join(name);
            fs::create_dir_all(&dir).unwrap();
            fs::write(dir.join("type"), format!("{kind}\n")).unwrap();
            fs::write(dir.join("max_brightness"), "100\n").unwrap();
        }
        assert_eq!(find_backlight(&root).unwrap().name, "acpi_video0");
        fs::remove_dir_all(&root).unwrap();
    }
}
