//! `yaedock`, the one YaeDock program for the PC. It runs the daemon
//! (`yaedock daemon`), pairs phones, manages paired devices and the background
//! service, and has commands for checking the daemon's building blocks. Run
//! without a command in a terminal, it shows a menu. Started under the name
//! `yaedockd` (for example through a symlink), it accepts `yaedockd run`.

mod check;
mod client;
mod clipboard;
mod firewall;
mod install;
mod obs;
mod pair;
mod service;
mod tui;

use std::io::IsTerminal;
use std::net::{IpAddr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::bail;
use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;
use yaedock_core::devices::DeviceStore;
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, mangohud, paths, sampler};
use yaedock_proto::ServerMessage;
use yaedock_sys::brightness::{BacklightBackend, BrightnessBackend};
use yaedock_sys::volume;

#[derive(Parser)]
#[command(
    version,
    about = "Pair phones with this PC and manage the YaeDock daemon",
    after_help = "Run `yaedock` without a command in a terminal for an interactive menu."
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// Connect to a daemon like the phone would and print every message
    Client {
        /// Daemon address
        #[arg(default_value = "wss://127.0.0.1:9481")]
        url: String,
        /// SHA-256 fingerprint of the daemon's certificate; defaults to this
        /// machine's own daemon
        #[arg(long)]
        fingerprint: Option<String>,
        /// One-time pairing code from `yaedock pair`
        #[arg(long)]
        code: Option<String>,
        /// Token received when pairing
        #[arg(long)]
        token: Option<String>,
        /// Device id to identify as
        #[arg(long, default_value = "yaedock-client")]
        device_id: String,
        /// A JSON message to send after the welcome; may be repeated
        #[arg(short, long)]
        send: Vec<String>,
        /// Stop after this many messages
        #[arg(short = 'n', long)]
        count: Option<u64>,
        /// Write the mirrored screen's video to this file, as H.264; send
        /// `{"type":"start_screen"}` to get any
        #[arg(long)]
        video: Option<std::path::PathBuf>,
    },
    /// Pair a new phone: show a QR code and wait for the phone to scan it
    Pair {
        /// Port the daemon listens on
        #[arg(long, default_value_t = DEFAULT_PORT)]
        port: u16,
        /// Address the phone should connect to; defaults to this PC's LAN address
        #[arg(long)]
        host: Option<IpAddr>,
    },
    /// Run the daemon in this terminal (the background service runs this too)
    Daemon {
        /// Address to listen on
        #[arg(long, default_value = "0.0.0.0:9481")]
        listen: SocketAddr,
    },
    /// Install this program for your user in ~/.local/bin
    #[command(long_flag = "install")]
    Install {
        /// Also run the daemon in the background and start it at login, without asking
        #[arg(short, long)]
        yes: bool,
    },
    /// Update this program (and the background daemon) to the newest release on GitHub
    Update {
        /// Only say whether there is a newer version
        #[arg(long)]
        check: bool,
        /// Update without asking
        #[arg(short, long)]
        yes: bool,
    },
    /// Remove the installed program and the background service
    #[command(long_flag = "uninstall")]
    Uninstall {
        /// Also delete paired phones and the certificate
        #[arg(long)]
        purge: bool,
    },
    /// Open the setup guide: check this PC, install the daemon, pair a phone
    Setup,
    /// Show whether the daemon is running and which phones are connected
    Status,
    /// Show game frame rates on the phone, through MangoHud: on, off, show or hide its overlay on the PC, or (no argument) the current state
    Fps {
        #[arg(value_parser = ["on", "off", "show", "hide"])]
        action: Option<String>,
    },
    /// Record the screen from the phone, through OBS Studio: on, off, or (no argument) the current state
    Obs {
        #[arg(value_parser = ["on", "off"])]
        action: Option<String>,
    },
    /// Check which YaeDock features work on this PC, and how to fix the rest
    #[command(alias = "doctor")]
    Check {
        /// Print the results as JSON, as the phone receives them
        #[arg(long)]
        json: bool,
    },
    /// Run the daemon in the background as a systemd user service
    #[command(subcommand)]
    Service(ServiceCommand),
    /// Shortcut buttons on the phone that run commands on this PC; lists them without a subcommand
    Shortcuts {
        #[command(subcommand)]
        command: Option<ShortcutCommand>,
    },
    /// List paired phones
    Devices,
    /// Forget a paired phone
    Unpair {
        /// Device id, as shown by `yaedock devices`
        id: String,
    },
    /// Print a `stats` message every tick, as the phone would receive it
    Stats {
        /// Stop after this many messages
        #[arg(short = 'n', long)]
        count: Option<u64>,
    },
    /// Show display brightness, or set it
    Brightness {
        /// New brightness in percent, 0 to 100
        #[arg(value_parser = clap::value_parser!(u8).range(0..=100))]
        percent: Option<u8>,
        /// Display id; needed only when there is more than one display
        #[arg(short, long)]
        display: Option<String>,
    },
    /// Show the default output's volume, or change it
    Volume {
        /// New volume in percent, 0 to 150
        #[arg(value_parser = clap::value_parser!(u8).range(0..=volume::MAX_PERCENT as i64))]
        percent: Option<u8>,
        /// Mute the output
        #[arg(long, conflicts_with = "unmute")]
        mute: bool,
        /// Unmute the output
        #[arg(long)]
        unmute: bool,
    },
}

