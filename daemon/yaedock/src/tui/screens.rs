//! Drawing every screen of the terminal menu.

use ratatui::Frame;
use ratatui::layout::{Alignment, Constraint, Flex, Layout, Margin, Rect};
use ratatui::style::{Color, Modifier, Style, Stylize};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{
    Block, BorderType, Borders, Clear, LineGauge, Padding, Paragraph, Row, Scrollbar,
    ScrollbarOrientation, ScrollbarState, Sparkline, Table, Wrap,
};
use yaedock_core::pairing::{self, CODE_LIFETIME};

use super::theme::{self, LOGO, SPINNER};
use super::{App, DevicesView, LogsView, MENU, MenuItem, PairView, Screen, wizard};
use crate::pair;

pub fn draw(app: &mut App, frame: &mut Frame) {
    let [body, footer] =
        Layout::vertical([Constraint::Min(0), Constraint::Length(1)]).areas(frame.area());
    let body = body.inner(Margin::new(1, 0));
    let hints: &[(&str, &str)] = match &app.screen {
        Screen::Home => &[
            ("↑↓", "move"),
            ("↵", "choose"),
            ("1-9", "jump"),
            ("q", "quit"),
        ],
        Screen::Check(_) => &[("r", "run again"), ("esc", "back")],
        Screen::Wizard(view) if view.pair.as_ref().is_some_and(|p| p.outcome.is_none()) => {
            &[("esc", "cancel pairing")]
        }
        Screen::Wizard(view) if view.mandatory => &[
            ("↵", "continue"),
            ("←", "back"),
            ("r", "check again"),
            ("q", "quit and finish later"),
        ],
        Screen::Wizard(_) => &[
            ("↵", "continue"),
            ("←", "back"),
            ("→", "skip"),
            ("r", "check again"),
            ("esc", "menu"),
            ("q", "quit"),
        ],
        Screen::Pair(view) if view.outcome.is_some() => &[("↵", "done")],
        Screen::Pair(_) => &[("esc", "cancel")],
        Screen::Devices(view) if view.confirm.is_some() => &[("y", "unpair"), ("any key", "keep")],
        Screen::Devices(_) => &[("↑↓", "move"), ("d", "unpair"), ("esc", "back")],
        Screen::Logs(_) => &[
            ("↑↓", "scroll"),
            ("pgup pgdn", "page"),
            ("g G", "top/end"),
            ("r", "reload"),
            ("esc", "back"),
        ],
        Screen::Help => &[("esc", "back")],
    };
    frame.render_widget(
        Line::from(
            hints
                .iter()
                .flat_map(|(k, w)| theme::key_hint(k, w))
                .collect::<Vec<_>>(),
        ),
        footer.inner(Margin::new(1, 0)),
    );

    let frame_no = app.frame();
    match &mut app.screen {
        Screen::Home => home(app, frame, body),
        Screen::Pair(view) => {
            let body = header(frame, body, "Pair a phone");
            pair_screen(frame, body, view, app.daemon.is_some(), frame_no);
        }
        Screen::Devices(view) => {
            let body = header(frame, body, "Paired phones");
            devices(frame, body, view, app.daemon.as_ref());
        }
        Screen::Logs(view) => {
            let body = header(frame, body, "Daemon log");
            logs(frame, body, view);
        }
        Screen::Wizard(_) => wizard::draw(app, frame, body),
        Screen::Check(checks) => {
            let body = header(frame, body, "System check");
            check_screen(frame, body, checks);
        }
        Screen::Help => {
            let body = header(frame, body, "Help");
            help(frame, body);
        }
    }

    if let Some(toast) = &app.toast {
        toast_box(frame, frame.area(), &toast.text, toast.error);
    }
}

/// A rounded panel with a title, in the theme's quiet border colour.
fn panel(title: &str) -> Block<'static> {
    Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::new().fg(theme::border()))
        .title(Line::from(vec![
            " ".into(),
            Span::from(title.to_string()).bold().fg(theme::accent()),
            " ".into(),
        ]))
        .padding(Padding::new(2, 2, 1, 0))
}

/// The one-line title used on every screen except home; returns the rest.
fn header(frame: &mut Frame, area: Rect, title: &str) -> Rect {
    let [top, rest] = Layout::vertical([Constraint::Length(2), Constraint::Min(0)]).areas(area);
    let mut line = theme::gradient_line("◆ YaeDock", true);
    line.spans.push(Span::styled(
        format!("  ›  {title}"),
        Style::new().fg(theme::muted()),
    ));
    frame.render_widget(line, top);
    rest
}

