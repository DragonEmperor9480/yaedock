//! Silent screenshots: saved on the PC without any sound, flash or
//! notification there.

use std::env;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use crate::{ControlError, desktop};

/// Takes screenshots of all screens.
pub trait ScreenshotBackend: Send + Sync {
    /// What this backend uses, for reports such as `yaedock check`.
    fn name(&self) -> &'static str;

    /// Saves a screenshot into `folder` and returns its path.
    fn take(&self, folder: &Path) -> Result<PathBuf, ControlError>;
}

/// Spectacle, KDE's screenshot tool, which can run without a window or a
/// notification. Fully silent on KDE Plasma and on X11; other Wayland desktops
/// may ask for permission through their screenshot portal.
pub struct SpectacleBackend;

/// Spectacle if it is installed.
pub fn detect() -> Option<Box<dyn ScreenshotBackend>> {
    let path = env::var_os("PATH")?;
    env::split_paths(&path)
        .any(|dir| dir.join("spectacle").is_file())
        .then(|| Box::new(SpectacleBackend) as Box<dyn ScreenshotBackend>)
}

impl ScreenshotBackend for SpectacleBackend {
    fn name(&self) -> &'static str {
        "Spectacle"
    }

    fn take(&self, folder: &Path) -> Result<PathBuf, ControlError> {
        fs::create_dir_all(folder)?;
        let path = folder.join(file_name(chrono::Local::now()));
        let output = Command::new("spectacle")
            .envs(desktop::session_env())
            .args(["--background", "--nonotify", "--fullscreen", "--output"])
            .arg(&path)
            .stdin(Stdio::null())
            .output()?;
        // Spectacle can exit successfully without saving, for example when the
        // desktop refused, so the file itself is the proof.
        if !output.status.success() || !path.is_file() {
            let why = String::from_utf8_lossy(&output.stderr);
            let why = why.lines().last().unwrap_or("no file was written").trim();
            return Err(io::Error::other(format!("Spectacle failed: {why}")).into());
        }
        Ok(path)
    }
}

/// `Screenshot_20261019_180211.png`, as Spectacle names its own.
fn file_name(time: chrono::DateTime<chrono::Local>) -> String {
    time.format("Screenshot_%Y%m%d_%H%M%S.png").to_string()
}

/// `Screenshots` in the user's Pictures folder, as `xdg-user-dirs` names it
/// (`~/Pictures` unless set otherwise).
pub fn folder() -> Option<PathBuf> {
    let home = PathBuf::from(env::var_os("HOME")?);
    let config = env::var_os("XDG_CONFIG_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home.join(".config"));
    let pictures = fs::read_to_string(config.join("user-dirs.dirs"))
        .ok()
        .and_then(|text| pictures_dir(&text, &home))
        .unwrap_or_else(|| home.join("Pictures"));
    Some(pictures.join("Screenshots"))
}

/// Reads `XDG_PICTURES_DIR="$HOME/Pictures"` from `user-dirs.dirs`.
fn pictures_dir(text: &str, home: &Path) -> Option<PathBuf> {
    let value = text
        .lines()
        .find_map(|line| line.trim().strip_prefix("XDG_PICTURES_DIR="))?
        .trim()
        .trim_matches('"');
    let path = match value.strip_prefix("$HOME") {
        Some(rest) => home.join(rest.trim_start_matches('/')),
        None => PathBuf::from(value),
    };
    // A folder set to the home folder itself means "no Pictures folder".
    (path.is_absolute() && path != home).then_some(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn finds_the_pictures_folder() {
        let home = Path::new("/home/me");
        let dirs =
            "# comment\nXDG_DESKTOP_DIR=\"$HOME/Desktop\"\nXDG_PICTURES_DIR=\"$HOME/Bilder\"\n";
        assert_eq!(
            pictures_dir(dirs, home),
            Some(PathBuf::from("/home/me/Bilder"))
        );
        assert_eq!(
            pictures_dir("XDG_PICTURES_DIR=\"/data/pics\"", home),
            Some(PathBuf::from("/data/pics"))
        );
        assert_eq!(pictures_dir("XDG_PICTURES_DIR=\"$HOME/\"", home), None);
        assert_eq!(pictures_dir("", home), None);
    }

    #[test]
    fn names_files_by_time() {
        let time = chrono::Local
            .with_ymd_and_hms(2026, 10, 19, 18, 2, 11)
            .unwrap();
        assert_eq!(file_name(time), "Screenshot_20261019_180211.png");
    }
}
