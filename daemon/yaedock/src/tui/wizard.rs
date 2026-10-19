//! The first-run setup wizard: one page at a time (check this PC, install the
//! daemon, pair a phone), each with one obvious button. Slow work runs on
//! background threads so the screen keeps moving and shows progress.

use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::thread;
use std::time::{Duration, Instant};

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::{Alignment, Constraint, Flex, Layout, Rect};
use ratatui::style::Style;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, BorderType, Padding, Paragraph, Wrap};
use yaedock_core::control;

use super::theme::{self, SPINNER};
use super::{App, PairView, Screen, pair_view, screens};
use crate::check::{self, Check, Outcome};
use crate::service;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Page {
    Check,
    Install,
    Pair,
    Done,
}

const STEPS: [(Page, &str); 3] = [
    (Page::Check, "Check"),
    (Page::Install, "Install"),
    (Page::Pair, "Pair"),
];

pub struct WizardView {
    pub page: Page,
    /// `None` while the check is running.
    pub checks: Option<Vec<Check>>,
    checks_rx: Option<Receiver<Vec<Check>>>,
    pub install: Option<InstallRun>,
    pub pair: Option<PairView>,
    /// Where the page's button was drawn, so a click can press it.
    pub button: Rect,
}

pub struct InstallRun {
    pub tasks: Vec<Task>,
    rx: Receiver<Event>,
    /// Set once every task has run, or one failed.
    pub finished: Option<Result<(), String>>,
}

pub struct Task {
    pub label: &'static str,
    pub state: TaskState,
}

#[derive(Clone, PartialEq, Eq)]
pub enum TaskState {
    Waiting,
    Running,
    Done,
    Failed(String),
}

enum Event {
    Running(usize),
    Done(usize),
    Failed(usize, String),
    Finished(Result<(), String>),
}

const INSTALL_TASKS: [&str; 4] = [
    "Writing the service file",
    "Starting the daemon",
    "Waiting for it to answer",
    "Turning on start at login",
];

/// Each step stays on screen at least this long, so progress can be followed.
const MIN_TASK_TIME: Duration = Duration::from_millis(350);

impl WizardView {
    pub fn new() -> Self {
        let mut view = Self {
            page: Page::Check,
            checks: None,
            checks_rx: None,
            install: None,
            pair: None,
            button: Rect::default(),
        };
        view.start_checks();
        view
    }

    /// A wizard whose check has already run, for tests.
    #[cfg(test)]
    pub fn with_checks(checks: Vec<Check>) -> Self {
        Self {
            page: Page::Check,
            checks: Some(checks),
            checks_rx: None,
            install: None,
            pair: None,
            button: Rect::default(),
        }
    }

    /// Shows install progress without installing anything, for tests.
    #[cfg(test)]
    pub fn with_install_progress(
        &mut self,
        states: Vec<TaskState>,
        finished: Option<Result<(), String>>,
    ) {
        let (_tx, rx) = mpsc::channel();
        self.page = Page::Install;
        self.install = Some(InstallRun {
            tasks: INSTALL_TASKS
                .iter()
                .zip(states)
                .map(|(label, state)| Task { label, state })
                .collect(),
            rx,
            finished,
        });
    }

    /// Runs the check again, for example after the firewall was changed.
    pub fn recheck(&mut self) {
        self.page = Page::Check;
        self.start_checks();
    }

    fn start_checks(&mut self) {
        let (tx, rx) = mpsc::channel();
        thread::spawn(move || {
            let started = Instant::now();
            let checks = check::run();
            // A moment of "Checking…" reads better than a flash.
            thread::sleep(Duration::from_millis(600).saturating_sub(started.elapsed()));
            let _ = tx.send(checks);
        });
        self.checks = None;
        self.checks_rx = Some(rx);
    }