// ---------------------------------------------------------------- home

fn home(app: &mut App, frame: &mut Frame, area: Rect) {
    let big = area.height >= 32 && area.width >= 64;
    let [top, rest] = Layout::vertical([
        Constraint::Length(if big { 9 } else { 2 }),
        Constraint::Min(0),
    ])
    .areas(area);
    if big {
        let mut lines: Vec<Line> = vec![Line::from("")];
        lines.extend(LOGO.iter().map(|row| theme::gradient_line(row, true)));
        lines.push(Line::from(Span::styled(
            "your PC's stats and controls, on your phone",
            Style::new()
                .fg(theme::muted())
                .add_modifier(Modifier::ITALIC),
        )));
        frame.render_widget(Paragraph::new(lines).alignment(Alignment::Center), top);
    } else {
        let mut line = theme::gradient_line("◆ YaeDock", true);
        line.spans.push(Span::styled(
            "  your PC's stats and controls, on your phone",
            Style::new().fg(theme::muted()),
        ));
        frame.render_widget(line, top);
    }

    let menu_height = MENU.len() as u16 + 3;
    let daemon_height = app.daemon.as_ref().map_or(1, |d| d.phones.len().max(1)) as u16 + 5;
    if rest.width >= 96 {
        // Menu and its description on the left; daemon and live PC card on the right.
        let [left, right] = Layout::horizontal([Constraint::Length(40), Constraint::Min(0)])
            .spacing(1)
            .areas(rest);
        let [menu_area, about_area] =
            Layout::vertical([Constraint::Length(menu_height), Constraint::Min(0)]).areas(left);
        let [daemon_area, pc_area] =
            Layout::vertical([Constraint::Length(daemon_height), Constraint::Min(0)]).areas(right);
        menu(app, frame, menu_area);
        if about_area.height >= 4 {
            about_card(app, frame, about_area);
        }
        daemon_card(app, frame, daemon_area);
        pc_card(app, frame, pc_area);
    } else {
        let [menu_area, daemon_area, pc_area, about_area] = Layout::vertical([
            Constraint::Length(menu_height),
            Constraint::Length(daemon_height),
            Constraint::Length(9),
            Constraint::Min(0),
        ])
        .areas(rest);
        menu(app, frame, menu_area);
        daemon_card(app, frame, daemon_area);
        pc_card(app, frame, pc_area);
        if about_area.height >= 4 {
            about_card(app, frame, about_area);
        }
    }
}

fn menu_label(app: &App, item: MenuItem) -> (&'static str, String) {
    match item {
        MenuItem::Pair => ("◎", "Pair a phone".into()),
        MenuItem::Devices => ("⊞", format!("Paired phones  ({})", app.paired_count)),
        MenuItem::StartStop if app.daemon.is_some() => ("■", "Stop the daemon".into()),
        MenuItem::StartStop => ("▶", "Start the daemon".into()),
        MenuItem::StartAtLogin => (
            "↻",
            format!(
                "Start at login  {}",
                if app.service.is_some_and(|s| s.enabled) {
                    "on"
                } else {
                    "off"
                }
            ),
        ),
        MenuItem::GameFps => (
            "◷",
            format!(
                "Game FPS on phone  {}",
                if app.game_fps { "on" } else { "off" }
            ),
        ),
        MenuItem::Check => ("✓", "System check".into()),
        MenuItem::Logs => ("≡", "View logs".into()),
        MenuItem::Help => ("?", "Help".into()),
        MenuItem::Quit => ("✕", "Quit".into()),
    }
}

