//! Setting up OBS Studio so the phone can record the screen: turns on OBS's
//! WebSocket server, which the daemon reaches on this PC only. The password
//! stays in OBS's settings, where the daemon reads it.

use std::fs;
use std::path::PathBuf;

use anyhow::{Context, bail};
use serde_json::{Value, json};
use yaedock_core::devices::random_hex;
use yaedock_core::paths::write_private;
use yaedock_sys::recorder::{ObsInstall, WebSocketSettings, obs_running};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    NotInstalled,
    Off,
    On,
}

pub fn state() -> State {
    let Some(install) = ObsInstall::find() else {
        return State::NotInstalled;
    };
    if WebSocketSettings::read(install).is_ok_and(|s| s.enabled) {
        State::On
    } else {
        State::Off
    }
}

/// The WebSocket port when the server is on.
pub fn port() -> Option<u16> {
    let settings = WebSocketSettings::read(ObsInstall::find()?).ok()?;
    settings.enabled.then_some(settings.port)
}

/// Turns the WebSocket server on, with a password (a new random one if
/// there is none). Returns the settings file.
pub fn enable() -> anyhow::Result<PathBuf> {
    update(|config| {
        config["server_enabled"] = true.into();
        let no_password = config["server_password"].as_str().is_none_or(str::is_empty);
        if no_password {
            config["server_password"] = random_hex(16)?.into();
        }
        config["auth_required"] = true.into();
        Ok(())
    })
}

/// Turns the WebSocket server off, leaving the rest as it was.
pub fn disable() -> anyhow::Result<PathBuf> {
    update(|config| {
        config["server_enabled"] = false.into();
        Ok(())
    })
}

fn update(change: impl FnOnce(&mut Value) -> anyhow::Result<()>) -> anyhow::Result<PathBuf> {
    let install = ObsInstall::find().context("OBS Studio is not installed")?;
    // OBS writes its settings back when it exits, which would undo the change.
    if obs_running() {
        bail!("close OBS Studio first: it rewrites these settings when it exits");
    }
    let path = install.websocket_config().context("HOME is not set")?;
    let mut config = match fs::read_to_string(&path) {
        Ok(text) => serde_json::from_str(&text)
            .with_context(|| format!("{} is not valid JSON", path.display()))?,
        // OBS has not been opened yet; it fills in the rest on first start.
        Err(_) => json!({"server_port": 4455, "alerts_enabled": false, "first_load": false}),
    };
    change(&mut config)?;
    fs::create_dir_all(path.parent().context("settings path has no folder")?)?;
    // Readable only by the user: it holds the password.
    let text = serde_json::to_string_pretty(&config)? + "\n";
    write_private(&path, text.as_bytes()).with_context(|| format!("writing {}", path.display()))?;
    Ok(path)
}