#[derive(Subcommand)]
enum ShortcutCommand {
    /// Add a button, or change the command of one with the same name
    Add {
        /// The button's label on the phone
        name: String,
        /// The command to run, as you would type it in a terminal (quote it)
        command: String,
    },
    /// Remove a button
    Remove { name: String },
}

#[derive(Subcommand)]
enum ServiceCommand {
    /// Install the systemd user service (done automatically by start and enable)
    Install,
    /// Stop the service and remove it
    Uninstall,
    /// Start the daemon in the background
    Start,
    /// Stop the background daemon
    Stop,
    /// Restart the background daemon, for example after updating it
    Restart,
    /// Start the daemon now and every time you log in
    Enable,
    /// Stop starting the daemon at login
    Disable,
    /// Show whether the service is installed, running and enabled
    Status,
    /// Follow the daemon's log
    Logs,
}

/// The command line when started as `yaedockd`, the daemon's traditional name.
#[derive(Parser)]
#[command(
    name = "yaedockd",
    version,
    about = "The YaeDock daemon",
    arg_required_else_help = true
)]
struct DaemonCli {
    #[command(subcommand)]
    command: DaemonCommand,
}

#[derive(Subcommand)]
enum DaemonCommand {
    /// Run the daemon
    Run {
        /// Address to listen on
        #[arg(long, default_value = "0.0.0.0:9481")]
        listen: SocketAddr,
    },
}

fn init_logging(default: &str) {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(default)),
        )
        .with_writer(std::io::stderr)
        .with_ansi(std::io::stderr().is_terminal())
        .init();
}

