//! The desktop session's environment, for programs that draw on or capture
//! the screen.
//!
//! The daemon starts with the user's systemd manager, usually before the
//! desktop has told the manager where its display is (`WAYLAND_DISPLAY`,
//! `DISPLAY`). The manager learns it a moment later, but the daemon's own
//! environment never changes, so programs it starts would find no display.
//! Asking the manager when starting one gets the current values.

use std::process::{Command, Stdio};

/// What a graphical program needs to find the desktop.
const DESKTOP_VARS: &[&str] = &[
    "WAYLAND_DISPLAY",
    "DISPLAY",
    "XAUTHORITY",
    "XDG_CURRENT_DESKTOP",
    "XDG_SESSION_DESKTOP",
    "XDG_SESSION_TYPE",
];

/// The desktop variables the user's systemd manager knows now; none if it
/// cannot be asked.
pub fn session_env() -> Vec<(String, String)> {
    Command::new("systemctl")
        .args(["--user", "show-environment"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()
        .filter(|output| output.status.success())
        .map(|output| parse(&String::from_utf8_lossy(&output.stdout)))
        .unwrap_or_default()
}

/// `KEY=value` lines, keeping only [`DESKTOP_VARS`]. Values with special
/// characters come quoted as `$'…'`; none of these variables need them, so
/// those are left out rather than unquoted.
fn parse(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|line| line.split_once('='))
        .filter(|(key, value)| DESKTOP_VARS.contains(key) && !value.starts_with("$'"))
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_the_display_variables() {
        let text = "DISPLAY=:0\nHOME=/home/shura\nWAYLAND_DISPLAY=wayland-0\n\
                    XDG_CURRENT_DESKTOP=KDE\nLS_COLORS=$'rs=0:di=01;34'\n";
        assert_eq!(
            parse(text),
            vec![
                ("DISPLAY".into(), ":0".into()),
                ("WAYLAND_DISPLAY".into(), "wayland-0".into()),
                ("XDG_CURRENT_DESKTOP".into(), "KDE".into()),
            ]
        );
    }
}
