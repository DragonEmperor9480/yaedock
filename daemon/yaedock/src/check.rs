//! The system check: finds out which YaeDock features work on this PC, and
//! how to fix the ones that do not. It only looks; it never changes anything.

use std::io::IsTerminal;
use std::net::TcpListener;
use std::process::Command;

use yaedock_core::control::{self, DaemonStatus};
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, paths};
use yaedock_proto::Stats;
use yaedock_sys::brightness::{BacklightBackend, BrightnessBackend};
use yaedock_sys::metrics::{DiskSource, HwmonSource, MetricSource, NetSource, SystemSource};
use yaedock_sys::volume;

use crate::{pair, service};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Outcome {
    Ok,
    /// Works, with a limitation.
    Warn,
    /// Will not work until fixed.
    Fail,
}

#[derive(Debug, Clone)]
pub struct Check {
    pub area: &'static str,
    pub outcome: Outcome,
    pub summary: String,
    /// What to do about a warning or failure.
    pub fix: Option<String>,
}

fn ok(area: &'static str, summary: impl Into<String>) -> Check {
    Check {
        area,
        outcome: Outcome::Ok,
        summary: summary.into(),
        fix: None,
    }
}

fn warn(area: &'static str, summary: impl Into<String>, fix: impl Into<String>) -> Check {
    Check {
        area,
        outcome: Outcome::Warn,
        summary: summary.into(),
        fix: Some(fix.into()),
    }
}

fn fail(area: &'static str, summary: impl Into<String>, fix: impl Into<String>) -> Check {
    Check {
        area,
        outcome: Outcome::Fail,
        summary: summary.into(),
        fix: Some(fix.into()),
    }
}

/// Runs every check. Takes about a second, mostly to measure CPU usage.
pub fn run() -> Vec<Check> {
    let daemon = control::status_blocking().ok().flatten();
    vec![
        systemd(),
        stats(),
        temperatures(),
        brightness(),
        volume(),
        network(),
        port(daemon.as_ref()),
        firewall(),
        certificate(),
        daemon_state(daemon.as_ref()),
    ]
}

fn systemd() -> Check {
    if std::env::var_os("XDG_RUNTIME_DIR").is_none() {
        return fail(
            "systemd",
            "No user session (XDG_RUNTIME_DIR is not set)",
            "Run yaedock from a normal desktop login rather than su or a bare SSH session.",
        );
    }
    match service::state() {
        Ok(_) => ok("systemd", "User services are available"),
        Err(err) => fail(
            "systemd",
            format!("Cannot talk to the systemd user manager: {err:#}"),
            "YaeDock needs systemd to run in the background; `yaedock daemon` still works in a terminal.",
        ),
    }
}

fn stats() -> Check {
    let mut stats = Stats::default();
    let mut system = SystemSource::new();
    let mut failures = Vec::new();
    if let Err(err) = system.sample(&mut stats) {
        failures.push(format!("CPU/RAM: {err}"));
    }
    if let Err(err) = NetSource::new().sample(&mut stats) {
        failures.push(format!("network: {err}"));
    }
    match DiskSource::new() {
        Ok(mut disk) => {
            if let Err(err) = disk.sample(&mut stats) {
                failures.push(format!("disk: {err}"));
            }
        }
        Err(err) => failures.push(format!("disk: {err}")),
    }
    if !failures.is_empty() {
        return warn(
            "Stats",
            format!("Some stats are unavailable ({})", failures.join("; ")),
            "Those tiles stay empty on the phone.",
        );
    }
    let cores = stats.cpu.map_or(0, |c| c.cores.len());
    let ram = stats.mem.map_or(0, |m| m.total) as f64 / (1u64 << 30) as f64;
    ok(
        "Stats",
        format!("CPU ({cores} threads), RAM ({ram:.1} GiB), disk and network"),
    )
}

fn temperatures() -> Check {
    let Ok(mut hwmon) = HwmonSource::new() else {
        return warn(
            "Temperatures",
            "No hardware sensors found",
            "The temperature tile stays empty. Loading the `coretemp` or `k10temp` module may help.",
        );
    };
    let names = hwmon.sensor_names();
    if names.is_empty() {
        return warn(
            "Temperatures",
            "No temperature sensors found",
            "The temperature tile stays empty.",
        );
    }
    let mut stats = Stats::default();
    let _ = hwmon.sample(&mut stats);
    let temps = stats.temps.unwrap_or_default();
    let main = temps
        .get("cpu_package")
        .map(|t| format!(", CPU package {t:.0} °C"))
        .unwrap_or_default();
    ok("Temperatures", format!("{} sensors{main}", names.len()))
}

fn brightness() -> Check {
    let backend = BacklightBackend::new();
    match backend.displays() {
        Ok(displays) if displays.is_empty() => warn(
            "Brightness",
            "No laptop backlight found",
            "Brightness control works for laptop screens; external monitors (DDC/CI) are planned.",
        ),
        Ok(displays) => match backend.control_method() {
            Some(method) => ok(
                "Brightness",
                format!(
                    "{} at {}%, changed through {method}",
                    displays[0].name, displays[0].percent
                ),
            ),
            None => warn(
                "Brightness",
                format!("{} can be read but not changed", displays[0].name),
                "Make sure you are logged in to a desktop session (logind), or install the udev rule from packaging/udev.",
            ),
        },
        Err(err) => fail(
            "Brightness",
            format!("Cannot read the backlight: {err}"),
            "Brightness control will not work.",
        ),
    }
}