/// Whether this program was started under the name `yaedockd`.
fn started_as_daemon() -> bool {
    std::env::args_os()
        .next()
        .and_then(|arg0| {
            std::path::Path::new(&arg0)
                .file_name()
                .map(|n| n.to_owned())
        })
        .is_some_and(|name| name == "yaedockd")
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    if started_as_daemon() {
        let DaemonCommand::Run { listen } = DaemonCli::parse().command;
        init_logging("info");
        return yaedockd::run(listen).await;
    }
    let cli = Cli::parse();
    // The daemon logs what it does; the other commands only report problems.
    init_logging(if matches!(cli.command, Some(Command::Daemon { .. })) {
        "info"
    } else {
        "warn"
    });

    let Some(command) = cli.command else {
        // The menu needs a real terminal; in a script or pipe, explain instead.
        if std::io::stdin().is_terminal() && std::io::stdout().is_terminal() {
            return tui::run();
        }
        use clap::CommandFactory;
        Cli::command().print_help()?;
        return Ok(());
    };
    match command {
        Command::Daemon { listen } => yaedockd::run(listen).await,
        Command::Client {
            url,
            fingerprint,
            code,
            token,
            device_id,
            send,
            count,
            video,
        } => {
            let fingerprint = match fingerprint {
                Some(fingerprint) => fingerprint,
                None => Identity::load(&paths::config_dir()?)?.fingerprint(),
            };
            let credentials = client::Credentials {
                device_id,
                code,
                token,
            };
            client::run(
                &url,
                &fingerprint,
                &credentials,
                &send,
                count,
                video.as_deref(),
            )
            .await
        }
        Command::Pair { port, host } => pair::run(port, host).await,
        Command::Install { yes } => {
            if install::install(yes)? {
                return tui::run();
            }
            Ok(())
        }
        Command::Update { check, yes } => update(check, yes),
        Command::Uninstall { purge } => install::uninstall(purge),
        Command::Setup => tui::run_setup(),
        Command::Status => status().await,
        Command::Check { json: false } => check::print(),
        Command::Check { json: true } => check::print_json(),
        Command::Fps { action } => fps(action.as_deref()),
        Command::Obs { action } => obs_command(action.as_deref()),
        Command::Service(command) => service_command(command).await,
        Command::Shortcuts { command } => shortcuts_command(command),
        Command::Devices => list_devices(),
        Command::Unpair { id } => {
            if !DeviceStore::open()?.remove(&id)? {
                bail!("no paired device with id {id}");
            }
            println!("Unpaired {id}.");
            Ok(())
        }
        Command::Stats { count } => print_stats(count).await,
        Command::Brightness { percent, display } => brightness(percent, display),
        Command::Volume {
            percent,
            mute,
            unmute,
        } => set_volume(percent, (mute || unmute).then_some(mute)),
    }
}

async fn service_command(command: ServiceCommand) -> anyhow::Result<()> {
    let verb = match command {
        ServiceCommand::Install => {
            println!("{}", service::install()?);
            return Ok(());
        }
        ServiceCommand::Uninstall => {
            println!("{}", service::uninstall()?);
            return Ok(());
        }
        ServiceCommand::Logs => return service::logs(),
        ServiceCommand::Status => {
            let state = service::state()?;
            let yes_no = |b: bool| if b { "yes" } else { "no" };
            println!("Installed:        {}", yes_no(state.installed));
            println!("Running:          {}", yes_no(state.running));
            println!("Starts at login:  {}", yes_no(state.enabled));
            return Ok(());
        }
        ServiceCommand::Start => "start",
        ServiceCommand::Stop => "stop",
        ServiceCommand::Restart => "restart",
        ServiceCommand::Enable => "enable",
        ServiceCommand::Disable => "disable",
    };
    service::control(verb)?;
    let state = service::state()?;
    println!(
        "The daemon is {}{}.",
        if state.running { "running" } else { "stopped" },
        if state.enabled {
            " and starts at login"
        } else {
            ""
        },
    );
    Ok(())
}

fn fps(action: Option<&str>) -> anyhow::Result<()> {
    match action {
        Some("on") => {
            let path = mangohud::enable()?;
            println!(
                "Game frame rates are on: MangoHud now logs for YaeDock ({}).",
                path.display()
            );
            println!(
                "Start games with MangoHud, for example the Steam launch option `mangohud %command%`."
            );
        }
        Some(action @ ("show" | "hide")) => {
            let visible = action == "show";
            mangohud::set_overlay_visible(visible)?;
            println!(
                "The MangoHud overlay is now {}. Running games change straight away.",
                if visible { "shown" } else { "hidden" }
            );
        }
        Some(_) => {
            let path = mangohud::disable()?;
            println!(
                "Game frame rates are off; removed YaeDock's lines from {}.",
                path.display()
            );
        }
        None => match mangohud::state() {
            mangohud::State::On if mangohud::overlay_visible() == Some(false) => {
                println!("Game frame rates are on, and the MangoHud overlay is hidden.")
            }
            mangohud::State::On => println!("Game frame rates are on."),
            mangohud::State::Off => {
                println!("Game frame rates are off. Turn them on with `yaedock fps on`.")
            }
            mangohud::State::NotInstalled => {
                println!(
                    "MangoHud is not installed; install it from your distribution to see game frame rates."
                )
            }
        },
    }
    if mangohud::state() == mangohud::State::On && mangohud::overlay_hidden() {
        println!(
            "Note: your MangoHud config hides the overlay (no_display), and MangoHud only logs while it is shown."
        );
    }
    Ok(())
}

