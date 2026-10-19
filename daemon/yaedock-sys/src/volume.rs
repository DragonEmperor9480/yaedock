//! System volume backends for the default audio output.

use std::io::{self, BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use yaedock_proto::VolumeState;

use crate::ControlError;

/// The highest volume the phone can set, in percent.
pub const MAX_PERCENT: u8 = 150;

/// Reads and changes the volume of the default audio output.
pub trait VolumeBackend: Send + Sync {
    /// What this backend uses, for reports such as `yaedock check`.
    fn name(&self) -> &'static str;

    fn get(&self) -> Result<VolumeState, ControlError>;

    /// Changes the volume (0 to 150 percent), the mute flag, or both.
    fn set(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError>;

    /// Starts watching for volume changes made anywhere, calling `changed` for
    /// each. Returns `None` if changes cannot be watched; callers then poll.
    fn watch(&self, changed: Box<dyn Fn() + Send>) -> Option<Watcher> {
        pactl_watch(changed)
    }
}

/// Whether volume changes can be watched as they happen (`pactl subscribe`);
/// without it the daemon checks the volume every second instead.
pub fn can_watch() -> bool {
    Command::new("pactl")
        .arg("--version")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

/// A running volume watcher. Dropping it stops the watch.
pub struct Watcher {
    child: Child,
    alive: Arc<AtomicBool>,
}

impl Watcher {
    /// False once the watch has ended, for example because the audio server restarted.
    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::Relaxed)
    }
}

impl Drop for Watcher {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Watches with `pactl subscribe`, which works with PulseAudio and with
/// PipeWire's pulse server, and reports changes to sinks (volume, mute) and to
/// the server (the default sink). Stream and client events are ignored: they
/// are frequent, and reading the volume itself creates client events.
fn pactl_watch(changed: Box<dyn Fn() + Send>) -> Option<Watcher> {
    let mut child = Command::new("pactl")
        .arg("subscribe")
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let stdout = child.stdout.take()?;
    let alive = Arc::new(AtomicBool::new(true));
    let thread_alive = alive.clone();
    thread::Builder::new()
        .name("pactl-subscribe".into())
        .spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let Ok(line) = line else { break };
                if is_volume_event(&line) {
                    changed();
                }
            }
            thread_alive.store(false, Ordering::Relaxed);
        })
        .ok()?;
    Some(Watcher { child, alive })
}

fn is_volume_event(line: &str) -> bool {
    line.starts_with("Event 'change' on sink #") || line.starts_with("Event 'change' on server")
}

/// Picks the first volume backend that works on this system: PipeWire through
/// `wpctl`, then PulseAudio (or pipewire-pulse) through `pactl`.
pub fn detect() -> Option<Box<dyn VolumeBackend>> {
    if WpctlBackend.get().is_ok() {
        Some(Box::new(WpctlBackend))
    } else if PactlBackend.get().is_ok() {
        Some(Box::new(PactlBackend))
    } else {
        None
    }
}

/// PipeWire through `wpctl` (WirePlumber).
pub struct WpctlBackend;

const WPCTL_SINK: &str = "@DEFAULT_AUDIO_SINK@";

impl VolumeBackend for WpctlBackend {
    fn name(&self) -> &'static str {
        "PipeWire (wpctl)"
    }

    fn get(&self) -> Result<VolumeState, ControlError> {
        let out = run("wpctl", &["get-volume", WPCTL_SINK])?;
        parse_wpctl(&out).ok_or_else(|| bad_output("wpctl", &out))
    }

    fn set(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
        if let Some(percent) = percent {
            let volume = format!("{:.2}", percent.min(MAX_PERCENT) as f32 / 100.0);
            run("wpctl", &["set-volume", WPCTL_SINK, &volume])?;
        }
        if let Some(muted) = muted {
            run(
                "wpctl",
                &["set-mute", WPCTL_SINK, if muted { "1" } else { "0" }],
            )?;
        }
        Ok(())
    }
}