fn menu(app: &mut App, frame: &mut Frame, area: Rect) {
    let block = panel("Menu").padding(Padding::new(0, 1, 1, 0));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let rows = Rect {
        height: inner.height.min(MENU.len() as u16),
        ..inner
    };
    app.menu_rows = rows;
    for (i, item) in MENU.iter().enumerate() {
        if i as u16 >= rows.height {
            break;
        }
        let row = Rect {
            y: rows.y + i as u16,
            height: 1,
            ..rows
        };
        let selected = i == app.selected;
        let (icon, label) = menu_label(app, *item);
        let base = if selected {
            Style::new().bg(theme::selection())
        } else {
            Style::new()
        };
        let label_style = if selected {
            base.fg(Color::White).bold()
        } else {
            base
        };
        let icon_color = match item {
            MenuItem::StartStop if app.daemon.is_some() => theme::bad(),
            MenuItem::StartStop => theme::good(),
            MenuItem::Quit => theme::muted(),
            _ => theme::gradient(i as f32 / (MENU.len() - 1) as f32),
        };
        let mut spans = vec![
            Span::styled(if selected { "▌ " } else { "  " }, base.fg(theme::accent())),
            Span::styled(format!("{icon}  "), base.fg(icon_color)),
            Span::styled(label.clone(), label_style),
        ];
        let used: usize = 2 + 3 + label.chars().count();
        let number = format!("{} ", i + 1);
        let pad = (row.width as usize).saturating_sub(used + number.len());
        spans.push(Span::styled(" ".repeat(pad), base));
        spans.push(Span::styled(number, base.fg(theme::muted())));
        frame.render_widget(Line::from(spans), row);
    }
}

fn daemon_card(app: &App, frame: &mut Frame, area: Rect) {
    let mut lines = Vec::new();
    match &app.daemon {
        Some(d) => {
            let how = if app.service.is_some_and(|s| s.running) {
                "in the background"
            } else {
                "in a terminal"
            };
            let up = pairing::now().saturating_sub(d.started_at);
            lines.push(Line::from(vec![
                theme::pulse(app.frame(), theme::good()),
                " Running ".bold().fg(theme::good()),
                Span::styled(
                    format!("{how}  ·  wss://{}  ·  up {}", d.listen, duration(up)),
                    Style::new().fg(theme::muted()),
                ),
            ]));
            lines.push(Line::from(""));
            if d.phones.is_empty() {
                lines.push(Line::from(Span::styled(
                    "No phones connected",
                    Style::new().fg(theme::muted()),
                )));
            }
            for phone in &d.phones {
                let host = phone
                    .address
                    .rsplit_once(':')
                    .map_or(phone.address.as_str(), |(h, _)| h);
                lines.push(Line::from(vec![
                    Span::styled("◆ ", Style::new().fg(theme::violet())),
                    phone.name.clone().bold(),
                    Span::styled(
                        format!(
                            "   {host}  ·  connected {}",
                            duration(pairing::now().saturating_sub(phone.connected_at))
                        ),
                        Style::new().fg(theme::muted()),
                    ),
                ]));
            }
        }
        None => {
            lines.push(Line::from(vec![
                "○".fg(theme::bad()),
                " Not running".bold().fg(theme::bad()),
            ]));
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                "Choose \"Start the daemon\" to run it in the background.",
                Style::new().fg(theme::muted()),
            )));
        }
    }
    frame.render_widget(Paragraph::new(lines).block(panel("Daemon")), area);
}

