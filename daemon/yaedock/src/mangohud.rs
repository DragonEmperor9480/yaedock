//! Setting up MangoHud so the daemon can read game frame rates: a marked block
//! at the end of the user's MangoHud config turns its logging on and points it
//! at the folder the daemon watches. Everything else in the config is kept.

use std::env;
use std::fs;
use std::path::PathBuf;

use anyhow::Context;
use yaedock_core::paths;

const BEGIN: &str = "# >>> YaeDock: send game frame rates to the phone (managed by `yaedock fps`)";
const END: &str = "# <<< YaeDock";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    NotInstalled,
    Off,
    On,
}

pub fn config_path() -> anyhow::Result<PathBuf> {
    let base = match env::var_os("XDG_CONFIG_HOME").filter(|v| !v.is_empty()) {
        Some(dir) => PathBuf::from(dir),
        None => PathBuf::from(env::var_os("HOME").context("HOME is not set")?).join(".config"),
    };
    Ok(base.join("MangoHud/MangoHud.conf"))
}

/// Whether MangoHud is installed: its launcher on `PATH`, or its Vulkan layer.
fn installed() -> bool {
    let on_path = env::var_os("PATH")
        .is_some_and(|path| env::split_paths(&path).any(|dir| dir.join("mangohud").is_file()));
    let home_layers =
        env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share/vulkan/implicit_layer.d"));
    let layer = [
        "/usr/share/vulkan/implicit_layer.d".into(),
        "/etc/vulkan/implicit_layer.d".into(),
    ]
    .into_iter()
    .chain(home_layers)
    .any(|dir: PathBuf| {
        fs::read_dir(dir).is_ok_and(|entries| {
            entries
                .flatten()
                .any(|e| e.file_name().to_string_lossy().starts_with("MangoHud"))
        })
    });
    on_path || layer
}

pub fn state() -> State {
    let configured = config_path()
        .and_then(|p| Ok(fs::read_to_string(p)?))
        .is_ok_and(|text| text.contains(BEGIN));
    match (configured, installed()) {
        (true, _) => State::On,
        (false, true) => State::Off,
        (false, false) => State::NotInstalled,
    }
}

/// Whether the config hides the overlay, which stops MangoHud from logging.
pub fn overlay_hidden() -> bool {
    config_path()
        .and_then(|p| Ok(fs::read_to_string(p)?))
        .is_ok_and(|text| {
            without_block(&text)
                .lines()
                .any(|l| l.trim() == "no_display" || l.trim() == "no_display=1")
        })
}

/// Adds (or refreshes) the YaeDock block. Returns the config's path.
pub fn enable() -> anyhow::Result<PathBuf> {
    let path = config_path()?;
    let logs = paths::mangohud_logs()?;
    fs::create_dir_all(&logs)?;
    let existing = fs::read_to_string(&path).unwrap_or_default();
    let mut text = without_block(&existing).trim_end().to_string();
    if !text.is_empty() {
        text.push_str("\n\n");
    }
    text.push_str(&block(&logs.to_string_lossy()));
    fs::create_dir_all(path.parent().context("config path has no folder")?)?;
    fs::write(&path, text).with_context(|| format!("writing {}", path.display()))?;
    Ok(path)
}

/// Removes the YaeDock block, leaving the rest of the config as it was.
pub fn disable() -> anyhow::Result<PathBuf> {
    let path = config_path()?;
    let existing =
        fs::read_to_string(&path).with_context(|| format!("reading {}", path.display()))?;
    let mut text = without_block(&existing).trim_end().to_string();
    text.push('\n');
    fs::write(&path, text).with_context(|| format!("writing {}", path.display()))?;
    Ok(path)
}

fn block(logs: &str) -> String {
    // Later lines win in MangoHud's config, so this block goes at the end.
    format!("{BEGIN}\nautostart_log=1\nlog_interval=500\noutput_folder={logs}\n{END}\n")
}

/// The config without the YaeDock block.
fn without_block(text: &str) -> String {
    let mut out = String::new();
    let mut inside = false;
    for line in text.lines() {
        if line == BEGIN {
            inside = true;
        } else if inside && line == END {
            inside = false;
        } else if !inside {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds_and_removes_only_its_own_block() {
        let user = "fps\nframe_timing\nposition=top-left\n";
        let with = format!(
            "{}\n\n{}",
            user.trim_end(),
            block("/run/user/1000/yaedock/mangohud")
        );
        assert!(with.contains("autostart_log=1"));
        assert!(with.starts_with("fps\nframe_timing"));
        assert_eq!(without_block(&with).trim_end(), user.trim_end());
        // Adding twice still leaves a single block.
        let twice = format!("{}\n\n{}", without_block(&with).trim_end(), block("/x"));
        assert_eq!(twice.matches(BEGIN).count(), 1);
    }
}