fn shortcuts_command(command: Option<ShortcutCommand>) -> anyhow::Result<()> {
    use yaedock_core::shortcuts;
    match command {
        Some(ShortcutCommand::Add { name, command }) => {
            shortcuts::add(&name, &command)?;
            println!(
                "Added \"{}\"; it shows on the phone within a second.",
                name.trim()
            );
        }
        Some(ShortcutCommand::Remove { name }) => {
            if shortcuts::remove(&name)? {
                println!("Removed \"{name}\".");
            } else {
                println!("There is no shortcut called \"{name}\".");
            }
        }
        None => {
            let entries = shortcuts::load()?;
            if entries.is_empty() {
                println!("No shortcuts yet. Add one, for example:");
                println!("  yaedock shortcuts add \"Steam\" \"steam\"");
            }
            for entry in &entries {
                println!("{:<24} {}", entry.name, entry.command);
            }
            println!("\nStored in {}.", shortcuts::path()?.display());
        }
    }
    Ok(())
}

fn obs_command(action: Option<&str>) -> anyhow::Result<()> {
    match action {
        Some("on") => {
            let path = obs::enable()?;
            println!(
                "Screen recording is on: OBS Studio's WebSocket server is turned on ({}).",
                path.display()
            );
            println!(
                "The daemon reaches it on this PC only, so the port needs no firewall rule. \
                 Make sure your OBS scene has a screen capture source."
            );
        }
        Some(_) => {
            let path = obs::disable()?;
            println!(
                "Screen recording is off; OBS Studio's WebSocket server is turned off ({}).",
                path.display()
            );
        }
        None => match obs::state() {
            obs::State::On => println!("Screen recording is on."),
            obs::State::Off => println!(
                "Screen recording is off. Close OBS Studio, then turn it on with `yaedock obs on`."
            ),
            obs::State::NotInstalled => println!(
                "OBS Studio is not installed; install it from your distribution to record the screen."
            ),
        },
    }
    Ok(())
}

async fn status() -> anyhow::Result<()> {
    let Some(status) = yaedock_core::control::status().await? else {
        println!("The daemon is not running. Start it with `yaedock service start`.");
        return Ok(());
    };
    let now = yaedock_core::pairing::now();
    println!(
        "Daemon {} running (pid {}) on wss://{}, up {}",
        status.version,
        status.pid,
        status.listen,
        ago(now.saturating_sub(status.started_at)),
    );
    println!("Fingerprint {}", status.fingerprint);
    if status.phones.is_empty() {
        println!("No phones connected.");
    }
    for phone in status.phones {
        println!(
            "{} ({}) connected from {} for {}",
            phone.name,
            phone.device_id,
            phone.address,
            ago(now.saturating_sub(phone.connected_at)),
        );
    }
    Ok(())
}

/// Formats a duration in seconds as `3d 4h`, `4h 12m` or `12m 5s`.
fn ago(seconds: u64) -> String {
    let (d, h, m, s) = (
        seconds / 86_400,
        seconds % 86_400 / 3_600,
        seconds % 3_600 / 60,
        seconds % 60,
    );
    match (d, h) {
        (0, 0) => format!("{m}m {s}s"),
        (0, _) => format!("{h}h {m}m"),
        _ => format!("{d}d {h}h"),
    }
}