fn volume() -> Check {
    let Some(backend) = volume::detect() else {
        return fail(
            "Volume",
            "No volume control found",
            "Install and run PipeWire (wpctl) or PulseAudio (pactl).",
        );
    };
    let current = backend
        .get()
        .map(|v| format!(" at {}%{}", v.percent, if v.muted { ", muted" } else { "" }))
        .unwrap_or_default();
    if volume::can_watch() {
        ok(
            "Volume",
            format!("{}{current}, live updates", backend.name()),
        )
    } else {
        warn(
            "Volume",
            format!("{}{current}", backend.name()),
            "Install pactl (libpulse) so volume changes made on the PC reach the phone instantly instead of within a second.",
        )
    }
}

fn network() -> Check {
    match pair::lan_address() {
        Some(address) => ok("Network", format!("This PC is at {address}")),
        None => fail(
            "Network",
            "No network address found",
            "Connect this PC to the same Wi-Fi or LAN as the phone.",
        ),
    }
}

fn port(daemon: Option<&DaemonStatus>) -> Check {
    if let Some(d) = daemon {
        return ok(
            "Port",
            format!("{} is used by the YaeDock daemon", d.listen),
        );
    }
    match TcpListener::bind(("0.0.0.0", DEFAULT_PORT)) {
        Ok(_) => ok("Port", format!("TCP {DEFAULT_PORT} is free")),
        Err(err) => fail(
            "Port",
            format!("TCP {DEFAULT_PORT} is taken by another program ({err})"),
            "Stop that program, or run the daemon on another port with `yaedock daemon --listen 0.0.0.0:PORT`.".to_string(),
        ),
    }
}

fn firewall() -> Check {
    let active = |unit: &str| {
        Command::new("systemctl")
            .args(["is-active", "--quiet", unit])
            .status()
            .is_ok_and(|s| s.success())
    };
    if active("ufw") {
        warn(
            "Firewall",
            "ufw is on",
            format!(
                "Allow the phone in, if you have not already: sudo ufw allow {DEFAULT_PORT}/tcp"
            ),
        )
    } else if active("firewalld") {
        warn(
            "Firewall",
            "firewalld is on",
            format!(
                "Allow the phone in, if you have not already: sudo firewall-cmd --permanent --add-port={DEFAULT_PORT}/tcp && sudo firewall-cmd --reload"
            ),
        )
    } else {
        ok("Firewall", "No active firewall found (ufw, firewalld)")
    }
}

fn certificate() -> Check {
    match paths::config_dir().and_then(|dir| Identity::load(&dir)) {
        Ok(identity) => ok(
            "Certificate",
            format!("Fingerprint {}…", &identity.fingerprint()[..16]),
        ),
        Err(_) => ok(
            "Certificate",
            "Not created yet; the daemon makes one on first start",
        ),
    }
}

fn daemon_state(daemon: Option<&DaemonStatus>) -> Check {
    let service = service::state().ok();
    match (daemon, service) {
        (Some(_), Some(s)) if s.running && s.enabled => {
            ok("Daemon", "Running in the background and starts at login")
        }
        (Some(_), Some(s)) if s.running => warn(
            "Daemon",
            "Running in the background",
            "Turn on Start at login so the phone always finds this PC.",
        ),
        (Some(d), _) => warn(
            "Daemon",
            format!("Running in a terminal (pid {})", d.pid),
            "Stop it there and start it from the menu to keep it running in the background.",
        ),
        (None, Some(s)) if s.installed => fail(
            "Daemon",
            "Installed but not running",
            "Start it from the menu, or run `yaedock service start`.",
        ),
        (None, _) => fail(
            "Daemon",
            "Not installed",
            "Install it from the menu, or run `yaedock service enable`.",
        ),
    }
}

/// `yaedock check`: prints each check, coloured when writing to a terminal.
pub fn print() -> anyhow::Result<()> {
    let color = std::io::stdout().is_terminal();
    let paint = |code: &str, text: &str| {
        if color {
            format!("\x1b[{code}m{text}\x1b[0m")
        } else {
            text.to_string()
        }
    };
    println!("Checking this PC…\n");
    let checks = run();
    for check in &checks {
        let mark = match check.outcome {
            Outcome::Ok => paint("32", "✓"),
            Outcome::Warn => paint("33", "!"),
            Outcome::Fail => paint("31", "✗"),
        };
        println!("{mark} {:<13} {}", check.area, check.summary);
        if let Some(fix) = &check.fix {
            println!("  {:<13} {}", "", paint("2", fix));
        }
    }
    let count = |o| checks.iter().filter(|c| c.outcome == o).count();
    println!(
        "\n{} working, {} with limits, {} not working",
        count(Outcome::Ok),
        count(Outcome::Warn),
        count(Outcome::Fail)
    );
    Ok(())
}
