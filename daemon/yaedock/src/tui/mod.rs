//! The menu shown when `yaedock` runs in a terminal without a command: a live
//! view of the daemon and this PC, pairing, paired phones, background service
//! control, logs and help.

mod local;
mod screens;
mod theme;

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
use ratatui::layout::{Position, Rect};
use ratatui::widgets::TableState;
use yaedock_core::control::{self, DaemonStatus};
use yaedock_core::devices::{Device, DeviceStore};
use yaedock_core::pairing::{self, Pairing, Status as PairingStatus};
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, paths};

use self::local::LocalStats;
use crate::pair;
use crate::service::{self, ServiceState};

const REFRESH: Duration = Duration::from_secs(1);
const FRAME: Duration = Duration::from_millis(120);
const TOAST_FOR: Duration = Duration::from_secs(4);

pub fn run() -> anyhow::Result<()> {
    ratatui::run(|terminal| {
        // Mouse clicks and scrolling work too; switch them off however we leave.
        execute!(stdout(), EnableMouseCapture)?;
        let result = App::new().run(terminal);
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
    Logs,
    Help,
    Quit,
}

const MENU: [MenuItem; 7] = [
    MenuItem::Pair,
    MenuItem::Devices,
    MenuItem::StartStop,
    MenuItem::StartAtLogin,
    MenuItem::Logs,
    MenuItem::Help,
    MenuItem::Quit,
];

enum Screen {
    Home,
    Pair(PairView),
    Devices(DevicesView),
    Logs(LogsView),
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
    local: LocalStats,
    refreshed: Option<Instant>,
    started: Instant,
    toast: Option<Toast>,
    /// Where the menu rows were drawn, so mouse clicks can find them.
    menu_rows: Rect,
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
            local: LocalStats::new(),
            refreshed: None,
            started: Instant::now(),
            toast: None,
            menu_rows: Rect::default(),
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
            terminal.draw(|frame| screens::draw(&mut self, frame))?;
            if event::poll(FRAME)? {
                match event::read()? {
                    Event::Key(key) if key.kind == KeyEventKind::Press => self.on_key(key),
                    Event::Mouse(mouse) => self.on_mouse(mouse),
                    _ => {}
                }
            }
        }
        self.leave_pairing();
        Ok(())
    }

    fn refresh(&mut self) {
        self.daemon = control::status_blocking().ok().flatten();
        self.service = service::state().ok();
        self.paired_count = DeviceStore::open()
            .and_then(|s| s.list())
            .map_or(0, |d| d.len());
        self.local.sample();
        self.refreshed = Some(Instant::now());
        if let Screen::Pair(view) = &mut self.screen
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
                KeyCode::Char(c @ '1'..='7') => {
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
            Screen::Help => {
                if back || key.code == KeyCode::Enter {
                    self.screen = Screen::Home;
                }
            }
        }
    }

    fn on_mouse(&mut self, mouse: MouseEvent) {
        let position = Position::new(mouse.column, mouse.row);
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
            (Screen::Help | Screen::Pair(_), MouseEventKind::Down(MouseButton::Right)) => {
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
            MenuItem::Logs => self.screen = Screen::Logs(logs_view()),
            MenuItem::Help => self.screen = Screen::Help,
            MenuItem::Quit => self.quit = true,
        }
    }

    /// Whether the daemon is running, but not as the background service
    /// (someone ran `yaedockd run` in a terminal).
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

    /// Gives the daemon a moment to start or stop, then shows its new state.
    fn settle(&mut self) {
        std::thread::sleep(Duration::from_millis(400));
        self.refresh();
    }

    /// Cancels a pairing that is still waiting, so its code stops working.
    fn leave_pairing(&mut self) {
        if let Screen::Pair(view) = &self.screen
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
