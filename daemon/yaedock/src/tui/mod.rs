//! The menu shown when `yaedock` runs in a terminal without a command: a live
//! view of the daemon and this PC, pairing, paired phones, background service
//! control, logs and help.

mod local;
mod screens;
mod theme;
mod wizard;

use std::io::stdout;
use std::net::IpAddr;
use std::process::Command;
use std::time::{Duration, Instant};

use ratatui::DefaultTerminal;
use ratatui::crossterm::event::{
    self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEvent, KeyEventKind,
    KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
};
use ratatui::crossterm::execute;
use ratatui::crossterm::terminal::EnterAlternateScreen;
use ratatui::layout::{Position, Rect};
use ratatui::widgets::TableState;
use yaedock_core::control::{self, DaemonStatus};
use yaedock_core::devices::{Device, DeviceStore};
use yaedock_core::pairing::{self, Pairing, Status as PairingStatus};
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, paths};

use self::local::LocalStats;
use self::wizard::WizardView;
use crate::check::{self, Check, Switch};
use crate::clipboard::{self, Copied};
use crate::service::{self, ServiceState};
use crate::{obs, pair};

const REFRESH: Duration = Duration::from_secs(1);
const FRAME: Duration = Duration::from_millis(120);
const TOAST_FOR: Duration = Duration::from_secs(4);

/// Opens the menu, or the setup guide on a PC that is new to YaeDock.
pub fn run() -> anyhow::Result<()> {
    start(false)
}

/// Opens the setup guide (`yaedock setup`), even on a PC already set up.
pub fn run_setup() -> anyhow::Result<()> {
    start(true)
}

