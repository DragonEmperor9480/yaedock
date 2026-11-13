//! The system check: finds out which YaeDock features work on this PC, and
//! how to fix the ones that do not. It only looks; it never changes anything.

use std::io::IsTerminal;
use std::net::TcpListener;

use yaedock_core::control::{self, DaemonStatus};
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, paths};
use yaedock_proto::{CheckOutcome, CheckReport, CheckResult, Stats};
use yaedock_sys::brightness::{BacklightBackend, BrightnessBackend};
use yaedock_sys::media::{MediaBackend, MprisBackend};
use yaedock_sys::metrics::{DiskSource, HwmonSource, MetricSource, NetSource, SystemSource};
use yaedock_sys::{power, screenshot, volume};

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
    /// A command (needing root) that fixes it, which the setup wizard can run.
    pub command: Option<String>,
    /// A setting the menu can turn on itself, without root.
    pub switch: Option<Switch>,
}

/// A setting that fixes a warning, which the menu can turn on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Switch {
    /// OBS Studio's WebSocket server, as `yaedock obs on` does.
    Recording,
    /// MangoHud's log for YaeDock, as `yaedock fps on` does.
    GameFps,
}

fn ok(area: &'static str, summary: impl Into<String>) -> Check {
    Check {
        area,
        outcome: Outcome::Ok,
        summary: summary.into(),
        fix: None,
        command: None,
        switch: None,
    }
}

fn warn(area: &'static str, summary: impl Into<String>, fix: impl Into<String>) -> Check {
    Check {
        area,
        outcome: Outcome::Warn,
        summary: summary.into(),
        fix: Some(fix.into()),
        command: None,
        switch: None,
    }
}

fn fail(area: &'static str, summary: impl Into<String>, fix: impl Into<String>) -> Check {
    Check {
        area,
        outcome: Outcome::Fail,
        summary: summary.into(),
        fix: Some(fix.into()),
        command: None,
        switch: None,
    }
}