    fn start_install(&mut self) {
        let (tx, rx) = mpsc::channel();
        thread::spawn(move || install_worker(&tx));
        self.install = Some(InstallRun {
            tasks: INSTALL_TASKS
                .iter()
                .map(|label| Task {
                    label,
                    state: TaskState::Waiting,
                })
                .collect(),
            rx,
            finished: None,
        });
    }

    /// Takes in results from background work. Returns whether anything changed.
    pub fn poll(&mut self) -> bool {
        let mut changed = false;
        if let Some(rx) = &self.checks_rx {
            match rx.try_recv() {
                Ok(checks) => {
                    self.checks = Some(checks);
                    self.checks_rx = None;
                    changed = true;
                }
                Err(TryRecvError::Disconnected) => self.checks_rx = None,
                Err(TryRecvError::Empty) => {}
            }
        }
        if let Some(run) = &mut self.install {
            while let Ok(event) = run.rx.try_recv() {
                changed = true;
                match event {
                    Event::Running(i) => run.tasks[i].state = TaskState::Running,
                    Event::Done(i) => run.tasks[i].state = TaskState::Done,
                    Event::Failed(i, why) => run.tasks[i].state = TaskState::Failed(why),
                    Event::Finished(result) => run.finished = Some(result),
                }
            }
        }
        changed
    }

    fn installing(&self) -> bool {
        self.install
            .as_ref()
            .is_some_and(|run| run.finished.is_none())
    }
}

fn install_worker(tx: &Sender<Event>) {
    let send = |event| {
        let _ = tx.send(event);
    };
    let steps: [&dyn Fn() -> Result<(), String>; 4] = [
        &|| service::install().map(|_| ()).map_err(|e| format!("{e:#}")),
        &|| service::control("start").map_err(|e| format!("{e:#}")),
        &|| {
            let deadline = Instant::now() + Duration::from_secs(10);
            while Instant::now() < deadline {
                if control::status_blocking().ok().flatten().is_some() {
                    return Ok(());
                }
                thread::sleep(Duration::from_millis(200));
            }
            Err("It did not answer within 10 seconds. `yaedock service logs` shows why.".into())
        },
        &|| service::control("enable").map_err(|e| format!("{e:#}")),
    ];
    for (i, step) in steps.iter().enumerate() {
        send(Event::Running(i));
        let started = Instant::now();
        let result = step();
        thread::sleep(MIN_TASK_TIME.saturating_sub(started.elapsed()));
        match result {
            Ok(()) => send(Event::Done(i)),
            Err(why) => {
                send(Event::Failed(i, why.clone()));
                send(Event::Finished(Err(why)));
                return;
            }
        }
    }
    send(Event::Finished(Ok(())));
}

impl App {
    fn wizard(&mut self) -> Option<&mut WizardView> {
        match &mut self.screen {
            Screen::Wizard(view) => Some(view),
            _ => None,
        }
    }

    /// Whether a step is done, for the stepper and the summary.
    fn step_done(&self, view: &WizardView, page: Page) -> bool {
        match page {
            Page::Check => view.checks.is_some(),
            Page::Install => self.service.is_some_and(|s| s.installed && s.running),
            Page::Pair => self.paired_count > 0,
            Page::Done => true,
        }
    }

    pub(super) fn wizard_key(&mut self, key: KeyEvent) {
        let Some(view) = self.wizard() else { return };
        let pairing = view.pair.as_ref().is_some_and(|p| p.outcome.is_none());
        match key.code {
            KeyCode::Enter | KeyCode::Char(' ') => self.wizard_primary(),
            KeyCode::Right | KeyCode::Char('s')
                if !self.wizard().is_some_and(|v| v.installing()) =>
            {
                self.cancel_wizard_pairing();
                self.wizard_go(1);
            }
            KeyCode::Left | KeyCode::Backspace
                if !self.wizard().is_some_and(|v| v.installing()) =>
            {
                self.cancel_wizard_pairing();
                self.wizard_go(-1);
            }
            KeyCode::Char('r')
                if self
                    .wizard()
                    .is_some_and(|v| v.page == Page::Check && v.checks.is_some()) =>
            {
                if let Some(view) = self.wizard() {
                    view.start_checks();
                }
            }
            KeyCode::Esc if pairing => self.cancel_wizard_pairing(),
            KeyCode::Esc => {
                if !self.wizard().is_some_and(|v| v.installing()) {
                    self.screen = Screen::Home;
                }
            }
            KeyCode::Char('a') => {
                if let Some(command) = self
                    .wizard()
                    .and_then(|v| fix_command(v.checks.as_deref()?, false))
                {
                    self.pending_command = Some(command);
                }
            }
            KeyCode::Char('q') => self.quit = true,
            _ => {}
        }
    }

