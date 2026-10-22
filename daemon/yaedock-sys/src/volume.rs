//! Volume backends: the default audio output, the default microphone, and
//! each program's sound stream.

use std::io::{self, BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use yaedock_proto::{AppVolume, AudioOutput, VolumeState};

use crate::ControlError;

/// The highest volume the phone can set, in percent.
pub const MAX_PERCENT: u8 = 150;

/// Reads and changes volumes.
pub trait VolumeBackend: Send + Sync {
    /// What this backend uses, for reports such as `yaedock check`.
    fn name(&self) -> &'static str;

    /// The default audio output.
    fn get(&self) -> Result<VolumeState, ControlError>;

    /// Changes the default output's volume (0 to 150 percent), the mute flag, or both.
    fn set(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError>;

    /// The default microphone, or `None` when there is none.
    fn mic(&self) -> Result<Option<VolumeState>, ControlError> {
        if pactl_default_source()?.is_none() {
            return Ok(None);
        }
        let volume = run("pactl", &["get-source-volume", PACTL_SOURCE])?;
        let mute = run("pactl", &["get-source-mute", PACTL_SOURCE])?;
        Ok(Some(VolumeState {
            percent: parse_pactl_volume(&volume).ok_or_else(|| bad_output("pactl", &volume))?,
            muted: parse_pactl_mute(&mute).ok_or_else(|| bad_output("pactl", &mute))?,
        }))
    }

    fn set_mic(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
        pactl_set("source", PACTL_SOURCE, percent, muted)
    }

    /// The outputs to choose from, the default one marked; through `pactl`
    /// with PulseAudio and PipeWire alike.
    fn outputs(&self) -> Result<Vec<AudioOutput>, ControlError> {
        let sinks = run("pactl", &["-f", "json", "list", "sinks"])?;
        let default = run("pactl", &["get-default-sink"])?;
        parse_outputs(&sinks, default.trim()).ok_or_else(|| bad_output("pactl", &sinks))
    }

    /// Makes `id` (an `AudioOutput::id`) the default output. This is
    /// PipeWire's way: programs that play to "the default" follow it.
    fn set_output(&self, id: &str) -> Result<(), ControlError> {
        if !self.outputs()?.iter().any(|output| output.id == id) {
            return Err(ControlError::UnknownDevice(id.to_string()));
        }
        run("pactl", &["set-default-sink", id])?;
        Ok(())
    }

    /// Programs playing sound, through `pactl` with PulseAudio and PipeWire alike.
    fn apps(&self) -> Result<Vec<AppVolume>, ControlError> {
        let out = run("pactl", &["-f", "json", "list", "sink-inputs"])?;
        parse_apps(&out).ok_or_else(|| bad_output("pactl", &out))
    }

    /// Changes one program's volume, mute flag, or both; `id` is `AppVolume::id`.
    fn set_app(
        &self,
        id: u32,
        percent: Option<u8>,
        muted: Option<bool>,
    ) -> Result<(), ControlError> {
        pactl_set("sink-input", &id.to_string(), percent, muted)
    }

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
/// PipeWire's pulse server, and reports changes to outputs and microphones
/// (volume, mute), to program streams, and to the server (the defaults).
/// Client events are ignored: reading the volume itself creates them.
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
                if is_audio_event(&line) {
                    changed();
                }
            }
            thread_alive.store(false, Ordering::Relaxed);
        })
        .ok()?;
    Some(Watcher { child, alive })
}

fn is_audio_event(line: &str) -> bool {
    let Some(rest) = line.strip_prefix("Event '") else {
        return false;
    };
    let Some((event, object)) = rest.split_once("' on ") else {
        return false;
    };
    match object.split(' ').next() {
        Some("source" | "server") => event == "change",
        // Outputs come and go too, such as a Bluetooth headset connecting.
        Some("sink" | "sink-input") => true,
        _ => false,
    }
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
const WPCTL_SOURCE: &str = "@DEFAULT_AUDIO_SOURCE@";

impl VolumeBackend for WpctlBackend {
    fn name(&self) -> &'static str {
        "PipeWire (wpctl)"
    }

    fn get(&self) -> Result<VolumeState, ControlError> {
        let out = run("wpctl", &["get-volume", WPCTL_SINK])?;
        parse_wpctl(&out).ok_or_else(|| bad_output("wpctl", &out))
    }

    fn set(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
        wpctl_set(WPCTL_SINK, percent, muted)
    }

    // Program streams still go through pactl, whose JSON names them.
    fn mic(&self) -> Result<Option<VolumeState>, ControlError> {
        // wpctl fails when there is no microphone to be the default.
        match run("wpctl", &["get-volume", WPCTL_SOURCE]) {
            Ok(out) => Ok(Some(
                parse_wpctl(&out).ok_or_else(|| bad_output("wpctl", &out))?,
            )),
            Err(_) => Ok(None),
        }
    }

    fn set_mic(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
        wpctl_set(WPCTL_SOURCE, percent, muted)
    }
}

