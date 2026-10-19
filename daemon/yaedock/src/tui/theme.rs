//! Colours, the logo, and small animated touches for the terminal menu.

use std::sync::OnceLock;

use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};

/// The ANSI Shadow lettering of "YAEDOCK".
pub const LOGO: [&str; 6] = [
    "██╗   ██╗ █████╗ ███████╗██████╗  ██████╗  ██████╗██╗  ██╗",
    "╚██╗ ██╔╝██╔══██╗██╔════╝██╔══██╗██╔═══██╗██╔════╝██║ ██╔╝",
    " ╚████╔╝ ███████║█████╗  ██║  ██║██║   ██║██║     █████╔╝ ",
    "  ╚██╔╝  ██╔══██║██╔══╝  ██║  ██║██║   ██║██║     ██╔═██╗ ",
    "   ██║   ██║  ██║███████╗██████╔╝╚██████╔╝╚██████╗██║  ██╗",
    "   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═════╝  ╚═════╝  ╚═════╝╚═╝  ╚═╝",
];

pub const SPINNER: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

/// Whether the terminal shows 24-bit colour; otherwise the 16 named colours are used.
fn truecolor() -> bool {
    static TRUECOLOR: OnceLock<bool> = OnceLock::new();
    *TRUECOLOR.get_or_init(|| {
        std::env::var("COLORTERM").is_ok_and(|v| v.contains("truecolor") || v.contains("24bit"))
    })
}

fn rgb(r: u8, g: u8, b: u8, fallback: Color) -> Color {
    if truecolor() {
        Color::Rgb(r, g, b)
    } else {
        fallback
    }
}

pub fn accent() -> Color {
    rgb(94, 214, 255, Color::Cyan)
}
pub fn violet() -> Color {
    rgb(176, 132, 255, Color::Magenta)
}
pub fn good() -> Color {
    rgb(105, 219, 124, Color::Green)
}
pub fn warn() -> Color {
    rgb(255, 196, 87, Color::Yellow)
}
pub fn bad() -> Color {
    rgb(255, 107, 107, Color::Red)
}
/// Main text, set explicitly where a parent style (such as a border) would
/// otherwise colour it.
pub fn text() -> Color {
    rgb(220, 226, 236, Color::White)
}

/// Secondary text. Light grey in 16-colour terminals, where dark grey is too faint.
pub fn muted() -> Color {
    rgb(140, 148, 166, Color::Gray)
}
pub fn border() -> Color {
    rgb(64, 70, 86, Color::DarkGray)
}
/// The background of every other table row.
pub fn zebra() -> Color {
    rgb(26, 30, 40, Color::Reset)
}
/// The background of the selected row.
/// In 16-colour terminals there is no shade subtle enough, so the selection is
/// shown by the marker and bold text alone.
pub fn selection() -> Color {
    rgb(38, 48, 68, Color::Reset)
}

/// The background of key badges.
pub fn badge() -> Color {
    rgb(56, 63, 80, Color::DarkGray)
}

/// The colour `t` of the way (0 to 1) from cyan to violet.
pub fn gradient(t: f32) -> Color {
    let t = t.clamp(0.0, 1.0);
    if !truecolor() {
        return if t < 0.5 { Color::Cyan } else { Color::Magenta };
    }
    let mix = |a: u8, b: u8| (a as f32 + (b as f32 - a as f32) * t).round() as u8;
    Color::Rgb(mix(94, 176), mix(214, 132), mix(255, 255))
}

/// `text` with each character coloured along the cyan-to-violet gradient.
pub fn gradient_line(text: &str, bold: bool) -> Line<'static> {
    let count = text.chars().count().max(2) as f32 - 1.0;
    Line::from(
        text.chars()
            .enumerate()
            .map(|(i, c)| {
                let style = Style::new().fg(gradient(i as f32 / count));
                Span::styled(
                    c.to_string(),
                    if bold {
                        style.add_modifier(Modifier::BOLD)
                    } else {
                        style
                    },
                )
            })
            .collect::<Vec<_>>(),
    )
}

/// A key hint such as ` ↵ choose`, the key drawn as a small badge.
pub fn key_hint(key: &str, what: &str) -> Vec<Span<'static>> {
    vec![
        Span::styled(
            format!(" {key} "),
            Style::new().fg(Color::White).bg(badge()).bold(),
        ),
        Span::styled(format!(" {what}   "), Style::new().fg(muted())),
    ]
}

/// A filled dot that gently pulses between two shades, for "running".
pub fn pulse(frame: u64, color: Color) -> Span<'static> {
    let bright = (frame / 3).is_multiple_of(2);
    let style = Style::new().fg(color);
    Span::styled(
        "●",
        if bright {
            style.bold()
        } else {
            style.add_modifier(Modifier::DIM)
        },
    )
}

/// Green below 60 °C, amber below 80 °C, red above, as in the phone app.
pub fn temperature(celsius: f32) -> Color {
    if celsius < 60.0 {
        good()
    } else if celsius < 80.0 {
        warn()
    } else {
        bad()
    }
}