    pub(super) fn wizard_click(&mut self, position: ratatui::layout::Position) {
        if self.wizard().is_some_and(|v| v.button.contains(position)) {
            self.wizard_primary();
        }
    }

    fn wizard_go(&mut self, by: isize) {
        let order = [Page::Check, Page::Install, Page::Pair, Page::Done];
        if let Some(view) = self.wizard() {
            let i = order.iter().position(|p| *p == view.page).unwrap_or(0) as isize;
            view.page = order[(i + by).clamp(0, order.len() as isize - 1) as usize];
        }
    }

    fn cancel_wizard_pairing(&mut self) {
        self.leave_pairing();
        if let Some(view) = self.wizard() {
            view.pair = None;
        }
    }

    /// What the page's button does.
    fn wizard_primary(&mut self) {
        let started_by_hand = self.started_by_hand();
        let installed = self.service.is_some_and(|s| s.installed && s.running);
        let Some(view) = self.wizard() else { return };
        match view.page {
            Page::Check if view.checks.is_some() => {
                // A blocked port stops phones connecting, so fixing it comes first.
                match view.checks.as_deref().and_then(|c| fix_command(c, true)) {
                    Some(command) => self.pending_command = Some(command),
                    None => view.page = Page::Install,
                }
            }
            Page::Check => {}
            Page::Install => match view.install.as_ref().map(|run| &run.finished) {
                Some(None) => {}
                Some(Some(Ok(()))) => view.page = Page::Pair,
                Some(Some(Err(_))) => view.start_install(),
                None if installed => view.page = Page::Pair,
                None if started_by_hand => self.refresh(),
                None => view.start_install(),
            },
            Page::Pair
                if view.pair.is_none()
                    && view
                        .checks
                        .as_deref()
                        .and_then(|c| fix_command(c, true))
                        .is_some() =>
            {
                self.pending_command = view.checks.as_deref().and_then(|c| fix_command(c, true));
            }
            Page::Pair => match view.pair.as_ref().map(|p| &p.outcome) {
                Some(None) => {}
                Some(Some(Ok(_))) => view.page = Page::Done,
                Some(Some(Err(_))) | None => match pair_view() {
                    Ok(pair) => {
                        if let Some(view) = self.wizard() {
                            view.pair = Some(pair);
                        }
                    }
                    Err(err) => self.toast(format!("Could not start pairing: {err:#}"), true),
                },
            },
            Page::Done => self.screen = Screen::Home,
        }
    }
}

// ---------------------------------------------------------------- drawing