/// Runs every check. Takes about a second, mostly to measure CPU usage.
pub fn run() -> Vec<Check> {
    let daemon = control::status_blocking().ok().flatten();
    vec![
        systemd(),
        stats(),
        temperatures(),
        gpu_names(),
        gpu_stats(),
        battery(),
        brightness(),
        volume(),
        microphone(),
        media(),
        power_profile(),
        screenshots(),
        recording(),
        system_actions(),
        shortcuts(),
        network(),
        port(daemon.as_ref()),
        firewall(),
        discovery(),
        game_fps(),
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

/// GPU names come from the PCI ID database; without it they are generic.
fn gpu_names() -> Check {
    use yaedock_sys::metrics::{GpuSource, pci_ids_found};
    let gpus = GpuSource::new().gpus();
    if gpus.is_empty() {
        return ok("GPU", "No GPU found to report on");
    }
    let names: Vec<String> = gpus.into_iter().map(|g| g.name).collect();
    if pci_ids_found() {
        return ok("GPU", names.join(", "));
    }
    let summary = format!(
        "Generic names ({}): the PCI ID list is not installed",
        names.join(", ")
    );
    let restart = "then restart the daemon so the phone gets the new names";
    match install_command(PCI_IDS_PACKAGE) {
        Some(command) => Check {
            area: "GPU",
            outcome: Outcome::Warn,
            summary,
            fix: Some(format!("Install it with `{command}`, {restart}.")),
            command: Some(command),
            switch: None,
        },
        None => warn(
            "GPU",
            summary,
            format!("Install your distribution's hwdata or pciutils package, {restart}."),
        ),
    }
}

/// GPU usage, temperature and memory, which depend on the driver.
fn gpu_stats() -> Check {
    use yaedock_sys::metrics::GpuSource;
    let mut source = GpuSource::new();
    let gpus = source.gpus();
    if gpus.is_empty() {
        return ok("GPU stats", "No GPU to report on");
    }
    // Intel's usage is measured between two readings.
    let mut stats = Stats::default();
    let _ = source.sample(&mut stats);
    std::thread::sleep(std::time::Duration::from_millis(250));
    let _ = source.sample(&mut stats);
    let readings = stats.gpus.unwrap_or_default();

    let mut working = Vec::new();
    let mut asleep = Vec::new();
    let mut missing = Vec::new();
    for gpu in &gpus {
        match readings.iter().find(|r| r.id == gpu.id) {
            Some(r) if r.sleeping => asleep.push(gpu.name.as_str()),
            Some(r) if r.usage.is_some() || r.temp.is_some() => working.push(gpu.name.as_str()),
            _ => missing.push(gpu.name.as_str()),
        }
    }
    let mut parts = Vec::new();
    if !working.is_empty() {
        parts.push(format!("Usage from {}", working.join(", ")));
    }
    if !asleep.is_empty() {
        parts.push(format!("{} asleep, shown when it wakes", asleep.join(", ")));
    }
    if missing.is_empty() {
        return ok("GPU stats", parts.join("; "));
    }
    parts.push(format!("no usage from {}", missing.join(", ")));
    let fix = missing
        .iter()
        .map(|name| gpu_stats_fix(name))
        .collect::<Vec<_>>()
        .join(" ");
    warn("GPU stats", capitalize(&parts.join("; ")), fix)
}

/// Why a GPU reports no usage, by its brand (the start of its name).
fn gpu_stats_fix(name: &str) -> String {
    if name.starts_with("NVIDIA") {
        format!(
            "{name} needs the NVIDIA driver's NVML library (in nvidia-utils or your distribution's NVIDIA driver package); the open nouveau driver reports no usage."
        )
    } else if name.starts_with("Intel") {
        format!(
            "The driver for {name} does not report how busy it is; its card shows the clock speed only."
        )
    } else if name.starts_with("AMD") {
        format!("The amdgpu driver did not report usage for {name}; a newer kernel may.")
    } else {
        format!(
            "YaeDock reads usage from Intel, AMD and NVIDIA GPUs only, so {name} shows its name alone."
        )
    }
}

fn capitalize(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

/// The laptop battery, if there is one.
fn battery() -> Check {
    use yaedock_proto::BatteryState;
    use yaedock_sys::metrics::BatterySource;
    let Some(mut source) = BatterySource::new() else {
        return ok(
            "Battery",
            "None found, as on a desktop PC; the phone shows no battery",
        );
    };
    let mut stats = Stats::default();
    match source.sample(&mut stats).map(|()| stats.battery) {
        Ok(Some(battery)) => {
            let state = match battery.state {
                BatteryState::Charging => ", charging",
                BatteryState::Discharging => ", on battery",
                BatteryState::Full => ", full",
                BatteryState::NotCharging => ", plugged in, not charging",
                BatteryState::Unknown => "",
            };
            ok("Battery", format!("{:.0}%{state}", battery.percent))
        }
        Ok(None) => warn(
            "Battery",
            "Found a battery, but it reports no charge",
            "The phone shows no battery until it does.",
        ),
        Err(err) => warn(
            "Battery",
            format!("Cannot read the battery: {err}"),
            "The phone shows no battery until it can be read.",
        ),
    }
}

/// The package with the PCI ID list; every major distribution names it so.
const PCI_IDS_PACKAGE: &str = "hwdata";

/// The command that installs `package` with this PC's package manager, or
/// None when there is no package manager this knows.
fn install_command(package: &str) -> Option<String> {
    let path = std::env::var_os("PATH")?;
    let has = |program: &str| std::env::split_paths(&path).any(|dir| dir.join(program).is_file());
    install_command_for(package, has)
}

fn install_command_for(package: &str, has: impl Fn(&str) -> bool) -> Option<String> {
    let command = if has("pacman") {
        format!("sudo pacman -S --needed {package}")
    } else if has("apt-get") {
        format!("sudo apt-get install {package}")
    } else if has("dnf") {
        format!("sudo dnf install {package}")
    } else if has("zypper") {
        format!("sudo zypper install {package}")
    } else if has("emerge") {
        format!("sudo emerge --noreplace sys-apps/{package}")
    } else {
        return None;
    };
    Some(command)
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
            "Install pactl (in libpulse, or pulseaudio-utils) to choose the audio output and set each app's volume from the phone, and so volume changes made on the PC reach it instantly instead of within a second.",
        )
    }
}

fn microphone() -> Check {
    let mic = volume::detect().and_then(|backend| backend.mic().ok().flatten());
    match mic {
        Some(mic) => ok(
            "Microphone",
            format!(
                "Default microphone at {}%{}",
                mic.percent,
                if mic.muted { ", muted" } else { "" }
            ),
        ),
        None => warn(
            "Microphone",
            "No microphone found",
            "Connect a microphone to mute it from the phone.",
        ),
    }
}

fn media() -> Check {
    let Some(backend) = MprisBackend::new() else {
        return fail(
            "Media",
            "No session D-Bus, so media players cannot be reached",
            "Run YaeDock inside your desktop session.",
        );
    };
    match backend.current() {
        Ok(Some(media)) => ok(
            "Media",
            format!(
                "Controls media players; now {} in {}",
                if media.playing { "playing" } else { "paused" },
                media.app
            ),
        ),
        Ok(None) => ok(
            "Media",
            "Controls media players (MPRIS); none is running now",
        ),
        Err(err) => fail(
            "Media",
            format!("Listing media players failed: {err}"),
            "Check that the session D-Bus works.",
        ),
    }
}

fn power_profile() -> Check {
    match power::detect().map(|backend| backend.get()) {
        Some(Ok(profiles)) => ok(
            "Power",
            format!(
                "Set to {}, can switch to {}",
                profiles.active,
                profiles.available.join(", ")
            ),
        ),
        _ => warn(
            "Power",
            "No power profile service found",
            "Install power-profiles-daemon (or tuned-ppd) and start it, for example `systemctl enable --now power-profiles-daemon`.",
        ),
    }
}

fn screenshots() -> Check {
    let Some(backend) = screenshot::detect() else {
        return warn(
            "Screenshot",
            "Off; silent screenshots need Spectacle",
            "Install Spectacle (the `spectacle` package) to take screenshots from the phone. It is fully silent on KDE Plasma and on X11.",
        );
    };
    let folder = screenshot::folder()
        .map(|f| format!(" into {}", f.display()))
        .unwrap_or_default();
    ok(
        "Screenshot",
        format!("Saved silently with {}{folder}", backend.name()),
    )
}

fn recording() -> Check {
    use crate::firewall::{Proto, Verdict, verdict};
    use crate::obs::{self, State};
    match obs::state() {
        State::NotInstalled => warn(
            "Recording",
            "Off; screen recording needs OBS Studio",
            "Install OBS Studio to record the screen from the phone.",
        ),
        State::Off => Check {
            switch: Some(Switch::Recording),
            ..warn(
                "Recording",
                "OBS Studio is installed but its WebSocket server is off",
                "Close OBS Studio, then run `yaedock obs on`.",
            )
        },
        State::On => {
            // The daemon talks to OBS on this PC; an open port lets the whole
            // network try the OBS password instead.
            let port = obs::port().unwrap_or(4455);
            if let Verdict::Allowed { firewall } = verdict(port, Proto::Tcp) {
                return warn(
                    "Recording",
                    format!("Works, but {firewall} lets other devices reach OBS on port {port}"),
                    format!(
                        "YaeDock does not need that port open. Close it, for example `sudo ufw delete allow {port}/tcp`."
                    ),
                );
            }
            ok(
                "Recording",
                "Through OBS Studio, with its own scene and output settings",
            )
        }
    }
}

fn system_actions() -> Check {
    use yaedock_proto::SystemAction;
    use yaedock_sys::session::{LogindBackend, SessionBackend};
    let actions = LogindBackend::new()
        .map(|logind| logind.available())
        .unwrap_or_default();
    if actions.is_empty() {
        return warn(
            "Lock & power",
            "logind allows none of lock, sleep, restart or shut down",
            "Run YaeDock from a desktop session on this PC; logind allows these to the user at the screen.",
        );
    }
    let names: Vec<&str> = actions
        .iter()
        .map(|action| match action {
            SystemAction::Lock => "lock",
            SystemAction::Suspend => "sleep",
            SystemAction::Reboot => "restart",
            SystemAction::PowerOff => "shut down",
        })
        .collect();
    ok(
        "Lock & power",
        format!("The phone can {}", names.join(", ")),
    )
}

/// The shortcut buttons from `yaedock shortcuts`; a file that does not
/// parse hides every button.
fn shortcuts() -> Check {
    use yaedock_core::shortcuts;
    match shortcuts::load() {
        Ok(list) if list.is_empty() => ok(
            "Shortcuts",
            "None yet; add a button with `yaedock shortcuts add NAME COMMAND`",
        ),
        Ok(list) => {
            let mut names: Vec<&str> = list.iter().take(4).map(|s| s.name.as_str()).collect();
            if list.len() > names.len() {
                names.push("…");
            }
            let count = list.len();
            let s = if count == 1 { "" } else { "s" };
            ok(
                "Shortcuts",
                format!("{count} button{s}: {}", names.join(", ")),
            )
        }
        Err(err) => {
            let file =
                shortcuts::path().map_or("shortcuts.toml".into(), |p| p.display().to_string());
            fail(
                "Shortcuts",
                format!(
                    "shortcuts.toml is not valid: {}",
                    parse_problem(&err.root_cause().to_string())
                ),
                format!(
                    "The phone shows no shortcut buttons until it is fixed. Correct {file}, or delete it and add the buttons again with `yaedock shortcuts add`."
                ),
            )
        }
    }
}

/// A TOML error in one line: the message, from the last line of the report,
/// and where, from its first ("TOML parse error at line 3, column 1").
fn parse_problem(report: &str) -> String {
    let mut lines = report.lines().map(str::trim).filter(|l| !l.is_empty());
    let first = lines.next().unwrap_or_default();
    let Some(message) = lines.next_back() else {
        return first.to_string();
    };
    match first
        .split(" at ")
        .nth(1)
        .and_then(|at| at.split(',').next())
    {
        Some(line) => format!("{message} ({line})"),
        None => message.to_string(),
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
    use crate::firewall::{Proto, Verdict, allow_command, verdict};
    match verdict(DEFAULT_PORT, Proto::Tcp) {
        Verdict::NoFirewall => ok("Firewall", "No active firewall found (ufw, firewalld)"),
        Verdict::Allowed { firewall } => {
            ok("Firewall", format!("{firewall} allows TCP {DEFAULT_PORT}"))
        }
        Verdict::Blocked { firewall } => {
            let command = allow_command(firewall, DEFAULT_PORT, Proto::Tcp);
            Check {
                area: "Firewall",
                outcome: Outcome::Fail,
                summary: format!("{firewall} blocks TCP {DEFAULT_PORT}, so phones cannot connect"),
                fix: Some(format!("Allow it: {command}")),
                command: Some(command),
                switch: None,
            }
        }
        Verdict::Unknown { firewall } => {
            let command = allow_command(firewall, DEFAULT_PORT, Proto::Tcp);
            Check {
                area: "Firewall",
                outcome: Outcome::Warn,
                summary: format!("{firewall} is on; its rules cannot be read without root"),
                fix: Some(format!(
                    "If phones cannot connect, allow the port: {command}"
                )),
                command: Some(command),
                switch: None,
            }
        }
    }
}

/// mDNS lets phones find this PC by itself; without it they still connect by address.
fn discovery() -> Check {
    use crate::firewall::{Proto, Verdict, allow_command, verdict};
    match verdict(5353, Proto::Udp) {
        Verdict::NoFirewall | Verdict::Allowed { .. } => {
            ok("Discovery", "Phones can find this PC on the network")
        }
        Verdict::Blocked { firewall } => {
            let command = allow_command(firewall, 5353, Proto::Udp);
            Check {
                area: "Discovery",
                outcome: Outcome::Warn,
                summary: format!(
                    "{firewall} blocks mDNS (UDP 5353), so phones will not follow this PC to a new address"
                ),
                fix: Some(format!("Allow it: {command}")),
                command: Some(command),
                switch: None,
            }
        }
        Verdict::Unknown { .. } => ok(
            "Discovery",
            "mDNS rules cannot be read without root; phones still connect by address",
        ),
    }
}

/// Optional: game frame rates through MangoHud.
fn game_fps() -> Check {
    use yaedock_core::mangohud::{State, overlay_hidden, state};
    match state() {
        State::NotInstalled => ok(
            "Game FPS",
            "Off; install MangoHud to see game frame rates on the phone",
        ),
        State::Off => Check {
            switch: Some(Switch::GameFps),
            ..warn(
                "Game FPS",
                "MangoHud is installed but not set up for YaeDock",
                "Turn it on with `yaedock fps on`.",
            )
        },
        State::On if overlay_hidden() => warn(
            "Game FPS",
            "On, but your MangoHud config hides the overlay, so it writes no log",
            "Remove no_display from the MangoHud config, or use a small overlay such as fps_only.",
        ),
        State::On => ok(
            "Game FPS",
            "Games started with MangoHud send their frame rate",
        ),
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

/// `yaedock check --json`: the results as a `check_report` message, which
/// the daemon passes on to the phone.
pub fn print_json() -> anyhow::Result<()> {
    let report = report(run());
    serde_json::to_writer(std::io::stdout().lock(), &report)?;
    println!();
    Ok(())
}

fn report(checks: Vec<Check>) -> CheckReport {
    let checks = checks
        .into_iter()
        .map(|check| CheckResult {
            area: check.area.into(),
            outcome: match check.outcome {
                Outcome::Ok => CheckOutcome::Ok,
                Outcome::Warn => CheckOutcome::Warn,
                Outcome::Fail => CheckOutcome::Fail,
            },
            summary: check.summary,
            fix: check.fix,
            command: check.command,
        })
        .collect();
    CheckReport { checks }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn toml_errors_fit_on_one_line() {
        let report = "TOML parse error at line 3, column 1\n  |\n3 | [[shortcut]]\n  | ^\nmissing field `command`\n";
        assert_eq!(parse_problem(report), "missing field `command` (line 3)");
        assert_eq!(parse_problem("permission denied"), "permission denied");
    }

    #[test]
    fn gpu_fixes_name_the_driver() {
        assert!(gpu_stats_fix("NVIDIA GeForce GTX 1050").contains("NVML"));
        assert!(gpu_stats_fix("Intel UHD Graphics 630").contains("clock speed"));
        assert!(gpu_stats_fix("GPU 1af4:1050").contains("Intel, AMD and NVIDIA"));
    }

    #[test]
    fn installs_with_the_package_manager_found() {
        let only = |name: &'static str| move |program: &str| program == name;
        assert_eq!(
            install_command_for("hwdata", only("pacman")).as_deref(),
            Some("sudo pacman -S --needed hwdata")
        );
        assert_eq!(
            install_command_for("hwdata", only("apt-get")).as_deref(),
            Some("sudo apt-get install hwdata")
        );
        assert_eq!(
            install_command_for("hwdata", only("dnf")).as_deref(),
            Some("sudo dnf install hwdata")
        );
        assert_eq!(
            install_command_for("hwdata", only("zypper")).as_deref(),
            Some("sudo zypper install hwdata")
        );
        assert_eq!(install_command_for("hwdata", |_| false), None);
    }
}