/// PulseAudio, or PipeWire through pipewire-pulse, via `pactl`.
pub struct PactlBackend;

const PACTL_SINK: &str = "@DEFAULT_SINK@";

impl VolumeBackend for PactlBackend {
    fn name(&self) -> &'static str {
        "PulseAudio (pactl)"
    }

    fn get(&self) -> Result<VolumeState, ControlError> {
        let volume = run("pactl", &["get-sink-volume", PACTL_SINK])?;
        let mute = run("pactl", &["get-sink-mute", PACTL_SINK])?;
        let percent = parse_pactl_volume(&volume).ok_or_else(|| bad_output("pactl", &volume))?;
        let muted = match mute.trim() {
            "Mute: yes" => true,
            "Mute: no" => false,
            _ => return Err(bad_output("pactl", &mute)),
        };
        Ok(VolumeState { percent, muted })
    }

    fn set(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
        if let Some(percent) = percent {
            let volume = format!("{}%", percent.min(MAX_PERCENT));
            run("pactl", &["set-sink-volume", PACTL_SINK, &volume])?;
        }
        if let Some(muted) = muted {
            run(
                "pactl",
                &["set-sink-mute", PACTL_SINK, if muted { "1" } else { "0" }],
            )?;
        }
        Ok(())
    }
}

/// Runs a command in the C locale and returns its stdout.
fn run(program: &str, args: &[&str]) -> io::Result<String> {
    let output = Command::new(program)
        .args(args)
        .env("LC_ALL", "C")
        .output()?;
    if !output.status.success() {
        return Err(io::Error::other(format!(
            "{program} {}: {}",
            args.join(" "),
            String::from_utf8_lossy(&output.stderr).trim()
        )));
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

fn bad_output(program: &str, output: &str) -> ControlError {
    io::Error::new(
        io::ErrorKind::InvalidData,
        format!("unexpected {program} output: {}", output.trim()),
    )
    .into()
}

/// Parses `Volume: 0.70` or `Volume: 0.70 [MUTED]`.
fn parse_wpctl(output: &str) -> Option<VolumeState> {
    let mut words = output.split_whitespace();
    if words.next()? != "Volume:" {
        return None;
    }
    let volume: f32 = words.next()?.parse().ok()?;
    Some(VolumeState {
        percent: (volume * 100.0).round().clamp(0.0, 255.0) as u8,
        muted: words.next() == Some("[MUTED]"),
    })
}

/// Takes the first channel's percentage from
/// `Volume: front-left: 45875 /  70% / -9.29 dB,   front-right: ...`.
fn parse_pactl_volume(output: &str) -> Option<u8> {
    let percent = output.split_whitespace().find(|word| word.ends_with('%'))?;
    let percent: u32 = percent.trim_end_matches('%').parse().ok()?;
    Some(percent.min(255) as u8)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_wpctl() {
        assert_eq!(
            parse_wpctl("Volume: 0.70\n"),
            Some(VolumeState {
                percent: 70,
                muted: false
            })
        );
        assert_eq!(
            parse_wpctl("Volume: 1.25 [MUTED]\n"),
            Some(VolumeState {
                percent: 125,
                muted: true
            })
        );
        assert_eq!(parse_wpctl("nonsense"), None);
    }

    #[test]
    fn picks_volume_events() {
        assert!(is_volume_event("Event 'change' on sink #49"));
        assert!(is_volume_event("Event 'change' on server -1"));
        assert!(!is_volume_event("Event 'change' on sink-input #120"));
        assert!(!is_volume_event("Event 'new' on client #3344"));
        assert!(!is_volume_event("Event 'change' on card #44"));
    }

    #[test]
    fn parses_pactl() {
        let output = "Volume: front-left: 45875 /  70% / -9.29 dB,   front-right: 45875 /  70% / -9.29 dB\n        balance 0.00\n";
        assert_eq!(parse_pactl_volume(output), Some(70));
    }
}