fn wpctl_set(target: &str, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
    if let Some(percent) = percent {
        let volume = format!("{:.2}", percent.min(MAX_PERCENT) as f32 / 100.0);
        run("wpctl", &["set-volume", target, &volume])?;
    }
    if let Some(muted) = muted {
        run(
            "wpctl",
            &["set-mute", target, if muted { "1" } else { "0" }],
        )?;
    }
    Ok(())
}

/// PulseAudio, or PipeWire through pipewire-pulse, via `pactl`.
pub struct PactlBackend;

const PACTL_SINK: &str = "@DEFAULT_SINK@";
const PACTL_SOURCE: &str = "@DEFAULT_SOURCE@";

impl VolumeBackend for PactlBackend {
    fn name(&self) -> &'static str {
        "PulseAudio (pactl)"
    }

    fn get(&self) -> Result<VolumeState, ControlError> {
        let volume = run("pactl", &["get-sink-volume", PACTL_SINK])?;
        let mute = run("pactl", &["get-sink-mute", PACTL_SINK])?;
        let percent = parse_pactl_volume(&volume).ok_or_else(|| bad_output("pactl", &volume))?;
        let muted = parse_pactl_mute(&mute).ok_or_else(|| bad_output("pactl", &mute))?;
        Ok(VolumeState { percent, muted })
    }

    fn set(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), ControlError> {
        pactl_set("sink", PACTL_SINK, percent, muted)
    }

    /// PulseAudio leaves playing programs where they are when the default
    /// changes, so they are moved over too.
    fn set_output(&self, id: &str) -> Result<(), ControlError> {
        if !self.outputs()?.iter().any(|output| output.id == id) {
            return Err(ControlError::UnknownDevice(id.to_string()));
        }
        run("pactl", &["set-default-sink", id])?;
        for app in self.apps()? {
            // A program may have stopped meanwhile.
            let _ = run("pactl", &["move-sink-input", &app.id.to_string(), id]);
        }
        Ok(())
    }
}

/// Sets the volume and mute flag of a pactl `kind` (sink, source, sink-input).
fn pactl_set(
    kind: &str,
    target: &str,
    percent: Option<u8>,
    muted: Option<bool>,
) -> Result<(), ControlError> {
    if let Some(percent) = percent {
        let volume = format!("{}%", percent.min(MAX_PERCENT));
        run("pactl", &[&format!("set-{kind}-volume"), target, &volume])?;
    }
    if let Some(muted) = muted {
        let flag = if muted { "1" } else { "0" };
        run("pactl", &[&format!("set-{kind}-mute"), target, flag])?;
    }
    Ok(())
}