fn pc_card(app: &App, frame: &mut Frame, area: Rect) {
    let block = panel("This PC");
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let stats = &app.local.latest;
    let label = |text: &str| Span::styled(format!("{text:<6}"), Style::new().fg(theme::muted()));

    // Charts share whatever height the card has; the gauge and readings stay one row.
    let [cpu_row, net_row, _, mem_row, misc_row] = Layout::vertical([
        Constraint::Fill(1),
        Constraint::Fill(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
    ])
    .spacing(0)
    .areas(inner);

    let cpu = stats.cpu.as_ref().map_or(0.0, |c| c.total);
    chart_row(
        frame,
        cpu_row,
        Line::from(vec![
            label("CPU"),
            Span::styled(format!("{cpu:>3.0}%"), Style::new().bold()),
        ]),
        &app.local.cpu,
        Some(100),
        theme::accent(),
    );
    let (rx, tx) = stats.net.as_ref().map_or((0, 0), |n| (n.rx, n.tx));
    chart_row(
        frame,
        net_row,
        Line::from(vec![
            label("Net"),
            Span::styled(format!("↓ {}", rate(rx)), Style::new().bold()),
        ]),
        &app.local.net,
        None,
        theme::violet(),
    );
    // The upload rate sits under the download rate, beside the network chart.
    if net_row.height >= 2 {
        frame.render_widget(
            Line::from(vec![
                "      ".into(),
                Span::styled(format!("↑ {}", rate(tx)), Style::new().bold()),
            ]),
            Rect {
                y: net_row.y + 1,
                height: 1,
                width: 20.min(net_row.width),
                ..net_row
            },
        );
    }

    if let Some(mem) = &stats.mem {
        let ratio = if mem.total > 0 {
            mem.used as f64 / mem.total as f64
        } else {
            0.0
        };
        let [mem_label, mem_gauge] =
            Layout::horizontal([Constraint::Length(20), Constraint::Min(0)]).areas(mem_row);
        frame.render_widget(
            Line::from(vec![
                label("RAM"),
                Span::styled(format!("{:>3.0}%", ratio * 100.0), Style::new().bold()),
            ]),
            mem_label,
        );
        frame.render_widget(
            LineGauge::default()
                .ratio(ratio.clamp(0.0, 1.0))
                .label(Span::styled(
                    format!("{} of {}", gib(mem.used), gib(mem.total)),
                    Style::new().fg(theme::muted()),
                ))
                .filled_style(Style::new().fg(theme::good()))
                .unfilled_style(Style::new().fg(theme::border())),
            mem_gauge,
        );
    }

    if let Some(t) = app.local.temperature() {
        frame.render_widget(
            Line::from(vec![
                label("Temp"),
                Span::styled(
                    format!("{t:>3.0}°C"),
                    Style::new().bold().fg(theme::temperature(t)),
                ),
            ]),
            misc_row,
        );
    }
}

/// A reading on the left and its recent history as a sparkline on the right.
fn chart_row(
    frame: &mut Frame,
    area: Rect,
    reading: Line<'static>,
    history: &std::collections::VecDeque<u64>,
    max: Option<u64>,
    color: Color,
) {
    if area.height == 0 {
        return;
    }
    let [left, chart] =
        Layout::horizontal([Constraint::Length(20), Constraint::Min(0)]).areas(area);
    frame.render_widget(reading, Rect { height: 1, ..left });
    let data: Vec<u64> = history.iter().copied().collect();
    let skip = data.len().saturating_sub(chart.width as usize);
    let mut sparkline = Sparkline::default()
        .data(&data[skip..])
        .style(Style::new().fg(color));
    if let Some(max) = max {
        sparkline = sparkline.max(max);
    }
    frame.render_widget(sparkline, chart);
}

fn about_card(app: &App, frame: &mut Frame, area: Rect) {
    let item = MENU[app.selected];
    let text = match item {
        MenuItem::Pair => {
            "Shows a QR code for connecting a new phone. Open YaeDock on the phone, tap Scan QR code, and point it at the screen."
        }
        MenuItem::Devices => {
            "Lists the phones paired with this PC and which are online now. Unpair the ones you no longer use."
        }
        MenuItem::StartStop if app.daemon.is_some() => {
            "Stops the daemon. Connected phones will show this PC as unreachable until it starts again."
        }
        MenuItem::StartStop => {
            "Runs the daemon in the background as a systemd user service, so phones can connect."
        }
        MenuItem::StartAtLogin => {
            "Starts the daemon automatically every time you log in, so your phone always finds this PC."
        }
        MenuItem::GameFps => {
            "Shows the frame rate of games started with MangoHud on the phone. Turning it on adds three marked lines to your MangoHud config; turning it off removes them."
        }
        MenuItem::Check => {
            "Checks which features work on this PC (stats, temperatures, brightness, volume, network, firewall) and how to fix the rest. Changes nothing."
        }
        MenuItem::Logs => {
            "Shows what the daemon has been doing. Useful when a phone cannot connect."
        }
        MenuItem::Help => "Commands, keys, and fixes for common problems.",
        MenuItem::Quit => "Leaves this menu. A daemon running in the background keeps running.",
    };
    frame.render_widget(
        Paragraph::new(Span::styled(text, Style::new().fg(theme::muted())))
            .wrap(Wrap { trim: true })
            .block(panel(&menu_label(app, item).1)),
        area,
    );
}

// ---------------------------------------------------------------- pairing

pub(super) fn pair_screen(
    frame: &mut Frame,
    area: Rect,
    view: &PairView,
    daemon_running: bool,
    frame_no: u64,
) {
    if let Some(outcome) = &view.outcome {
        let lines = match outcome {
            Ok(device) => vec![
                theme::gradient_line("✓  Paired", true),
                Line::from(""),
                Line::from(vec![
                    "Connected to ".into(),
                    device.clone().bold().fg(theme::violet()),
                ]),
                Line::from(""),
                Line::from(Span::styled(
                    "It will reconnect by itself from now on.",
                    Style::new().fg(theme::muted()),
                )),
            ],
            Err(why) => vec![
                Line::from("✗  Pairing did not finish".bold().fg(theme::bad())),
                Line::from(""),
                Line::from(why.as_str()),
                Line::from(""),
                Line::from(Span::styled(
                    "Press Enter, then choose Pair a phone to try again.",
                    Style::new().fg(theme::muted()),
                )),
            ],
        };
        let box_area = center(area, 52, 9);
        frame.render_widget(
            Paragraph::new(lines)
                .alignment(Alignment::Center)
                .block(panel("Pairing")),
            box_area,
        );
        return;
    }

    let qr: Vec<&str> = view.qr.lines().collect();
    let qr_h = qr.len() as u16;
    let qr_w = qr.iter().map(|l| l.chars().count()).max().unwrap_or(0) as u16;

    let left = view.pairing.expires.saturating_sub(pairing::now());
    let code = format!("{}  {}", &view.pairing.code[..3], &view.pairing.code[3..]);
    let mut info = vec![
        Line::from("Scan this with the YaeDock app".bold()),
        Line::from(Span::styled(
            "Open the app on your phone and tap Scan QR code.",
            Style::new().fg(theme::muted()),
        )),
        Line::from(""),
        Line::from(vec![
            Span::styled("Code         ", Style::new().fg(theme::muted())),
            Span::styled(code, Style::new().fg(theme::accent()).bold()),
        ]),
        Line::from(vec![
            Span::styled("Address      ", Style::new().fg(theme::muted())),
            format!("{}:{}", view.host, view.port).into(),
        ]),
        Line::from(vec![
            Span::styled("Fingerprint  ", Style::new().fg(theme::muted())),
            Span::styled(
                pair::group(&view.fingerprint),
                Style::new().fg(theme::muted()),
            ),
        ]),
        Line::from(""),
        Line::from(vec![
            Span::styled(
                SPINNER[(frame_no % SPINNER.len() as u64) as usize],
                Style::new().fg(theme::accent()),
            ),
            " Waiting for your phone…".into(),
        ]),
    ];
    if !daemon_running {
        info.push(Line::from(""));
        info.push(Line::from(Span::styled(
            "The daemon is not running, so the phone cannot connect yet. Start it from the menu first.",
            Style::new().fg(theme::warn()),
        )));
    }
    let gauge = LineGauge::default()
        .ratio(left as f64 / CODE_LIFETIME.as_secs() as f64)
        .label(Span::styled(
            format!("{}:{:02} left", left / 60, left % 60),
            Style::new().fg(theme::muted()),
        ))
        .filled_style(Style::new().fg(if left > 60 {
            theme::accent()
        } else {
            theme::warn()
        }))
        .unfilled_style(Style::new().fg(theme::border()));

    let text_h = info.len() as u16 + 2;
    let side_by_side = area.width >= qr_w + 4 + 50 && area.height >= qr_h;
    let stacked = area.width >= qr_w + 2 && area.height >= qr_h + text_h;
    if side_by_side || stacked {
        let (qr_area, text_area) = if side_by_side {
            let [q, t] = Layout::horizontal([Constraint::Length(qr_w + 4), Constraint::Min(0)])
                .spacing(2)
                .flex(Flex::Center)
                .areas(area);
            (center(q, qr_w, qr_h), center_v(t, text_h))
        } else {
            let [q, t] =
                Layout::vertical([Constraint::Length(qr_h), Constraint::Min(0)]).areas(area);
            (center(q, qr_w, qr_h), t)
        };
        // Dark modules on a white card, the way round phones read most reliably.
        frame.render_widget(
            Paragraph::new(qr.iter().map(|l| Line::from(*l)).collect::<Vec<_>>())
                .style(Style::new().fg(Color::Black).bg(Color::White)),
            qr_area,
        );
        let [text, bar] = Layout::vertical([
            Constraint::Length(info.len() as u16 + 1),
            Constraint::Length(1),
        ])
        .areas(text_area);
        frame.render_widget(Paragraph::new(info).wrap(Wrap { trim: false }), text);
        frame.render_widget(
            gauge,
            Rect {
                width: bar.width.min(48),
                ..bar
            },
        );
    } else {
        info.insert(
            0,
            Line::from(Span::styled(
                "Make the terminal bigger to show the QR code, or type these details in the app.",
                Style::new().fg(theme::warn()),
            )),
        );
        info.insert(1, Line::from(""));
        let [text, bar] = Layout::vertical([
            Constraint::Length(info.len() as u16 + 1),
            Constraint::Length(1),
        ])
        .areas(area);
        frame.render_widget(Paragraph::new(info).wrap(Wrap { trim: false }), text);
        frame.render_widget(gauge, bar);
    }
}

// ---------------------------------------------------------------- devices

fn devices(
    frame: &mut Frame,
    area: Rect,
    view: &mut DevicesView,
    daemon: Option<&yaedock_core::control::DaemonStatus>,
) {
    if view.devices.is_empty() {
        frame.render_widget(
            Paragraph::new(vec![
                Line::from("No phones are paired yet.".bold()),
                Line::from(""),
                Line::from(Span::styled(
                    "Go back and choose Pair a phone to add one.",
                    Style::new().fg(theme::muted()),
                )),
            ])
            .block(panel("Paired phones")),
            area,
        );
        return;
    }
    let now = pairing::now();
    let rows = view.devices.iter().enumerate().map(|(i, device)| {
        let online = daemon.is_some_and(|d| d.phones.iter().any(|p| p.device_id == device.id));
        let days = now.saturating_sub(device.paired_at) / 86_400;
        let zebra = if i % 2 == 1 {
            Style::new().bg(theme::zebra())
        } else {
            Style::new()
        };
        Row::new(vec![
            Line::from(if online {
                Span::styled("● online", Style::new().fg(theme::good()).bold())
            } else {
                Span::styled("○ offline", Style::new().fg(theme::muted()))
            }),
            Line::from(device.name.clone().bold()),
            Line::from(match days {
                0 => "today".to_string(),
                1 => "yesterday".to_string(),
                n => format!("{n} days ago"),
            }),
            Line::from(Span::styled(
                device.id.clone(),
                Style::new().fg(theme::muted()),
            )),
        ])
        .style(zebra)
        .height(1)
    });
    let count = view.devices.len();
    let table = Table::new(
        rows,
        [
            Constraint::Length(10),
            Constraint::Percentage(35),
            Constraint::Length(12),
            Constraint::Min(10),
        ],
    )
    .column_spacing(2)
    .header(
        Row::new(vec!["Status", "Name", "Paired", "Device id"])
            .style(
                Style::new()
                    .fg(theme::muted())
                    .add_modifier(Modifier::UNDERLINED),
            )
            .bottom_margin(1),
    )
    .row_highlight_style(Style::new().bg(theme::selection()).fg(Color::White))
    .highlight_symbol(Span::styled("▌ ", Style::new().fg(theme::accent())))
    .block(panel(&format!("Paired phones  ·  {count}")));
    frame.render_stateful_widget(table, area, &mut view.table);

    if let Some(row) = view.confirm {
        let device = &view.devices[row];
        let popup = center(area, 56, 8);
        frame.render_widget(Clear, popup);
        frame.render_widget(
            Paragraph::new(vec![
                Line::from(vec![
                    "Unpair ".into(),
                    device.name.clone().bold(),
                    "?".into(),
                ]),
                Line::from(""),
                Line::from(Span::styled(
                    "It will need to scan a new code to connect again.",
                    Style::new().fg(theme::muted()),
                )),
                Line::from(""),
                Line::from(vec![
                    Span::styled(" y ", Style::new().fg(Color::Black).bg(theme::bad()).bold()),
                    " unpair     ".into(),
                    Span::styled(
                        " any key ",
                        Style::new().fg(Color::Black).bg(theme::muted()).bold(),
                    ),
                    " keep".into(),
                ]),
            ])
            .alignment(Alignment::Center)
            .block(
                Block::bordered()
                    .border_type(BorderType::Double)
                    .border_style(Style::new().fg(theme::bad()))
                    .title(" Unpair ".bold().fg(theme::bad()))
                    .padding(Padding::uniform(1)),
            ),
            popup,
        );
    }
}

// ---------------------------------------------------------------- logs

fn logs(frame: &mut Frame, area: Rect, view: &LogsView) {
    let block = panel(&format!("Daemon log  ·  {} lines", view.lines.len()))
        .padding(Padding::horizontal(1));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    if view.lines.is_empty() {
        frame.render_widget(
            Paragraph::new(vec![
                Line::from("No log from the background service yet.".bold()),
                Line::from(""),
                Line::from(Span::styled(
                    "Logs show here once the daemon runs in the background. A daemon started with `yaedock daemon` prints its log in its own terminal.",
                    Style::new().fg(theme::muted()),
                )),
            ])
            .wrap(Wrap { trim: true }),
            inner,
        );
        return;
    }
    let height = inner.height as usize;
    let end = view.lines.len().saturating_sub(view.scroll);
    let start = end.saturating_sub(height);
    let lines: Vec<Line> = view.lines[start..end].iter().map(|l| log_line(l)).collect();
    frame.render_widget(Paragraph::new(Text::from(lines)), inner);
    let mut scrollbar =
        ScrollbarState::new(view.lines.len().saturating_sub(height)).position(start);
    frame.render_stateful_widget(
        Scrollbar::new(ScrollbarOrientation::VerticalRight)
            .thumb_style(Style::new().fg(theme::accent()))
            .track_style(Style::new().fg(theme::border())),
        area.inner(Margin::new(0, 1)),
        &mut scrollbar,
    );
}

/// Colours a journal line: the timestamp and source quiet, the message by level.
fn log_line(line: &str) -> Line<'static> {
    // short-iso: "2026-10-19T10:00:00+05:30 host yaedockd[123]: message"
    let (prefix, message) = match line.find("]: ") {
        Some(i) => (&line[..i + 3], &line[i + 3..]),
        None => ("", line),
    };
    let time = prefix.split_whitespace().next().unwrap_or("");
    let time = time.get(11..19).unwrap_or(time);
    let color = if message.contains("ERROR") {
        theme::bad()
    } else if message.contains("WARN") {
        theme::warn()
    } else {
        Color::Reset
    };
    Line::from(vec![
        Span::styled(format!("{time}  "), Style::new().fg(theme::muted())),
        Span::styled(message.to_string(), Style::new().fg(color)),
    ])
}

