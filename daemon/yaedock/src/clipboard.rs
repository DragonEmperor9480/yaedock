//! Copying a command to the clipboard, so it can be pasted into another
//! terminal. The menu captures the mouse, so its text cannot be selected the
//! usual way.

use std::env;
use std::io::{Write, stdout};
use std::process::{Command, Stdio};

/// How the text reached the clipboard.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Copied {
    /// A clipboard tool or the desktop's clipboard took it.
    Done,
    /// Only the terminal was asked (OSC 52); whether it copied is unknown.
    Asked,
}

/// Puts `text` on the clipboard: through `wl-copy`, `xclip` or `xsel` when
/// one is installed, then KDE's Klipper over D-Bus, and last the terminal itself.
pub fn copy(text: &str) -> Copied {
    let wayland = env::var_os("WAYLAND_DISPLAY").is_some();
    let x11 = env::var_os("DISPLAY").is_some();
    let tools: [(bool, &str, &[&str]); 3] = [
        (wayland, "wl-copy", &[]),
        (x11, "xclip", &["-selection", "clipboard"]),
        (x11, "xsel", &["--clipboard", "--input"]),
    ];
    for (usable, tool, args) in tools {
        if usable && pipe_to(tool, args, text) {
            return Copied::Done;
        }
    }
    // Plasma's clipboard, through busctl, which comes with systemd.
    let klipper = Command::new("busctl")
        .args([
            "--user",
            "call",
            "org.kde.klipper",
            "/klipper",
            "org.kde.klipper.klipper",
            "setClipboardContents",
            "s",
            text,
        ])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success());
    if klipper {
        return Copied::Done;
    }
    let mut out = stdout();
    let _ = write!(out, "{}", osc52(text));
    let _ = out.flush();
    Copied::Asked
}

/// Runs `tool` with `text` on its standard input; true if it succeeded.
fn pipe_to(tool: &str, args: &[&str], text: &str) -> bool {
    let Ok(mut child) = Command::new(tool)
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return false;
    };
    let written = child
        .stdin
        .take()
        .is_some_and(|mut stdin| stdin.write_all(text.as_bytes()).is_ok());
    child.wait().is_ok_and(|s| s.success()) && written
}

/// The escape sequence asking the terminal to put `text` on the clipboard.
fn osc52(text: &str) -> String {
    format!("\x1b]52;c;{}\x07", base64(text.as_bytes()))
}

fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |n, (i, b)| n | (u32::from(*b) << (16 - 8 * i)));
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(ALPHABET[(n >> (18 - 6 * i) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_base64_with_padding() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(
            base64(b"sudo ufw allow 5353/udp"),
            "c3VkbyB1ZncgYWxsb3cgNTM1My91ZHA="
        );
    }

    #[test]
    fn wraps_text_in_osc52() {
        assert_eq!(osc52("hi"), "\x1b]52;c;aGk=\x07");
    }
}