/// The default microphone's name, or `None` when the default source is only
/// the monitor of an output, which is what remains without a microphone.
fn pactl_default_source() -> io::Result<Option<String>> {
    let name = run("pactl", &["get-default-source"])?.trim().to_string();
    Ok(Some(name).filter(|n| !n.is_empty() && !n.ends_with(".monitor")))
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

fn parse_pactl_mute(output: &str) -> Option<bool> {
    match output.trim() {
        "Mute: yes" => Some(true),
        "Mute: no" => Some(false),
        _ => None,
    }
}

/// Reads the outputs from `pactl -f json list sinks`, with the port each one
/// plays through, such as "Headphones".
fn parse_outputs(json: &str, default: &str) -> Option<Vec<AudioOutput>> {
    let sinks: Vec<serde_json::Value> = serde_json::from_str(json).ok()?;
    Some(
        sinks
            .iter()
            .filter_map(|sink| {
                let id = sink["name"].as_str()?.to_string();
                let active = sink["active_port"].as_str();
                let port = sink["ports"].as_array().and_then(|ports| {
                    let named =
                        |p: &&serde_json::Value| active.is_some() && p["name"].as_str() == active;
                    ports.iter().find(named)?["description"]
                        .as_str()
                        .map(str::to_string)
                });
                Some(AudioOutput {
                    name: sink["description"].as_str().unwrap_or(&id).to_string(),
                    port,
                    default: id == default,
                    id,
                })
            })
            .collect(),
    )
}

/// Reads the streams from `pactl -f json list sink-inputs`, oldest first.
fn parse_apps(json: &str) -> Option<Vec<AppVolume>> {
    let streams: Vec<serde_json::Value> = serde_json::from_str(json).ok()?;
    let mut apps: Vec<AppVolume> = streams
        .iter()
        .filter_map(|stream| {
            let properties = &stream["properties"];
            let name = [
                "application.name",
                "application.process.binary",
                "media.name",
            ]
            .iter()
            .find_map(|key| properties[key].as_str().filter(|s| !s.is_empty()))
            .unwrap_or("Unknown");
            // Every channel at the same level is the usual case; show the first.
            let percent = stream["volume"]
                .as_object()?
                .values()
                .next()?
                .get("value_percent")?
                .as_str()?
                .trim_end_matches('%')
                .parse::<u32>()
                .ok()?;
            Some(AppVolume {
                id: u32::try_from(stream["index"].as_u64()?).ok()?,
                name: name.to_string(),
                percent: percent.min(255) as u8,
                muted: stream["mute"].as_bool().unwrap_or(false),
            })
        })
        .collect();
    apps.sort_by_key(|app| app.id);
    Some(apps)
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
    fn picks_audio_events() {
        assert!(is_audio_event("Event 'change' on sink #49"));
        assert!(is_audio_event("Event 'change' on server -1"));
        assert!(is_audio_event("Event 'change' on source #57"));
        assert!(is_audio_event("Event 'new' on sink-input #120"));
        assert!(is_audio_event("Event 'remove' on sink-input #120"));
        assert!(is_audio_event("Event 'new' on sink #70"));
        assert!(!is_audio_event("Event 'new' on client #3344"));
        assert!(!is_audio_event("Event 'change' on card #44"));
        assert!(!is_audio_event("Event 'change' on source-output #3"));
    }

    #[test]
    fn parses_outputs() {
        let json = r#"[{"name":"alsa_output.pci-0000_00_1f.3.analog-stereo","description":"Built-in Audio Analog Stereo",
            "active_port":"analog-output-headphones","ports":[
              {"name":"analog-output-speaker","description":"Speakers"},
              {"name":"analog-output-headphones","description":"Headphones"}]},
            {"name":"bluez_output.AA_BB.1","description":"WH-1000XM4","active_port":null,"ports":[]}]"#;
        assert_eq!(
            parse_outputs(json, "bluez_output.AA_BB.1").unwrap(),
            vec![
                AudioOutput {
                    id: "alsa_output.pci-0000_00_1f.3.analog-stereo".into(),
                    name: "Built-in Audio Analog Stereo".into(),
                    port: Some("Headphones".into()),
                    default: false,
                },
                AudioOutput {
                    id: "bluez_output.AA_BB.1".into(),
                    name: "WH-1000XM4".into(),
                    port: None,
                    default: true,
                },
            ]
        );
    }

    #[test]
    fn parses_app_streams() {
        let json = r#"[{"index":561,"mute":true,"volume":{"front-left":{"value":32768,"value_percent":"50%","db":"-18.06 dB"}},
            "properties":{"application.name":"Firefox","media.name":"AudioStream"}},
            {"index":551,"mute":false,"volume":{"mono":{"value":65536,"value_percent":"100%","db":"0.00 dB"}},
            "properties":{"application.process.binary":"mpv"}}]"#;
        assert_eq!(
            parse_apps(json).unwrap(),
            vec![
                AppVolume {
                    id: 551,
                    name: "mpv".into(),
                    percent: 100,
                    muted: false
                },
                AppVolume {
                    id: 561,
                    name: "Firefox".into(),
                    percent: 50,
                    muted: true
                },
            ]
        );
        assert_eq!(parse_apps("[]").unwrap(), vec![]);
        assert!(parse_apps("not json").is_none());
    }

    #[test]
    fn parses_pactl() {
        let output = "Volume: front-left: 45875 /  70% / -9.29 dB,   front-right: 45875 /  70% / -9.29 dB\n        balance 0.00\n";
        assert_eq!(parse_pactl_volume(output), Some(70));
    }
}
