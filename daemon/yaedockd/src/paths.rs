//! Where the daemon keeps its files.

use std::env;
use std::fs::{self, DirBuilder, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
use std::path::{Path, PathBuf};

use anyhow::Context;

/// `$XDG_CONFIG_HOME/yaedock`, usually `~/.config/yaedock`: the certificate
/// and paired devices.
pub fn config_dir() -> anyhow::Result<PathBuf> {
    let base = match env::var_os("XDG_CONFIG_HOME").filter(|v| !v.is_empty()) {
        Some(dir) => PathBuf::from(dir),
        None => PathBuf::from(env::var_os("HOME").context("HOME is not set")?).join(".config"),
    };
    private_dir(base.join("yaedock"))
}

/// Creates `dir` readable only by the user, if it does not exist.
fn private_dir(dir: PathBuf) -> anyhow::Result<PathBuf> {
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&dir)
        .with_context(|| format!("creating {}", dir.display()))?;
    Ok(dir)
}

/// Writes a file readable only by the user, replacing it atomically.
pub fn write_private(path: &Path, contents: &[u8]) -> io::Result<()> {
    let tmp = path.with_extension("tmp");
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&tmp)?;
    file.write_all(contents)?;
    file.sync_all()?;
    fs::rename(&tmp, path)
}