pub(super) fn draw(app: &mut App, frame: &mut Frame, area: Rect) {
    let frame_no = app.frame();
    let daemon_running = app.daemon.is_some();
    let Screen::Wizard(view) = &app.screen else {
        return;
    };
    let done: Vec<bool> = STEPS
        .iter()
        .map(|(page, _)| app.step_done(view, *page))
        .collect();

    let [_, title, _, stepper, body] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Min(0),
    ])
    .areas(area);
    frame.render_widget(
        Paragraph::new(theme::gradient_line("◆ YaeDock setup", true)).alignment(Alignment::Center),
        title,
    );

    let mut spans = Vec::new();
    for (i, (page, label)) in STEPS.iter().enumerate() {
        let current = view.page == *page;
        let (dot, color) = if done[i] {
            ("✓", theme::good())
        } else if current {
            ("●", theme::accent())
        } else {
            ("○", theme::muted())
        };
        if i > 0 {
            let line_color = if done[i - 1] {
                theme::good()
            } else {
                theme::border()
            };
            spans.push(Span::styled("  ━━━━━━  ", Style::new().fg(line_color)));
        }
        spans.push(Span::styled(
            format!("{dot} "),
            Style::new().fg(color).bold(),
        ));
        let label_style = if current {
            Style::new().fg(theme::text()).bold()
        } else {
            Style::new().fg(color)
        };
        spans.push(Span::styled(*label, label_style));
    }
    frame.render_widget(
        Paragraph::new(Line::from(spans)).alignment(Alignment::Center),
        stepper,
    );

    // The QR code needs the whole page.
    if view.page == Page::Pair
        && let Some(pair) = &view.pair
        && pair.outcome.is_none()
    {
        screens::pair_screen(frame, body, pair, daemon_running, frame_no);
        if let Screen::Wizard(view) = &mut app.screen {
            view.button = Rect::default();
        }
        return;
    }

    let page = page_content(app, view, frame_no);
    let width = body.width.saturating_sub(4).min(72);
    let text_height = wrapped_height(&page.lines, width.saturating_sub(8));
    let card_height = (text_height + 2 + 2 + if page.button.is_some() { 4 } else { 0 })
        .min(body.height.saturating_sub(2));
    let [card_row, nav_row] =
        Layout::vertical([Constraint::Length(card_height), Constraint::Length(2)])
            .flex(Flex::Center)
            .areas(body);
    let [card] = Layout::horizontal([Constraint::Length(width)])
        .flex(Flex::Center)
        .areas(card_row);
    let block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::new().fg(theme::border()))
        .padding(Padding::new(3, 3, 1, 1));
    let inner = block.inner(card);
    frame.render_widget(block, card);
    let [text_area, button_area] = Layout::vertical([
        Constraint::Min(0),
        Constraint::Length(if page.button.is_some() { 3 } else { 0 }),
    ])
    .areas(inner);
    frame.render_widget(
        Paragraph::new(page.lines).wrap(Wrap { trim: false }),
        text_area,
    );

    let mut button_rect = Rect::default();
    if let Some((label, enabled)) = page.button {
        let w = label.chars().count() as u16 + 10;
        let [rect] = Layout::horizontal([Constraint::Length(w)])
            .flex(Flex::Center)
            .areas(button_area);
        let color = if enabled {
            theme::accent()
        } else {
            theme::muted()
        };
        frame.render_widget(
            Paragraph::new(Line::from(Span::styled(
                label,
                Style::new().fg(color).bold(),
            )))
            .alignment(Alignment::Center)
            .block(
                Block::bordered()
                    .border_type(BorderType::Rounded)
                    .border_style(Style::new().fg(color)),
            ),
            rect,
        );
        button_rect = rect;
    }

    let [back, skip] = Layout::horizontal([Constraint::Fill(1), Constraint::Fill(1)]).areas(Rect {
        x: card.x + 1,
        width: card.width.saturating_sub(2),
        ..nav_row
    });
    let nav = Style::new().fg(theme::muted());
    if view.page != Page::Check {
        frame.render_widget(
            Span::styled("← Back", nav),
            Rect {
                y: back.y + 1,
                height: 1,
                ..back
            },
        );
    }
    if view.page != Page::Done {
        frame.render_widget(
            Paragraph::new(Span::styled("Skip →", nav)).alignment(Alignment::Right),
            Rect {
                y: skip.y + 1,
                height: 1,
                ..skip
            },
        );
    }
    if let Screen::Wizard(view) = &mut app.screen {
        view.button = button_rect;
    }
}