fn start(setup: bool) -> anyhow::Result<()> {
    ratatui::run(|terminal| {
        // Mouse clicks and scrolling work too; switch them off however we leave.
        execute!(stdout(), EnableMouseCapture)?;
        let mut app = App::new();
        // Until the daemon is installed and a phone is paired, YaeDock is not set
        // up yet, and the setup has to be finished before the menu opens.
        let not_installed = service::state().is_ok_and(|s| !s.installed)
            && control::status_blocking().ok().flatten().is_none();
        let no_phone = DeviceStore::open()
            .and_then(|s| s.list())
            .is_ok_and(|d| d.is_empty());
        let unfinished = not_installed || no_phone;
        if setup || unfinished {
            app.screen = Screen::Wizard(WizardView::new(unfinished));
        }
        let result = app.run(terminal);
        execute!(stdout(), DisableMouseCapture)?;
        result
    })
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum MenuItem {
    Pair,
    Devices,
    StartStop,
    StartAtLogin,
    GameFps,
    GameOverlay,
    Recording,
    Check,
    Update,
    Logs,
    Help,
    Quit,
}

const MENU: [MenuItem; 12] = [
    MenuItem::Pair,
    MenuItem::Devices,
    MenuItem::StartStop,
    MenuItem::StartAtLogin,
    MenuItem::GameFps,
    MenuItem::GameOverlay,
    MenuItem::Recording,
    MenuItem::Check,
    MenuItem::Update,
    MenuItem::Logs,
    MenuItem::Help,
    MenuItem::Quit,
];

enum Screen {
    Wizard(WizardView),
    Home,
    Pair(PairView),
    Devices(DevicesView),
    Logs(LogsView),
    Check(Vec<Check>),
    Help,
}

struct PairView {
    pairing: Pairing,
    qr: String,
    host: IpAddr,
    port: u16,
    fingerprint: String,
    /// Set once the pairing has finished, successfully or not.
    outcome: Option<Result<String, String>>,
}

struct DevicesView {
    devices: Vec<Device>,
    table: TableState,
    /// The row waiting for the user to confirm unpairing.
    confirm: Option<usize>,
}

struct LogsView {
    lines: Vec<String>,
    /// Lines scrolled up from the end.
    scroll: usize,
}

struct Toast {
    text: String,
    error: bool,
    shown: Instant,
}

struct App {
    screen: Screen,
    selected: usize,
    daemon: Option<DaemonStatus>,
    service: Option<ServiceState>,
    paired_count: usize,
    /// Whether MangoHud is set up to send game frame rates.
    game_fps: bool,
    /// Whether MangoHud's overlay shows on the PC, while game frame rates are on.
    game_overlay: Option<bool>,
    /// Whether OBS Studio is set up so the phone can record the screen.
    recording: obs::State,
    local: LocalStats,
    refreshed: Option<Instant>,
    started: Instant,
    toast: Option<Toast>,
    /// Where the menu rows were drawn, so mouse clicks can find them.
    menu_rows: Rect,
    /// A command needing root that the user chose to run; the main loop hands
    /// the terminal over to it.
    pending_command: Option<String>,
    /// The user chose to check for updates; like `pending_command`, it runs
    /// in the terminal, where it can ask before installing.
    pending_update: bool,
    quit: bool,
}

impl App {
    fn new() -> Self {
        Self {
            screen: Screen::Home,
            selected: 0,
            daemon: None,
            service: None,
            paired_count: 0,
            game_fps: false,
            game_overlay: None,
            recording: obs::State::NotInstalled,
            local: LocalStats::new(),
            refreshed: None,
            started: Instant::now(),
            toast: None,
            menu_rows: Rect::default(),
            pending_command: None,
            pending_update: false,
            quit: false,
        }
    }

    /// Animation frame number, advancing every [`FRAME`].
    fn frame(&self) -> u64 {
        (self.started.elapsed().as_millis() / FRAME.as_millis()) as u64
    }

    fn run(mut self, terminal: &mut DefaultTerminal) -> anyhow::Result<()> {
        while !self.quit {
            if self.refreshed.is_none_or(|at| at.elapsed() >= REFRESH) {
                self.refresh();
            }
            if self
                .toast
                .as_ref()
                .is_some_and(|t| t.shown.elapsed() >= TOAST_FOR)
            {
                self.toast = None;
            }
            // Background work in the wizard (the check, installing) reports in here.
            if let Screen::Wizard(view) = &mut self.screen
                && view.poll()
            {
                self.refresh();
            }
            terminal.draw(|frame| screens::draw(&mut self, frame))?;
            if event::poll(FRAME)? {
                match event::read()? {
                    Event::Key(key) if key.kind == KeyEventKind::Press => self.on_key(key),
                    Event::Mouse(mouse) => self.on_mouse(mouse),
                    _ => {}
                }
            }
            if let Some(command) = self.pending_command.take() {
                self.run_privileged(terminal, &command)?;
            }
            if std::mem::take(&mut self.pending_update) {
                self.run_update(terminal)?;
            }
        }
        self.leave_pairing();
        Ok(())
    }

    /// Steps out of the full-screen view so `command` can ask for the password
    /// in the terminal, then comes back and checks the PC again.
    fn run_privileged(
        &mut self,
        terminal: &mut DefaultTerminal,
        command: &str,
    ) -> anyhow::Result<()> {
        execute!(stdout(), DisableMouseCapture)?;
        ratatui::restore();
        println!("\nYaeDock needs administrator rights for this command:\n\n    {command}\n");
        let ok = Command::new("sh")
            .arg("-c")
            .arg(command)
            .status()
            .is_ok_and(|s| s.success());
        println!(
            "\n{}  Press Enter to go back to YaeDock.",
            if ok {
                "✓ Done."
            } else {
                "✗ The command did not succeed."
            }
        );
        let _ = std::io::stdin().read_line(&mut String::new());
        ratatui::crossterm::terminal::enable_raw_mode()?;
        execute!(stdout(), EnterAlternateScreen, EnableMouseCapture)?;
        terminal.clear()?;
        if let Screen::Wizard(view) = &mut self.screen {
            view.recheck();
        }
        if ok {
            self.toast("Done. Checking this PC again", false);
        } else {
            self.toast("The command did not succeed; nothing was changed", true);
        }
        Ok(())
    }

    /// Steps out of the full-screen view to run `yaedock update`, which shows
    /// what is new and asks before installing, then comes back.
    fn run_update(&mut self, terminal: &mut DefaultTerminal) -> anyhow::Result<()> {
        execute!(stdout(), DisableMouseCapture)?;
        ratatui::restore();
        println!();
        let result = crate::update(false, false);
        if let Err(err) = &result {
            println!("✗ {err:#}");
        }
        println!("\nPress Enter to go back to YaeDock.");
        let _ = std::io::stdin().read_line(&mut String::new());
        ratatui::crossterm::terminal::enable_raw_mode()?;
        execute!(stdout(), EnterAlternateScreen, EnableMouseCapture)?;
        terminal.clear()?;
        self.refresh();
        Ok(())
    }

    fn refresh(&mut self) {
        self.daemon = control::status_blocking().ok().flatten();
        self.service = service::state().ok();
        self.paired_count = DeviceStore::open()
            .and_then(|s| s.list())
            .map_or(0, |d| d.len());
        self.local.sample();
        self.game_fps = yaedock_core::mangohud::state() == yaedock_core::mangohud::State::On;
        self.game_overlay = yaedock_core::mangohud::overlay_visible();
        self.recording = obs::state();
        self.refreshed = Some(Instant::now());
        if let Some(view) = self.active_pairing()
            && view.outcome.is_none()
        {
            view.outcome = match pairing::load() {
                Ok(Some(p)) if p.code == view.pairing.code => match p.status {
                    PairingStatus::Paired { device } => Some(Ok(device)),
                    PairingStatus::Locked => Some(Err("Too many wrong codes were tried.".into())),
                    PairingStatus::Waiting if p.is_expired() => {
                        Some(Err("The code expired.".into()))
                    }
                    PairingStatus::Waiting => None,
                },
                _ => Some(Err("Another pairing was started.".into())),
            };
            if view.outcome.is_some() {
                let _ = pairing::remove();
            }
        }
    }

    fn toast(&mut self, text: impl Into<String>, error: bool) {
        self.toast = Some(Toast {
            text: text.into(),
            error,
            shown: Instant::now(),
        });
    }

    fn on_key(&mut self, key: KeyEvent) {
        if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
            self.quit = true;
            return;
        }
        let back = matches!(
            key.code,
            KeyCode::Esc | KeyCode::Char('q') | KeyCode::Backspace | KeyCode::Left
        );
        match &mut self.screen {
            Screen::Home => match key.code {
                KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
                KeyCode::Down | KeyCode::Char('j') => {
                    self.selected = (self.selected + 1).min(MENU.len() - 1)
                }
                KeyCode::Enter | KeyCode::Char(' ') | KeyCode::Right => {
                    self.choose(MENU[self.selected])
                }
                KeyCode::Char(c @ '1'..='9') => {
                    self.selected = c as usize - '1' as usize;
                    self.choose(MENU[self.selected]);
                }
                KeyCode::Esc | KeyCode::Char('q') => self.quit = true,
                _ => {}
            },
            Screen::Pair(_) => {
                if back || key.code == KeyCode::Enter {
                    self.leave_pairing();
                    self.screen = Screen::Home;
                }
            }
            Screen::Devices(view) => match (view.confirm, key.code) {
                (Some(row), KeyCode::Char('y') | KeyCode::Enter) => {
                    let device = view.devices[row].clone();
                    match DeviceStore::open().and_then(|s| s.remove(&device.id)) {
                        Ok(_) => self.toast(format!("Unpaired {}", device.name), false),
                        Err(err) => self.toast(format!("Could not unpair: {err:#}"), true),
                    }
                    self.screen = Screen::Devices(devices_view());
                    self.refresh();
                }
                (Some(_), _) => view.confirm = None,
                (None, KeyCode::Up | KeyCode::Char('k')) => view.table.select_previous(),
                (None, KeyCode::Down | KeyCode::Char('j')) => {
                    let last = view.devices.len().saturating_sub(1);
                    view.table
                        .select(Some(view.table.selected().map_or(0, |i| (i + 1).min(last))));
                }
                (None, KeyCode::Char('d') | KeyCode::Delete) => {
                    view.confirm = view.table.selected().filter(|&i| i < view.devices.len());
                }
                _ if back => self.screen = Screen::Home,
                _ => {}
            },
            Screen::Logs(view) => match key.code {
                KeyCode::Up | KeyCode::Char('k') => view.scroll_by(1),
                KeyCode::Down | KeyCode::Char('j') => view.scroll_by(-1),
                KeyCode::PageUp => view.scroll_by(20),
                KeyCode::PageDown => view.scroll_by(-20),
                KeyCode::Home | KeyCode::Char('g') => view.scroll = view.lines.len(),
                KeyCode::End | KeyCode::Char('G') => view.scroll = 0,
                KeyCode::Char('r') => self.screen = Screen::Logs(logs_view()),
                _ if back => self.screen = Screen::Home,
                _ => {}
            },
            Screen::Wizard(_) => self.wizard_key(key),
            Screen::Check(_) => {
                if self.check_fix_key(key.code) {
                    return;
                }
                if key.code == KeyCode::Char('r') {
                    self.screen = Screen::Check(check::run());
                } else if back || key.code == KeyCode::Enter {
                    self.screen = Screen::Home;
                }
            }
            Screen::Help => {
                if back || key.code == KeyCode::Enter {
                    self.screen = Screen::Home;
                }
            }
        }
    }

    fn on_mouse(&mut self, mouse: MouseEvent) {
        let position = Position::new(mouse.column, mouse.row);
        if matches!(self.screen, Screen::Wizard(_)) {
            if mouse.kind == MouseEventKind::Down(MouseButton::Left) {
                self.wizard_click(position);
            }
            return;
        }
        match (&mut self.screen, mouse.kind) {
            (Screen::Home, MouseEventKind::Down(MouseButton::Left))
                if self.menu_rows.contains(position) =>
            {
                let row = (mouse.row - self.menu_rows.y) as usize;
                if row < MENU.len() {
                    self.selected = row;
                    self.choose(MENU[row]);
                }
            }
            (Screen::Home, MouseEventKind::Moved) if self.menu_rows.contains(position) => {
                self.selected = ((mouse.row - self.menu_rows.y) as usize).min(MENU.len() - 1);
            }
            (Screen::Home, MouseEventKind::ScrollUp) => {
                self.selected = self.selected.saturating_sub(1)
            }
            (Screen::Home, MouseEventKind::ScrollDown) => {
                self.selected = (self.selected + 1).min(MENU.len() - 1)
            }
            (Screen::Logs(view), MouseEventKind::ScrollUp) => view.scroll_by(3),
            (Screen::Logs(view), MouseEventKind::ScrollDown) => view.scroll_by(-3),
            (Screen::Devices(view), MouseEventKind::ScrollUp) => view.table.select_previous(),
            (Screen::Devices(view), MouseEventKind::ScrollDown) => {
                let last = view.devices.len().saturating_sub(1);
                view.table
                    .select(Some(view.table.selected().map_or(0, |i| (i + 1).min(last))));
            }
            (
                Screen::Help | Screen::Check(_) | Screen::Pair(_),
                MouseEventKind::Down(MouseButton::Right),
            ) => {
                self.leave_pairing();
                self.screen = Screen::Home;
            }
            _ => {}
        }
    }

    fn choose(&mut self, item: MenuItem) {
        match item {
            MenuItem::Pair => match pair_view() {
                Ok(view) => self.screen = Screen::Pair(view),
                Err(err) => self.toast(format!("Could not start pairing: {err:#}"), true),
            },
            MenuItem::Devices => self.screen = Screen::Devices(devices_view()),
            MenuItem::StartStop => self.start_stop(),
            MenuItem::StartAtLogin => self.toggle_start_at_login(),
            MenuItem::GameFps => self.toggle_game_fps(),
            MenuItem::GameOverlay => self.toggle_game_overlay(),
            MenuItem::Recording => self.toggle_recording(),
            MenuItem::Check => self.screen = Screen::Check(check::run()),
            MenuItem::Update => self.pending_update = true,
            MenuItem::Logs => self.screen = Screen::Logs(logs_view()),
            MenuItem::Help => self.screen = Screen::Help,
            MenuItem::Quit => self.quit = true,
        }
    }

    /// Whether the daemon is running, but not as the background service
    /// (someone ran `yaedock daemon` in a terminal).
    fn started_by_hand(&self) -> bool {
        self.daemon.is_some() && !self.service.is_some_and(|s| s.running)
    }

    fn start_stop(&mut self) {
        if self.started_by_hand() {
            let pid = self.daemon.as_ref().map_or(0, |d| d.pid);
            self.toast(
                format!("The daemon runs in a terminal (pid {pid}); stop it there with Ctrl+C"),
                true,
            );
            return;
        }
        let (verb, done) = if self.daemon.is_some() {
            ("stop", "Daemon stopped")
        } else {
            ("start", "Daemon started in the background")
        };
        match service::control(verb) {
            Ok(()) => self.toast(done, false),
            Err(err) => self.toast(format!("{err:#}"), true),
        }
        self.settle();
    }

    fn toggle_start_at_login(&mut self) {
        let enabled = self.service.is_some_and(|s| s.enabled);
        if !enabled && self.started_by_hand() {
            self.toast("The daemon runs in a terminal; stop it there first", true);
            return;
        }
        let (verb, done) = if enabled {
            ("disable", "The daemon will no longer start at login")
        } else {
            ("enable", "The daemon is running and will start at login")
        };
        match service::control(verb) {
            Ok(()) => self.toast(done, false),
            Err(err) => self.toast(format!("{err:#}"), true),
        }
        self.settle();
    }

    fn toggle_game_fps(&mut self) {
        use yaedock_core::mangohud::{self, State};
        match mangohud::state() {
            State::NotInstalled => {
                self.toast("Install MangoHud first to see game frame rates", true)
            }
            State::On => match mangohud::disable() {
                Ok(_) => {
                    self.game_fps = false;
                    self.toast("Game frame rates are off", false)
                }
                Err(err) => self.toast(format!("{err:#}"), true),
            },
            State::Off => match mangohud::enable() {
                Ok(_) if mangohud::overlay_hidden() => self.toast(
                    "On, but your MangoHud overlay is hidden, so it will not log",
                    true,
                ),
                Ok(_) => self.toast(
                    "On: games started with MangoHud send their frame rate",
                    false,
                ),
                Err(err) => self.toast(format!("{err:#}"), true),
            },
        }
    }

    /// Turns screen recording through OBS Studio on or off, as `yaedock obs` does.
    fn toggle_recording(&mut self) {
        let result = match obs::state() {
            obs::State::NotInstalled => {
                return self.toast("Install OBS Studio first to record the screen", true);
            }
            obs::State::On => obs::disable().map(|_| "Screen recording is off"),
            obs::State::Off => {
                obs::enable().map(|_| "On: make sure your OBS scene has a screen capture source")
            }
        };
        match result {
            Ok(text) => {
                self.recording = obs::state();
                self.toast(text, false)
            }
            Err(err) => self.toast(format!("{err:#}"), true),
        }
    }

    /// The problems on the check screen or the setup guide's check, if one is open.
    fn shown_checks(&self) -> Option<Vec<Check>> {
        match &self.screen {
            Screen::Check(checks) => Some(checks.clone()),
            Screen::Wizard(view) => view.checks.clone(),
            _ => None,
        }
    }

    /// Keys that fix what the check found: a runs the commands that need the
    /// password, c copies them for another terminal, o and f turn on screen
    /// recording and game frame rates. True if the key did something.
    fn check_fix_key(&mut self, code: KeyCode) -> bool {
        let Some(checks) = self.shown_checks() else {
            return false;
        };
        let has = |switch| checks.iter().any(|c| c.switch == Some(switch));
        match code {
            KeyCode::Char('a') => match wizard::fix_command(&checks, false) {
                Some(command) => self.pending_command = Some(command),
                None => return false,
            },
            KeyCode::Char('c') => match wizard::fix_command(&checks, false) {
                Some(command) => match clipboard::copy(&command) {
                    Copied::Done => self.toast(format!("Copied: {command}"), false),
                    Copied::Asked => self.toast(
                        "Asked the terminal to copy it. If nothing was copied, hold Shift and drag to select it",
                        false,
                    ),
                },
                None => return false,
            },
            KeyCode::Char('o') if has(Switch::Recording) => {
                self.toggle_recording();
                self.check_again();
            }
            KeyCode::Char('f') if has(Switch::GameFps) => {
                self.toggle_game_fps();
                self.check_again();
            }
            _ => return false,
        }
        true
    }

    /// Runs the check again after a fix, on whichever screen shows it.
    fn check_again(&mut self) {
        match &mut self.screen {
            Screen::Check(_) => self.screen = Screen::Check(check::run()),
            Screen::Wizard(view) => view.start_checks(),
            _ => {}
        }
    }

    fn toggle_game_overlay(&mut self) {
        use yaedock_core::mangohud;
        let Some(visible) = mangohud::overlay_visible() else {
            return self.toast("Turn on Game FPS first", true);
        };
        match mangohud::set_overlay_visible(!visible) {
            Ok(()) => {
                self.game_overlay = Some(!visible);
                self.toast(
                    if visible {
                        "The overlay is hidden; the phone still gets the frame rate"
                    } else {
                        "The overlay shows on the PC again"
                    },
                    false,
                )
            }
            Err(err) => self.toast(format!("{err:#}"), true),
        }
    }

    /// Gives the daemon a moment to start or stop, then shows its new state.
    fn settle(&mut self) {
        std::thread::sleep(Duration::from_millis(400));
        self.refresh();
    }

    /// The pairing shown right now, on the pairing screen or in the wizard.
    fn active_pairing(&mut self) -> Option<&mut PairView> {
        match &mut self.screen {
            Screen::Pair(view) => Some(view),
            Screen::Wizard(wizard) => wizard.pair.as_mut(),
            _ => None,
        }
    }

    /// Cancels a pairing that is still waiting, so its code stops working.
    fn leave_pairing(&mut self) {
        if let Some(view) = self.active_pairing()
            && view.outcome.is_none()
            && pairing::load()
                .ok()
                .flatten()
                .is_some_and(|p| p.code == view.pairing.code)
        {
            let _ = pairing::remove();
        }
    }
}