// ---------------------------------------------------------------- system check

fn check_screen(frame: &mut Frame, area: Rect, checks: &[crate::check::Check]) {
    use crate::check::Outcome;
    let count = |o| checks.iter().filter(|c| c.outcome == o).count();
    let separator = || Span::styled("  ·  ", Style::new().fg(theme::muted()));
    let mut lines = vec![
        Line::from(vec![
            Span::styled(
                format!("{} working", count(Outcome::Ok)),
                Style::new().fg(theme::good()).bold(),
            ),
            separator(),
            Span::styled(
                format!("{} with limits", count(Outcome::Warn)),
                Style::new().fg(theme::warn()).bold(),
            ),
            separator(),
            Span::styled(
                format!("{} not working", count(Outcome::Fail)),
                Style::new().fg(theme::bad()).bold(),
            ),
        ]),
        Line::from(""),
    ];
    for check in checks {
        let (mark, color) = match check.outcome {
            Outcome::Ok => ("✓", theme::good()),
            Outcome::Warn => ("!", theme::warn()),
            Outcome::Fail => ("✗", theme::bad()),
        };
        lines.push(Line::from(vec![
            Span::styled(format!("{mark}  "), Style::new().fg(color).bold()),
            Span::styled(format!("{:<14}", check.area), Style::new().bold()),
            check.summary.clone().into(),
        ]));
        if let Some(fix) = &check.fix {
            lines.push(Line::from(vec![
                " ".repeat(17).into(),
                Span::styled(fix.clone(), Style::new().fg(theme::muted())),
            ]));
        }
    }
    frame.render_widget(
        Paragraph::new(lines)
            .wrap(Wrap { trim: false })
            .block(panel("This PC")),
        area,
    );
}