/// How many rows `lines` take when wrapped to `width` columns.
fn wrapped_height(lines: &[Line], width: u16) -> u16 {
    let width = width.max(1) as usize;
    lines
        .iter()
        .map(|line| {
            let w: usize = line.spans.iter().map(|s| s.width()).sum();
            w.div_ceil(width).max(1) as u16
        })
        .sum()
}

/// The command that fixes the first problem found: only ones that stop phones
/// connecting when `blocking_only`, otherwise warnings too.
fn fix_command(checks: &[Check], blocking_only: bool) -> Option<String> {
    checks
        .iter()
        .filter(|c| c.outcome == Outcome::Fail || (!blocking_only && c.outcome == Outcome::Warn))
        .find_map(|c| c.command.clone())
}

/// The lines explaining how to fix a blocked port, from here or another terminal.
fn fix_lines(command: &str) -> Vec<Line<'static>> {
    vec![
        Line::from(""),
        Line::from("Allow it now asks for your password and runs:"),
        Line::from(Span::styled(
            format!("  {command}"),
            Style::new().fg(theme::accent()).bold(),
        )),
        Line::from(""),
        plain("Prefer to do it yourself? Run it in another terminal, then press r to check again."),
    ]
}

struct PageContent {
    lines: Vec<Line<'static>>,
    /// The button's label, and whether it does anything right now.
    button: Option<(String, bool)>,
}

fn heading(text: &str) -> Line<'static> {
    Line::from(Span::styled(
        text.to_string(),
        Style::new().fg(theme::text()).bold(),
    ))
}

fn plain(text: &str) -> Line<'static> {
    Line::from(Span::styled(
        text.to_string(),
        Style::new().fg(theme::muted()),
    ))
}

