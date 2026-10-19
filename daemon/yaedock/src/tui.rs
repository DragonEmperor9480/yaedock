//! The menu shown when `yaedock` runs in a terminal without a command: daemon
//! status, pairing, paired phones, background service control, logs and help.

use std::net::IpAddr;
use std::process::Command;
use std::time::{Duration, Instant};

use ratatui::crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::layout::{Alignment, Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style, Stylize};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{
    Block, BorderType, List, ListItem, ListState, Padding, Paragraph, Row, Table, TableState, Wrap,
};
use ratatui::{DefaultTerminal, Frame};
use yaedock_core::control::{self, DaemonStatus};
use yaedock_core::devices::{Device, DeviceStore};
use yaedock_core::pairing::{self, Pairing, Status as PairingStatus};
use yaedock_core::tls::Identity;
use yaedock_core::{DEFAULT_PORT, paths};

use crate::pair;
use crate::service::{self, ServiceState};

const REFRESH: Duration = Duration::from_secs(1);
const ACCENT: Color = Color::Cyan;

pub fn run() -> anyhow::Result<()> {
    ratatui::run(|terminal| App::new().run(terminal))
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum MenuItem {
    Pair,
    Devices,
    StartStop,
    StartAtLogin,
    Logs,
    Help,
    Quit,
}

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

struct App {
    screen: Screen,
    menu: ListState,
    daemon: Option<DaemonStatus>,
    service: Option<ServiceState>,
    paired_count: usize,
    refreshed: Option<Instant>,
    /// A one-line message for the footer, and whether it is an error.
    message: Option<(String, bool)>,
    quit: bool,
}

impl App {
    fn new() -> Self {
        Self {
            screen: Screen::Home,
            menu: ListState::default().with_selected(Some(0)),
            daemon: None,
            service: None,
            paired_count: 0,
            refreshed: None,
            message: None,
            quit: false,
        }
    }

    fn run(mut self, terminal: &mut DefaultTerminal) -> anyhow::Result<()> {
        while !self.quit {
            if self.refreshed.is_none_or(|at| at.elapsed() >= REFRESH) {
                self.refresh();
            }
            terminal.draw(|frame| self.draw(frame))?;
            if event::poll(Duration::from_millis(250))?
                && let Event::Key(key) = event::read()?
                && key.kind == KeyEventKind::Press
            {
                self.on_key(key);
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
        self.refreshed = Some(Instant::now());
        if let Screen::Pair(view) = &mut self.screen
            && view.outcome.is_none()
        {
            view.outcome = match pairing::load() {
                Ok(Some(p)) if p.code == view.pairing.code => match p.status {
                    PairingStatus::Paired { device } => Some(Ok(device)),
                    PairingStatus::Locked => {
                        Some(Err("Too many wrong codes. Start pairing again.".into()))
                    }
                    PairingStatus::Waiting if p.is_expired() => {
                        Some(Err("The code expired. Start pairing again.".into()))
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

    fn menu_items(&self) -> Vec<(MenuItem, String)> {
        let running = self.daemon.is_some();
        let enabled = self.service.is_some_and(|s| s.enabled);
        vec![
            (MenuItem::Pair, "Pair a phone".into()),
            (
                MenuItem::Devices,
                format!("Paired phones ({})", self.paired_count),
            ),
            (
                MenuItem::StartStop,
                if running {
                    "Stop the daemon"
                } else {
                    "Start the daemon in the background"
                }
                .into(),
            ),
            (
                MenuItem::StartAtLogin,
                format!("Start at login: {}", if enabled { "on" } else { "off" }),
            ),
            (MenuItem::Logs, "View logs".into()),
            (MenuItem::Help, "Help".into()),
            (MenuItem::Quit, "Quit".into()),
        ]
    }

    fn on_key(&mut self, key: KeyEvent) {
        if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
            self.quit = true;
            return;
        }
        let back = matches!(
            key.code,
            KeyCode::Esc | KeyCode::Char('q') | KeyCode::Backspace
        );
        match &mut self.screen {
            Screen::Home => match key.code {
                KeyCode::Up | KeyCode::Char('k') => self.menu.select_previous(),
                KeyCode::Down | KeyCode::Char('j') => {
                    let last = self.menu_items().len() - 1;
                    self.menu
                        .select(Some(self.menu.selected().map_or(0, |i| (i + 1).min(last))));
                }
                KeyCode::Enter | KeyCode::Char(' ') => {
                    let items = self.menu_items();
                    if let Some((item, _)) = self.menu.selected().and_then(|i| items.get(i)) {
                        self.choose(*item);
                    }
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
                (Some(row), KeyCode::Char('y')) => {
                    let device = view.devices[row].clone();
                    view.confirm = None;
                    self.message = Some(
                        match DeviceStore::open().and_then(|s| s.remove(&device.id)) {
                            Ok(_) => (format!("Unpaired {}.", device.name), false),
                            Err(err) => (format!("Could not unpair: {err:#}"), true),
                        },
                    );
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
                KeyCode::Up | KeyCode::Char('k') => {
                    view.scroll = (view.scroll + 1).min(view.lines.len())
                }
                KeyCode::Down | KeyCode::Char('j') => view.scroll = view.scroll.saturating_sub(1),
                KeyCode::PageUp => view.scroll = (view.scroll + 20).min(view.lines.len()),
                KeyCode::PageDown => view.scroll = view.scroll.saturating_sub(20),
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

    fn choose(&mut self, item: MenuItem) {
        self.message = None;
        match item {
            MenuItem::Pair => match pair_view() {
                Ok(view) => self.screen = Screen::Pair(view),
                Err(err) => {
                    self.message = Some((format!("Could not start pairing: {err:#}"), true))
                }
            },
            MenuItem::Devices => self.screen = Screen::Devices(devices_view()),
            MenuItem::StartStop => self.start_stop(),
            MenuItem::StartAtLogin => {
                let enabled = self.service.is_some_and(|s| s.enabled);
                let (verb, done) = if enabled {
                    ("disable", "The daemon will no longer start at login.")
                } else {
                    ("enable", "The daemon is running and will start at login.")
                };
                if !enabled && self.started_by_hand() {
                    self.message = Some((
                        "The daemon is running in a terminal. Stop it there first, then try again."
                            .into(),
                        true,
                    ));
                    return;
                }
                self.message = Some(match service::control(verb) {
                    Ok(()) => (done.into(), false),
                    Err(err) => (format!("{err:#}"), true),
                });
            }
            MenuItem::Logs => self.screen = Screen::Logs(logs_view()),
            MenuItem::Help => self.screen = Screen::Help,
            MenuItem::Quit => self.quit = true,
        }
        // Let the daemon settle before showing its new state.
        std::thread::sleep(Duration::from_millis(300));
        self.refresh();
    }

    /// Whether the daemon is running, but not as the background service
    /// (someone ran `yaedockd run` in a terminal).
    fn started_by_hand(&self) -> bool {
        self.daemon.is_some() && !self.service.is_some_and(|s| s.running)
    }

    fn start_stop(&mut self) {
        if self.started_by_hand() {
            let pid = self.daemon.as_ref().map_or(0, |d| d.pid);
            self.message = Some((
                format!(
                    "The daemon was started in a terminal (pid {pid}). Stop it there with Ctrl+C."
                ),
                true,
            ));
            return;
        }
        let (verb, done) = if self.daemon.is_some() {
            ("stop", "Stopped the daemon.")
        } else {
            ("start", "Started the daemon in the background.")
        };
        self.message = Some(match service::control(verb) {
            Ok(()) => (done.into(), false),
            Err(err) => (format!("{err:#}"), true),
        });
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

    fn draw(&mut self, frame: &mut Frame) {
        let [header, body, footer] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Min(0),
            Constraint::Length(2),
        ])
        .areas(frame.area());
        frame.render_widget(
            Line::from(vec![
                " YaeDock ".bold().fg(Color::Black).bg(ACCENT),
                " your PC's stats on your phone".dim(),
            ]),
            header,
        );
        let keys = match &self.screen {
            Screen::Home => "↑↓ move   Enter choose   q quit",
            Screen::Pair(_) => "Esc back (cancels the code)",
            Screen::Devices(view) if view.confirm.is_some() => "y unpair   any other key cancel",
            Screen::Devices(_) => "↑↓ move   d unpair   Esc back",
            Screen::Logs(_) => "↑↓ PgUp PgDn scroll   r refresh   Esc back",
            Screen::Help => "Esc back",
        };
        match &mut self.screen {
            Screen::Home => draw_home(
                frame,
                body,
                self.daemon.as_ref(),
                self.service,
                &self.menu_items(),
                &mut self.menu,
            ),
            Screen::Pair(view) => draw_pair(frame, body, view, self.daemon.is_some()),
            Screen::Devices(view) => draw_devices(frame, body, view, self.daemon.as_ref()),
            Screen::Logs(view) => draw_logs(frame, body, view),
            Screen::Help => draw_help(frame, body),
        }
        let mut lines = vec![Line::from(keys).dim()];
        if let Some((text, error)) = &self.message {
            lines.insert(
                0,
                Line::from(text.as_str()).fg(if *error { Color::Red } else { Color::Green }),
            );
        }
        frame.render_widget(Paragraph::new(lines), footer);
    }
}

fn panel(title: &str) -> Block<'_> {
    Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::new().fg(Color::DarkGray))
        .title(Span::from(format!(" {title} ")).bold())
        .padding(Padding::horizontal(1))
}

fn draw_home(
    frame: &mut Frame,
    area: Rect,
    daemon: Option<&DaemonStatus>,
    service: Option<ServiceState>,
    items: &[(MenuItem, String)],
    menu: &mut ListState,
) {
    let mut status = Vec::new();
    match daemon {
        Some(d) => {
            let how = if service.is_some_and(|s| s.running) {
                "in the background"
            } else {
                "in a terminal"
            };
            status.push(Line::from(vec![
                "● ".fg(Color::Green),
                "Running ".bold(),
                format!("{how} on wss://{}", d.listen).into(),
            ]));
            if d.phones.is_empty() {
                status.push(Line::from("  No phones connected").dim());
            }
            for phone in &d.phones {
                status.push(Line::from(vec![
                    "  📱 ".into(),
                    phone.name.clone().bold(),
                    format!("  {}", phone.address).dim(),
                ]));
            }
        }
        None => {
            status.push(Line::from(vec!["○ ".fg(Color::Red), "Not running".bold()]));
            status.push(
                Line::from("  Choose \"Start the daemon\" to run it in the background.").dim(),
            );
        }
    }
    let [status_area, menu_area] = Layout::vertical([
        Constraint::Length(status.len() as u16 + 2),
        Constraint::Min(0),
    ])
    .areas(area);
    frame.render_widget(Paragraph::new(status).block(panel("Daemon")), status_area);

    let list = List::new(items.iter().map(|(_, label)| ListItem::new(label.as_str())))
        .block(panel("Menu"))
        .highlight_style(Style::new().fg(ACCENT).add_modifier(Modifier::BOLD))
        .highlight_symbol("› ");
    frame.render_stateful_widget(list, menu_area, menu);
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

fn draw_pair(frame: &mut Frame, area: Rect, view: &PairView, daemon_running: bool) {
    let block = panel("Pair a phone");
    let inner = block.inner(area);
    frame.render_widget(block, area);

    if let Some(outcome) = &view.outcome {
        let text = match outcome {
            Ok(device) => vec![
                Line::from(vec![
                    "✓ ".fg(Color::Green),
                    "Paired with ".into(),
                    device.clone().bold(),
                ]),
                Line::from(""),
                Line::from("The phone is connected and will reconnect by itself from now on.")
                    .dim(),
            ],
            Err(why) => vec![Line::from(vec!["✗ ".fg(Color::Red), why.as_str().into()])],
        };
        frame.render_widget(
            Paragraph::new(text).alignment(Alignment::Center),
            centered(inner, 3),
        );
        return;
    }

    let qr_lines: Vec<Line> = view.qr.lines().map(Line::from).collect();
    let qr_height = qr_lines.len() as u16;
    let qr_width = view
        .qr
        .lines()
        .map(|l| l.chars().count())
        .max()
        .unwrap_or(0) as u16;
    let left = view.pairing.expires.saturating_sub(pairing::now());
    let mut details = vec![
        Line::from("Open YaeDock on your phone, tap Scan QR code and point it here.").bold(),
        Line::from(""),
        Line::from(vec![
            "Address      ".dim(),
            format!("{}:{}", view.host, view.port).into(),
        ]),
        Line::from(vec![
            "Code         ".dim(),
            format!("{} {}", &view.pairing.code[..3], &view.pairing.code[3..])
                .bold()
                .fg(ACCENT),
        ]),
        Line::from(vec![
            "Fingerprint  ".dim(),
            pair::group(&view.fingerprint).into(),
        ]),
        Line::from(""),
        Line::from(format!(
            "Waiting for a phone… the code expires in {}:{:02}",
            left / 60,
            left % 60
        ))
        .dim(),
    ];
    if !daemon_running {
        details.push(Line::from(""));
        details.push(
            Line::from("The daemon is not running, so the phone cannot connect yet. Start it from the menu.")
                .fg(Color::Yellow),
        );
    }

    if inner.width >= qr_width + 40 && inner.height >= qr_height {
        let [qr_area, text_area] =
            Layout::horizontal([Constraint::Length(qr_width + 2), Constraint::Min(0)]).areas(inner);
        frame.render_widget(Paragraph::new(qr_lines), qr_area);
        frame.render_widget(
            Paragraph::new(details).wrap(Wrap { trim: false }),
            centered(text_area, 9),
        );
    } else if inner.width >= qr_width && inner.height >= qr_height + details.len() as u16 {
        let [qr_area, text_area] =
            Layout::vertical([Constraint::Length(qr_height), Constraint::Min(0)]).areas(inner);
        frame.render_widget(Paragraph::new(qr_lines), qr_area);
        frame.render_widget(
            Paragraph::new(details).wrap(Wrap { trim: false }),
            text_area,
        );
    } else {
        details.insert(
            0,
            Line::from(
                "Make the terminal bigger to show the QR code, or type these details in the app:",
            )
            .fg(Color::Yellow),
        );
        details.insert(1, Line::from(""));
        frame.render_widget(Paragraph::new(details).wrap(Wrap { trim: false }), inner);
    }
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

fn draw_devices(
    frame: &mut Frame,
    area: Rect,
    view: &mut DevicesView,
    daemon: Option<&DaemonStatus>,
) {
    let block = panel("Paired phones");
    if view.devices.is_empty() {
        frame.render_widget(
            Paragraph::new("No phones are paired yet. Choose \"Pair a phone\" in the menu.")
                .block(block),
            area,
        );
        return;
    }
    let now = pairing::now();
    let rows = view.devices.iter().map(|device| {
        let connected = daemon.is_some_and(|d| d.phones.iter().any(|p| p.device_id == device.id));
        let days = now.saturating_sub(device.paired_at) / 86_400;
        Row::new(vec![
            Line::from(if connected {
                "● connected".fg(Color::Green)
            } else {
                "○".dim()
            }),
            Line::from(device.name.clone()),
            Line::from(match days {
                0 => "today".to_string(),
                1 => "yesterday".to_string(),
                n => format!("{n} days ago"),
            }),
            Line::from(device.id.clone()).dim(),
        ])
    });
    let table = Table::new(
        rows,
        [
            Constraint::Length(12),
            Constraint::Percentage(35),
            Constraint::Length(12),
            Constraint::Min(10),
        ],
    )
    .header(Row::new(vec!["", "Name", "Paired", "Device id"]).style(Style::new().dim()))
    .row_highlight_style(Style::new().fg(ACCENT).add_modifier(Modifier::BOLD))
    .highlight_symbol("› ")
    .block(block);
    frame.render_stateful_widget(table, area, &mut view.table);

    if let Some(row) = view.confirm {
        let name = &view.devices[row].name;
        let popup = centered(area, 5);
        frame.render_widget(ratatui::widgets::Clear, popup);
        frame.render_widget(
            Paragraph::new(vec![
                Line::from(format!("Unpair {name}?")).bold(),
                Line::from("It will need to scan a new code to connect again.").dim(),
            ])
            .alignment(Alignment::Center)
            .block(panel("Confirm")),
            popup,
        );
    }
}

fn logs_view() -> LogsView {
    let output = Command::new("journalctl")
        .args([
            "--user",
            "-u",
            "yaedockd.service",
            "-n",
            "300",
            "--no-pager",
            "-o",
            "short",
        ])
        .output();
    let mut lines: Vec<String> = match output {
        Ok(out) => String::from_utf8_lossy(&out.stdout)
            .lines()
            .map(str::to_string)
            .collect(),
        Err(err) => vec![format!("Could not run journalctl: {err}")],
    };
    if lines.iter().all(|l| l.starts_with("-- ")) {
        lines = vec![
            "No logs from the background service yet.".into(),
            "Logs appear here once the daemon runs in the background (Start the daemon in the menu).".into(),
            "A daemon started with `yaedockd run` prints its log in its own terminal instead.".into(),
        ];
    }
    LogsView { lines, scroll: 0 }
}

fn draw_logs(frame: &mut Frame, area: Rect, view: &LogsView) {
    let block = panel("Daemon log");
    let height = block.inner(area).height as usize;
    let end = view.lines.len().saturating_sub(view.scroll);
    let start = end.saturating_sub(height);
    let text = Text::from(
        view.lines[start..end]
            .iter()
            .map(|l| Line::from(l.as_str()))
            .collect::<Vec<_>>(),
    );
    frame.render_widget(Paragraph::new(text).block(block), area);
}

fn draw_help(frame: &mut Frame, area: Rect) {
    let heading = |s: &'static str| Line::from(s).bold().fg(ACCENT);
    let command = |cmd: &'static str, what: &'static str| {
        Line::from(vec![format!("  {cmd:<28}").bold(), what.into()])
    };
    let text = vec![
        heading("What YaeDock is"),
        Line::from(
            "  yaedockd runs on this PC and streams its stats to the YaeDock app on your phone,",
        ),
        Line::from(
            "  which can also change this PC's brightness and volume. The phone needs the same Wi-Fi.",
        ),
        Line::from(""),
        heading("Getting started"),
        Line::from("  1. Start the daemon (in the menu, or `yaedock service start`)."),
        Line::from("  2. Pair a phone and scan the code with the YaeDock app."),
        Line::from("  3. Turn on \"Start at login\" so it is always there."),
        Line::from(""),
        heading("Commands"),
        command("yaedock", "this menu"),
        command(
            "yaedock status",
            "is the daemon running, which phones are connected",
        ),
        command("yaedock pair", "pair a phone from the command line"),
        command(
            "yaedock devices / unpair ID",
            "list or remove paired phones",
        ),
        command(
            "yaedock service start|stop|enable",
            "control the background daemon",
        ),
        command("yaedock service logs", "follow the daemon's log"),
        command("yaedockd run", "run the daemon in this terminal instead"),
        Line::from(""),
        heading("If the phone cannot connect"),
        Line::from(
            "  Make sure both are on the same network, and that the firewall allows TCP port 9481,",
        ),
        Line::from("  for example: sudo ufw allow 9481/tcp"),
    ];
    frame.render_widget(
        Paragraph::new(text)
            .wrap(Wrap { trim: false })
            .block(panel("Help")),
        area,
    );
}

/// A full-width strip of `height` rows in the vertical middle of `area`.
fn centered(area: Rect, height: u16) -> Rect {
    let [_, middle, _] = Layout::vertical([
        Constraint::Fill(1),
        Constraint::Length(height),
        Constraint::Fill(1),
    ])
    .areas(area);
    middle
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    fn render(app: &mut App, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal.draw(|frame| app.draw(frame)).unwrap();
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

    #[test]
    fn home_shows_status_and_menu() {
        let mut app = App::new();
        let screen = render(&mut app, 70, 20);
        assert!(screen.contains("Not running"));
        assert!(screen.contains("Pair a phone"));
        assert!(screen.contains("Start the daemon in the background"));

        app.daemon = Some(DaemonStatus {
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
        });
        let screen = render(&mut app, 70, 20);
        assert!(screen.contains("Running"));
        assert!(screen.contains("Pixel"));
        assert!(screen.contains("Stop the daemon"));
    }

    #[test]
    fn help_fits_a_normal_terminal() {
        let mut app = App::new();
        app.screen = Screen::Help;
        assert!(render(&mut app, 100, 30).contains("yaedock service logs"));
    }
}