// ---------------------------------------------------------------- help

fn help(frame: &mut Frame, area: Rect) {
    let heading =
        |s: &'static str| Line::from(Span::styled(s, Style::new().fg(theme::accent()).bold()));
    let muted = |s: &'static str| Line::from(Span::styled(s, Style::new().fg(theme::muted())));
    let step = |n: &'static str, s: &'static str| {
        Line::from(vec![
            Span::styled(
                format!(" {n} "),
                Style::new().fg(Color::Black).bg(theme::accent()).bold(),
            ),
            format!("  {s}").into(),
        ])
    };
    let command = |c: &'static str, s: &'static str| {
        Line::from(vec![
            Span::styled(format!("{c:<26}"), Style::new().bold()),
            Span::styled(s, Style::new().fg(theme::muted())),
        ])
    };

    let start = vec![
        heading("What YaeDock is"),
        muted(
            "The YaeDock daemon runs on this PC and streams its stats to the YaeDock app on your phone, which can also change this PC's brightness and volume.",
        ),
        Line::from(""),
        heading("Getting started"),
        step("1", "Start the daemon"),
        step("2", "Pair a phone and scan the code"),
        step("3", "Turn on Start at login"),
        Line::from(""),
        heading("If the phone cannot connect"),
        muted("Both need the same Wi-Fi network. If a firewall is on, allow TCP port 9481:"),
        Line::from("sudo ufw allow 9481/tcp".bold()),
    ];
    let commands = vec![
        heading("Commands"),
        command("yaedock", "this menu"),
        command("yaedock setup", "the setup guide"),
        command("yaedock --uninstall", "remove YaeDock from this PC"),
        command("yaedock status", "daemon and connected phones"),
        command("yaedock check", "what works on this PC"),
        command("yaedock pair", "pair from the command line"),
        command("yaedock devices", "list paired phones"),
        command("yaedock unpair ID", "remove a paired phone"),
        command("yaedock service start", "run in the background"),
        command("yaedock service enable", "also start at login"),
        command("yaedock service logs", "follow the daemon's log"),
        command("yaedock daemon", "run it in this terminal"),
        Line::from(""),
        heading("Keys"),
        command("↑ ↓  or  j k", "move"),
        command("Enter  or  click", "choose"),
        command("Esc  or  q", "back, or quit"),
    ];
    if area.width >= 110 {
        let [left, right] =
            Layout::horizontal([Constraint::Percentage(45), Constraint::Percentage(55)])
                .spacing(1)
                .areas(area);
        frame.render_widget(
            Paragraph::new(start)
                .wrap(Wrap { trim: true })
                .block(panel("Start here")),
            left,
        );
        frame.render_widget(Paragraph::new(commands).block(panel("Reference")), right);
    } else {
        let mut all = start;
        all.push(Line::from(""));
        all.extend(commands);
        frame.render_widget(
            Paragraph::new(all)
                .wrap(Wrap { trim: true })
                .block(panel("Help")),
            area,
        );
    }
}