fn page_content(app: &App, view: &WizardView, frame_no: u64) -> PageContent {
    let spinner = SPINNER[(frame_no % SPINNER.len() as u64) as usize];
    match view.page {
        Page::Check => match &view.checks {
            None => PageContent {
                lines: vec![
                    heading("Checking this PC"),
                    Line::from(""),
                    Line::from(vec![
                        Span::styled(format!("{spinner} "), Style::new().fg(theme::accent())),
                        "Looking at stats, sensors, brightness, volume and network…".into(),
                    ]),
                ],
                button: Some(("Checking…".into(), false)),
            },
            Some(checks) => {
                // The daemon is what the next page installs, so it is not a problem here.
                let checks: Vec<&Check> = checks.iter().filter(|c| c.area != "Daemon").collect();
                let fails = checks.iter().filter(|c| c.outcome == Outcome::Fail).count();
                let warns = checks.iter().filter(|c| c.outcome == Outcome::Warn).count();
                let (headline, color) = match (fails, warns) {
                    (0, 0) => (
                        "Good news: everything YaeDock needs works on this PC.",
                        theme::good(),
                    ),
                    (0, _) => ("Almost everything works. One thing to know:", theme::warn()),
                    _ => (
                        "Some features will not work until this is fixed:",
                        theme::bad(),
                    ),
                };
                let mut lines = vec![
                    heading("Checking this PC"),
                    Line::from(""),
                    Line::from(Span::styled(headline, Style::new().fg(color).bold())),
                ];
                for check in checks.iter().filter(|c| c.outcome != Outcome::Ok) {
                    let (mark, color) = if check.outcome == Outcome::Fail {
                        ("✗", theme::bad())
                    } else {
                        ("!", theme::warn())
                    };
                    lines.push(Line::from(""));
                    lines.push(Line::from(vec![
                        Span::styled(
                            format!("{mark} {}: ", check.area),
                            Style::new().fg(color).bold(),
                        ),
                        Span::styled(check.summary.clone(), Style::new().fg(theme::text())),
                    ]));
                    if let Some(fix) = &check.fix {
                        lines.push(plain(fix));
                    }
                }
                let working: Vec<&str> = checks
                    .iter()
                    .filter(|c| c.outcome == Outcome::Ok)
                    .map(|c| c.area)
                    .collect();
                lines.push(Line::from(""));
                lines.push(Line::from(vec![
                    Span::styled("✓ Works: ", Style::new().fg(theme::good()).bold()),
                    Span::styled(working.join(", "), Style::new().fg(theme::muted())),
                ]));
                let all: Vec<Check> = checks.iter().map(|c| (*c).clone()).collect();
                if let Some(command) = fix_command(&all, true) {
                    lines.extend(fix_lines(&command));
                    return PageContent {
                        lines,
                        button: Some(("Allow it now".into(), true)),
                    };
                }
                if fix_command(&all, false).is_some() {
                    lines.push(Line::from(""));
                    lines.push(plain(
                        "Press a to fix this now (asks for your password), or continue without it.",
                    ));
                }
                PageContent {
                    lines,
                    button: Some(("Continue".into(), true)),
                }
            }
        },
        Page::Install => {
            let mut lines = vec![heading("Install the YaeDock daemon"), Line::from("")];
            if let Some(run) = &view.install {
                for task in &run.tasks {
                    let (mark, color) = match &task.state {
                        TaskState::Waiting => ("·".to_string(), theme::muted()),
                        TaskState::Running => (spinner.to_string(), theme::accent()),
                        TaskState::Done => ("✓".to_string(), theme::good()),
                        TaskState::Failed(_) => ("✗".to_string(), theme::bad()),
                    };
                    let text_color = if task.state == TaskState::Waiting {
                        theme::muted()
                    } else {
                        theme::text()
                    };
                    lines.push(Line::from(vec![
                        Span::styled(format!(" {mark}  "), Style::new().fg(color).bold()),
                        Span::styled(task.label, Style::new().fg(text_color)),
                    ]));
                    if let TaskState::Failed(why) = &task.state {
                        lines.push(Line::from(Span::styled(
                            format!("    {why}"),
                            Style::new().fg(theme::bad()),
                        )));
                    }
                }
                lines.push(Line::from(""));
                let button = match &run.finished {
                    None => {
                        lines.push(plain("Installing… this only takes a moment."));
                        ("Installing…", false)
                    }
                    Some(Ok(())) => {
                        lines.push(Line::from(Span::styled(
                            "Installed! The daemon is running and starts by itself when you log in.",
                            Style::new().fg(theme::good()).bold(),
                        )));
                        ("Continue", true)
                    }
                    Some(Err(_)) => {
                        lines.push(Line::from(Span::styled(
                            "The install did not finish.",
                            Style::new().fg(theme::bad()).bold(),
                        )));
                        ("Try again", true)
                    }
                };
                return PageContent {
                    lines,
                    button: Some((button.0.into(), button.1)),
                };
            }
            if app.service.is_some_and(|s| s.installed && s.running) {
                lines.push(Line::from(Span::styled(
                    "✓ The daemon is already installed and running.",
                    Style::new().fg(theme::good()).bold(),
                )));
                lines.push(if app.service.is_some_and(|s| s.enabled) {
                    plain("It starts by itself when you log in.")
                } else {
                    plain("It does not start at login yet; turn that on in the menu.")
                });
                return PageContent {
                    lines,
                    button: Some(("Continue".into(), true)),
                };
            }
            if app.started_by_hand() {
                lines.push(Line::from(Span::styled(
                    "The daemon is running in a terminal right now.",
                    Style::new().fg(theme::warn()).bold(),
                )));
                lines.push(plain(
                    "Stop it there with Ctrl+C, then press the button to check again.",
                ));
                return PageContent {
                    lines,
                    button: Some(("Check again".into(), true)),
                };
            }
            lines.push(Line::from(
                "The daemon is the part of YaeDock that talks to your phone.",
            ));
            lines.push(Line::from(""));
            lines.push(plain("It runs quietly in the background, uses almost no CPU or memory, and starts by itself when you log in. You can stop or remove it any time from the menu."));
            PageContent {
                lines,
                button: Some(("Install now".into(), true)),
            }
        }
        Page::Pair => {
            let mut lines = vec![heading("Pair your phone"), Line::from("")];
            match view.pair.as_ref().and_then(|p| p.outcome.as_ref()) {
                Some(Ok(device)) => {
                    lines.push(Line::from(vec![
                        Span::styled("✓ Paired with ", Style::new().fg(theme::good()).bold()),
                        Span::styled(device.clone(), Style::new().fg(theme::violet()).bold()),
                    ]));
                    lines.push(plain(
                        "Your phone is connected and will reconnect by itself from now on.",
                    ));
                    PageContent {
                        lines,
                        button: Some(("Continue".into(), true)),
                    }
                }
                Some(Err(why)) => {
                    lines.push(Line::from(Span::styled(
                        format!("✗ {why}"),
                        Style::new().fg(theme::bad()).bold(),
                    )));
                    lines.push(plain("Nothing was changed. Try again with a new code."));
                    PageContent {
                        lines,
                        button: Some(("Try again".into(), true)),
                    }
                }
                None => {
                    lines.push(Line::from(vec![
                        Span::styled("1  ", Style::new().fg(theme::accent()).bold()),
                        "Install the YaeDock app on your Android phone.".into(),
                    ]));
                    lines.push(Line::from(vec![
                        Span::styled("2  ", Style::new().fg(theme::accent()).bold()),
                        "Open it and tap Scan QR code.".into(),
                    ]));
                    lines.push(Line::from(vec![
                        Span::styled("3  ", Style::new().fg(theme::accent()).bold()),
                        "Point the camera at the code on the next screen.".into(),
                    ]));
                    lines.push(Line::from(""));
                    lines.push(plain("The phone and this PC need to be on the same Wi-Fi."));
                    if app.daemon.is_none() {
                        lines.push(Line::from(""));
                        lines.push(Line::from(Span::styled(
                            "The daemon is not running, so the phone cannot connect. Go back a step to install it.",
                            Style::new().fg(theme::warn()),
                        )));
                    }
                    if let Some(command) = view.checks.as_deref().and_then(|c| fix_command(c, true))
                    {
                        lines.push(Line::from(""));
                        lines.push(Line::from(Span::styled(
                            "Your firewall blocks YaeDock, so pairing cannot work yet.",
                            Style::new().fg(theme::bad()).bold(),
                        )));
                        lines.extend(fix_lines(&command));
                        return PageContent {
                            lines,
                            button: Some(("Allow it now".into(), true)),
                        };
                    }
                    PageContent {
                        lines,
                        button: Some(("Show the QR code".into(), true)),
                    }
                }
            }
        }
        Page::Done => {
            let installed = app.service.is_some_and(|s| s.installed && s.running);
            let mut lines = vec![
                theme::gradient_line("You're all set!", true),
                Line::from(""),
            ];
            lines.push(if installed {
                Line::from(vec![
                    Span::styled("✓ ", Style::new().fg(theme::good()).bold()),
                    "The daemon runs in the background and starts at login".into(),
                ])
            } else {
                Line::from(vec![
                    Span::styled("! ", Style::new().fg(theme::warn()).bold()),
                    "The daemon is not installed yet; start it from the menu".into(),
                ])
            });
            lines.push(if app.paired_count > 0 {
                Line::from(vec![
                    Span::styled("✓ ", Style::new().fg(theme::good()).bold()),
                    format!(
                        "{} phone{} paired",
                        app.paired_count,
                        if app.paired_count == 1 { "" } else { "s" }
                    )
                    .into(),
                ])
            } else {
                Line::from(vec![
                    Span::styled("! ", Style::new().fg(theme::warn()).bold()),
                    "No phone paired yet; choose Pair a phone in the menu".into(),
                ])
            });
            lines.push(Line::from(""));
            lines.push(plain("Run yaedock any time to see this PC's status, pair more phones, or change settings."));
            PageContent {
                lines,
                button: Some(("Open the menu".into(), true)),
            }
        }
    }
}