impl LogsView {
    fn scroll_by(&mut self, lines: isize) {
        self.scroll = self
            .scroll
            .saturating_add_signed(lines)
            .min(self.lines.len());
    }
}

fn pair_view() -> anyhow::Result<PairView> {
    let fingerprint = Identity::load_or_create(&paths::config_dir()?)?.fingerprint();
    let host = pair::lan_address()
        .ok_or_else(|| anyhow::anyhow!("could not find this PC's LAN address"))?;
    let port = control::status_blocking()
        .ok()
        .flatten()
        .and_then(|d| d.listen.rsplit(':').next()?.parse().ok())
        .unwrap_or(DEFAULT_PORT);
    let pairing = Pairing::new()?;
    pairing::save(&pairing)?;
    let qr = pair::qr_text(&pair::uri(host, port, &fingerprint, &pairing.code))?;
    Ok(PairView {
        pairing,
        qr,
        host,
        port,
        fingerprint,
        outcome: None,
    })
}

fn devices_view() -> DevicesView {
    let devices = DeviceStore::open()
        .and_then(|s| s.list())
        .unwrap_or_default();
    let table = TableState::default().with_selected((!devices.is_empty()).then_some(0));
    DevicesView {
        devices,
        table,
        confirm: None,
    }
}

fn logs_view() -> LogsView {
    let output = Command::new("journalctl")
        .args([
            "--user",
            "-u",
            "yaedockd.service",
            "-n",
            "500",
            "--no-pager",
            "-o",
            "short-iso",
        ])
        .output();
    let lines = match output {
        Ok(out) => String::from_utf8_lossy(&out.stdout)
            .lines()
            .filter(|l| !l.starts_with("-- "))
            .map(str::to_string)
            .collect(),
        Err(err) => vec![format!("Could not run journalctl: {err}")],
    };
    LogsView { lines, scroll: 0 }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    fn render(app: &mut App, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal.draw(|frame| screens::draw(app, frame)).unwrap();
        let buffer = terminal.backend().buffer();
        (0..height)
            .map(|y| {
                (0..width)
                    .map(|x| buffer[(x, y)].symbol().to_string())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    fn running() -> DaemonStatus {
        DaemonStatus {
            version: "0.1.0".into(),
            pid: 42,
            listen: "0.0.0.0:9481".into(),
            fingerprint: "ab".into(),
            started_at: 0,
            phones: vec![control::ConnectedPhone {
                device_id: "p1".into(),
                name: "Pixel".into(),
                address: "192.168.1.30:5000".into(),
                connected_at: 0,
            }],
        }
    }

    #[test]
    fn home_adapts_to_the_daemon_and_terminal_size() {
        let mut app = App::new();
        for (width, height) in [(120, 40), (80, 24), (60, 20)] {
            let screen = render(&mut app, width, height);
            assert!(
                screen.contains("Pair a phone"),
                "{width}x{height}:\n{screen}"
            );
            assert!(
                screen.contains("Not running"),
                "{width}x{height}:\n{screen}"
            );
        }
        app.daemon = Some(running());
        let screen = render(&mut app, 120, 40);
        assert!(screen.contains("Running"));
        assert!(screen.contains("Pixel"));
        assert!(screen.contains("Stop the daemon"));
    }

    #[test]
    fn wizard_pages_draw() {
        let mut app = App::new();
        let checks = vec![
            Check {
                area: "Volume",
                outcome: check::Outcome::Ok,
                summary: "PipeWire".into(),
                fix: None,
                command: None,
                switch: None,
            },
            Check {
                area: "Firewall",
                outcome: check::Outcome::Warn,
                summary: "ufw is on".into(),
                fix: Some("sudo ufw allow 9481/tcp".into()),
                command: None,
                switch: None,
            },
        ];
        app.screen = Screen::Wizard(WizardView::with_checks(checks));
        for (width, height) in [(120, 40), (80, 30)] {
            let screen = render(&mut app, width, height);
            assert!(
                screen.contains("YaeDock setup"),
                "{width}x{height}:\n{screen}"
            );
            assert!(
                screen.contains("Almost everything works"),
                "{width}x{height}:\n{screen}"
            );
            assert!(
                screen.contains("sudo ufw allow 9481/tcp"),
                "{width}x{height}:\n{screen}"
            );
            assert!(screen.contains("Continue"), "{width}x{height}:\n{screen}");
        }
        for (page, text) in [
            (wizard::Page::Install, "Install now"),
            (wizard::Page::Pair, "Show the QR code"),
            (wizard::Page::Done, "all set"),
        ] {
            if let Screen::Wizard(view) = &mut app.screen {
                view.page = page;
            }
            let screen = render(&mut app, 100, 32);
            assert!(screen.contains(text), "{page:?}:\n{screen}");
        }

        use wizard::TaskState::{Done, Running, Waiting};
        if let Screen::Wizard(view) = &mut app.screen {
            view.with_install_progress(vec![Done, Running, Waiting, Waiting], None);
        }
        let screen = render(&mut app, 100, 32);
        assert!(screen.contains("Writing the service file"), "{screen}");
        assert!(screen.contains("Installing"), "{screen}");
    }

    #[test]
    fn wizard_shows_a_key_for_each_fix() {
        let warning = |area, command: Option<&str>, switch| Check {
            area,
            outcome: check::Outcome::Warn,
            summary: format!("{area} is not set up"),
            fix: None,
            command: command.map(Into::into),
            switch,
        };
        let checks = vec![
            warning("Recording", None, Some(Switch::Recording)),
            warning("Discovery", Some("sudo ufw allow 5353/udp"), None),
            warning("Game FPS", None, Some(Switch::GameFps)),
        ];
        let mut app = App::new();
        app.screen = Screen::Wizard(WizardView::with_checks(checks.clone()));
        let screen = render(&mut app, 110, 40);
        assert!(screen.contains("A few things to know"), "{screen}");
        assert!(screen.contains("a allow it now"), "{screen}");
        assert!(screen.contains("c copy the command"), "{screen}");
        assert!(screen.contains("o turn it on"), "{screen}");
        assert!(screen.contains("f turn it on"), "{screen}");

        app.screen = Screen::Check(checks);
        let screen = render(&mut app, 110, 40);
        assert!(screen.contains("a allow it now"), "{screen}");
        assert!(screen.contains("o turn it on"), "{screen}");
    }

    #[test]
    fn one_password_prompt_runs_every_fix() {
        let check = |outcome, command: &str| Check {
            area: "Firewall",
            outcome,
            summary: String::new(),
            fix: None,
            command: Some(command.into()),
            switch: None,
        };
        let checks = [
            check(check::Outcome::Fail, "sudo ufw allow 9481/tcp"),
            check(check::Outcome::Warn, "sudo ufw allow 5353/udp"),
            check(check::Outcome::Warn, "sudo ufw allow 5353/udp"),
        ];
        assert_eq!(
            wizard::fix_command(&checks, false).as_deref(),
            Some("sudo ufw allow 9481/tcp && sudo ufw allow 5353/udp")
        );
        assert_eq!(
            wizard::fix_command(&checks, true).as_deref(),
            Some("sudo ufw allow 9481/tcp")
        );
        assert_eq!(wizard::fix_command(&checks[..0], false), None);
    }

    #[test]
    fn wizard_offers_to_open_a_blocked_port() {
        let mut app = App::new();
        let blocked = Check {
            area: "Firewall",
            outcome: check::Outcome::Fail,
            summary: "ufw blocks TCP 9481, so phones cannot connect".into(),
            fix: Some("Allow it: sudo ufw allow 9481/tcp".into()),
            command: Some("sudo ufw allow 9481/tcp".into()),
            switch: None,
        };
        app.screen = Screen::Wizard(WizardView::with_checks(vec![blocked]));
        let screen = render(&mut app, 110, 34);
        assert!(screen.contains("Allow it now"), "{screen}");
        assert!(screen.contains("sudo ufw allow 9481/tcp"), "{screen}");
        assert!(screen.contains("Press c to copy it"), "{screen}");
        assert!(screen.contains("c copy the command"), "{screen}");

        if let Screen::Wizard(view) = &mut app.screen {
            view.page = wizard::Page::Pair;
        }
        let screen = render(&mut app, 110, 34);
        assert!(screen.contains("firewall blocks YaeDock"), "{screen}");
        assert!(screen.contains("Allow it now"), "{screen}");
    }

    #[test]
    fn first_setup_cannot_be_skipped() {
        use ratatui::crossterm::event::KeyEvent;
        let ok = Check {
            area: "Volume",
            outcome: check::Outcome::Ok,
            summary: "PipeWire".into(),
            fix: None,
            command: None,
            switch: None,
        };

        let mut app = App::new();
        let mut view = WizardView::with_checks(vec![ok.clone()]);
        view.mandatory = true;
        app.screen = Screen::Wizard(view);
        for key in [KeyCode::Esc, KeyCode::Char('s'), KeyCode::Right] {
            app.on_key(KeyEvent::from(key));
            assert!(
                matches!(app.screen, Screen::Wizard(_)),
                "{key:?} left the setup"
            );
        }
        assert!(
            app.toast
                .as_ref()
                .is_some_and(|t| t.text.contains("isn't finished"))
        );
        let screen = render(&mut app, 110, 34);
        assert!(!screen.contains("Skip"), "{screen}");

        // Opened later with `yaedock setup`, the wizard can be left.
        app.screen = Screen::Wizard(WizardView::with_checks(vec![ok]));
        app.on_key(KeyEvent::from(KeyCode::Esc));
        assert!(matches!(app.screen, Screen::Home));
    }

    #[test]
    fn every_screen_draws() {
        let mut app = App::new();
        app.screen = Screen::Help;
        assert!(render(&mut app, 100, 32).contains("yaedock service"));
        app.screen = Screen::Logs(LogsView {
            lines: vec!["2026-10-19T10:00:00 shura-pc yaedockd[1]: WARN boom".into()],
            scroll: 0,
        });
        assert!(render(&mut app, 100, 20).contains("boom"));
        app.screen = Screen::Devices(DevicesView {
            devices: Vec::new(),
            table: TableState::default(),
            confirm: None,
        });
        assert!(render(&mut app, 100, 20).contains("No phones are paired"));
    }
}