// ---------------------------------------------------------------- pieces

fn toast_box(frame: &mut Frame, area: Rect, text: &str, error: bool) {
    let color = if error { theme::bad() } else { theme::good() };
    let width = (text.chars().count() as u16 + 8).min(area.width.saturating_sub(2));
    let rect = Rect {
        x: area.right().saturating_sub(width + 1),
        y: area.bottom().saturating_sub(4),
        width,
        height: 3,
    };
    frame.render_widget(Clear, rect);
    frame.render_widget(
        Paragraph::new(Line::from(vec![
            Span::styled(
                if error { "✗ " } else { "✓ " },
                Style::new().fg(color).bold(),
            ),
            text.into(),
        ]))
        .block(
            Block::new()
                .borders(Borders::ALL)
                .border_type(BorderType::Rounded)
                .border_style(Style::new().fg(color))
                .padding(Padding::horizontal(1)),
        ),
        rect,
    );
}

fn center(area: Rect, width: u16, height: u16) -> Rect {
    let [h] = Layout::horizontal([Constraint::Length(width.min(area.width))])
        .flex(Flex::Center)
        .areas(area);
    let [v] = Layout::vertical([Constraint::Length(height.min(area.height))])
        .flex(Flex::Center)
        .areas(h);
    v
}

fn center_v(area: Rect, height: u16) -> Rect {
    let [v] = Layout::vertical([Constraint::Length(height.min(area.height))])
        .flex(Flex::Center)
        .areas(area);
    v
}

fn duration(seconds: u64) -> String {
    let (d, h, m) = (
        seconds / 86_400,
        seconds % 86_400 / 3_600,
        seconds % 3_600 / 60,
    );
    match (d, h, m) {
        (0, 0, 0) => format!("{seconds}s"),
        (0, 0, _) => format!("{m}m"),
        (0, _, _) => format!("{h}h {m}m"),
        _ => format!("{d}d {h}h"),
    }
}

fn gib(bytes: u64) -> String {
    format!("{:.1} GiB", bytes as f64 / (1u64 << 30) as f64)
}

fn rate(bytes: u64) -> String {
    match bytes {
        b if b >= 1 << 20 => format!("{:.1} MiB/s", b as f64 / (1u64 << 20) as f64),
        b if b >= 1 << 10 => format!("{:.0} KiB/s", b as f64 / 1024.0),
        b => format!("{b} B/s"),
    }
}