fn list_devices() -> anyhow::Result<()> {
    let devices = DeviceStore::open()?.list()?;
    if devices.is_empty() {
        println!("No paired phones. Pair one with `yaedock pair`.");
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    for device in devices {
        let days = now.saturating_sub(device.paired_at) / 86_400;
        println!("{}\t{}\tpaired {days} days ago", device.id, device.name);
    }
    Ok(())
}

async fn print_stats(count: Option<u64>) -> anyhow::Result<()> {
    let sources = sampler::default_sources().sources;
    let mut snapshots = sampler::spawn(sources, sampler::DEFAULT_TICK, sampler::always_watched());
    let mut printed = 0;
    while count.is_none_or(|count| printed < count) {
        snapshots.changed().await?;
        let snapshot = snapshots.borrow_and_update().as_ref().clone();
        println!(
            "{}",
            serde_json::to_string(&ServerMessage::Stats(snapshot))?
        );
        printed += 1;
    }
    Ok(())
}

fn brightness(percent: Option<u8>, display: Option<String>) -> anyhow::Result<()> {
    let backend = BacklightBackend::new();
    if let Some(percent) = percent {
        let display = match display {
            Some(display) => display,
            None => match backend.displays()?.as_slice() {
                [only] => only.id.clone(),
                [] => bail!("no display with adjustable brightness found"),
                _ => bail!("more than one display; choose one with --display"),
            },
        };
        backend.set(&display, percent)?;
    }
    for display in backend.displays()? {
        println!("{}\t{}\t{}%", display.id, display.name, display.percent);
    }
    Ok(())
}

fn set_volume(percent: Option<u8>, muted: Option<bool>) -> anyhow::Result<()> {
    let Some(backend) = volume::detect() else {
        bail!("no volume control found; is PipeWire (wpctl) or PulseAudio (pactl) running?");
    };
    backend.set(percent, muted)?;
    let state = backend.get()?;
    let muted = if state.muted { " (muted)" } else { "" };
    println!("{}%{muted}", state.percent);
    Ok(())
}

/// `yaedock update`: installs the newest release over the program the
/// background service runs (or this one, without a service), then restarts
/// the daemon on it.
fn update(check_only: bool, yes: bool) -> anyhow::Result<()> {
    use std::io::Write;
    use yaedock_core::update::{self, Stage};

    let current = update::current();
    println!("This is yaedock {current}. Checking GitHub for a newer one…");
    let Some(release) = update::newest()? else {
        println!("No releases are published yet.");
        return Ok(());
    };
    if release.version == current {
        println!("✓ Up to date: {current} is the newest release.");
        return Ok(());
    }
    if release.version < current {
        println!(
            "✓ Up to date: this is newer than the newest release, {}.",
            release.version
        );
        return Ok(());
    }
    println!(
        "\nVersion {} is available: {}",
        release.version, release.page
    );
    for line in release.notes.lines().take(20) {
        println!("  {line}");
    }
    if check_only {
        println!("\nRun `yaedock update` to install it.");
        return Ok(());
    }
    let program = match service::program() {
        Some(program) => program,
        None => std::env::current_exe()?,
    };
    let target = update::target(&program)?;
    println!();
    if !yes
        && !install::ask(&format!(
            "Update {} to {}?",
            target.display(),
            release.version
        ))
    {
        if std::io::stdin().is_terminal() {
            println!("Not updated.");
        } else {
            println!("Not updated; run `yaedock update -y` to update without asking.");
        }
        return Ok(());
    }
    // A terminal shows one line that counts up; a log gets a line per step.
    let terminal = std::io::stdout().is_terminal();
    let mut last = None;
    update::install(&release, &target, &mut |stage| {
        let text = match stage {
            Stage::Downloading(Some(percent)) if terminal => format!("Downloading… {percent}%"),
            Stage::Downloading(_) => "Downloading…".to_string(),
            Stage::Verifying => "Checking the download…".to_string(),
            Stage::Installing => "Installing…".to_string(),
        };
        if last.as_ref() != Some(&text) {
            if terminal {
                print!("\r\x1b[2K  {text}");
            } else {
                println!("  {text}");
            }
            let _ = std::io::stdout().flush();
            last = Some(text);
        }
    })
    .inspect_err(|_| {
        if terminal {
            println!();
        }
    })?;
    if terminal {
        print!("\r\x1b[2K");
    }
    println!(
        "✓ Updated yaedock to {} in {}",
        release.version,
        target.display()
    );
    if service::user_unit_path().is_ok_and(|p| p.exists())
        && service::state().is_ok_and(|s| s.running)
    {
        service::control("restart")?;
        println!("✓ Restarted the daemon with the new version; phones reconnect by themselves");
    }
    Ok(())
}
