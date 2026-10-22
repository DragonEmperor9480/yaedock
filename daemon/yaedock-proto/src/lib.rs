//! Protocol message types shared by the daemon and the test client.
//!
//! Every message is a JSON object with a `type` field. See `protocol/protocol.md`
//! for the wire format; this crate and that document must change together.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Protocol version sent in `hello` and `welcome`.
pub const PROTOCOL_VERSION: u32 = 1;

/// Messages sent by the phone to the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMessage {
    Hello(Hello),
    Subscribe(Subscribe),
    SetBrightness(SetBrightness),
    SetVolume(SetVolume),
    SetMic(SetMic),
    SetOutput(SetOutput),
    SetAppVolume(SetAppVolume),
    Media(MediaControl),
    SetPowerProfile(SetPowerProfile),
    TakeScreenshot,
    StartRecording,
    StopRecording(StopRecording),
    SystemAction(RunSystemAction),
    Ping,
    Pong,
}

/// Messages sent by the PC to the phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    Welcome(Welcome),
    Stats(Stats),
    State(State),
    Screenshot(Screenshot),
    RecordingSaved(RecordingSaved),
    Error(Error),
    Ping,
    Pong,
}

/// First message on every connection.
///
/// A new phone sends the one-time pairing `code` from the QR code; a paired
/// phone sends the `token` it received in its first `welcome`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Hello {
    pub version: u32,
    pub device_id: String,
    pub device_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

/// Reply to a successful `hello`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Welcome {
    pub version: u32,
    pub hostname: String,
    pub os: String,
    pub cpu_model: String,
    pub cores: u32,
    /// Temperature sensor names, the keys used in `Stats::temps`.
    pub sensors: Vec<String>,
    /// Long-lived device token, present only when pairing with a code.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    pub state: State,
    /// Graphics cards, whose readings arrive in `Stats::gpus` under the same ids.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub gpus: Vec<GpuInfo>,
    /// Whether `take_screenshot` works (Spectacle is installed).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub screenshot: bool,
    /// Whether `start_recording` works (OBS Studio is installed and its
    /// WebSocket server is on). OBS itself may still be closed.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub recorder: bool,
    /// What `system_action` may do on this PC.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub system_actions: Vec<SystemAction>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GpuInfo {
    /// Stable for the daemon's lifetime, such as `pci:0000:01:00.0`.
    pub id: String,
    pub name: String,
}

/// One sample of system metrics. Groups the phone has not subscribed to are omitted.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Stats {
    /// Unix time in milliseconds.
    pub ts: u64,
    /// Seconds since boot.
    pub uptime: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cpu: Option<Cpu>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mem: Option<Mem>,
    /// Degrees Celsius by sensor name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub temps: Option<BTreeMap<String, f32>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub net: Option<Net>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disk: Option<Disk>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gpus: Option<Vec<GpuStats>>,
    /// The game running right now, if its frame rate is known (through MangoHud).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub game: Option<Game>,
    /// The laptop battery; left out on PCs without one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub battery: Option<Battery>,
}

/// The laptop battery (all batteries together, if there are several).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Battery {
    /// Charge in percent, 0 to 100.
    pub percent: f32,
    pub state: BatteryState,
    /// Seconds until empty while discharging, or until full while charging.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seconds_left: Option<u64>,
    /// Watts flowing in or out.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub power_w: Option<f32>,
    /// Full capacity now, in percent of the capacity when new.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub health: Option<f32>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BatteryState {
    Charging,
    Discharging,
    Full,
    /// Plugged in but not charging, for example held below 100% to spare the battery.
    NotCharging,
    #[default]
    #[serde(other)]
    Unknown,
}

/// A running game's frame rate, as MangoHud measures it.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Game {
    /// The game's program name, such as `eldenring.exe` or `vkcube`.
    pub name: String,
    /// Frames per second.
    pub fps: f32,
    /// Milliseconds per frame.
    pub frametime: f32,
}

/// One graphics card's readings. What a GPU or its driver cannot report is left out.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GpuStats {
    /// Matches `GpuInfo::id` in the welcome.
    pub id: String,
    /// Percent busy, 0 to 100.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<f32>,
    /// Degrees Celsius.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub temp: Option<f32>,
    /// Video memory in bytes; integrated GPUs share system RAM and report none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vram_used: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vram_total: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub clock_mhz: Option<u32>,
    /// Powered down to save energy; it is not woken up to be read.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub sleeping: bool,
}

/// CPU usage in percent, 0 to 100.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Cpu {
    pub total: f32,
    pub cores: Vec<f32>,
}

/// Memory in bytes.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Mem {
    pub used: u64,
    pub total: u64,
}

/// Network throughput in bytes per second, summed over all interfaces.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Net {
    pub rx: u64,
    pub tx: u64,
}

/// Disk throughput in bytes per second, summed over all disks.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Disk {
    pub read: u64,
    pub write: u64,
}

/// Current brightness, volume, media and power profile, sent after any change
/// from any source. What the PC cannot control is left out.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct State {
    pub displays: Vec<DisplayState>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub volume: Option<VolumeState>,
    /// The default microphone.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mic: Option<VolumeState>,
    /// The audio outputs to choose from; the default one is marked.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub outputs: Vec<AudioOutput>,
    /// Programs playing sound right now, each with its own volume.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub apps: Vec<AppVolume>,
    /// The media player to control: the one playing, or else the last one seen.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media: Option<Media>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub power: Option<PowerProfiles>,
    /// The screen recorder (OBS Studio), present while it is running.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recording: Option<Recording>,
}

/// Whether the screen recorder is recording, and for how long.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Recording {
    pub active: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub paused: bool,
    /// Length of the recording so far, in milliseconds; 0 when not recording.
    #[serde(default)]
    pub duration_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DisplayState {
    pub id: String,
    pub name: String,
    /// Brightness in percent, 0 to 100.
    pub percent: u8,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct VolumeState {
    /// Volume in percent, 0 to 150.
    pub percent: u8,
    pub muted: bool,
}

/// An audio output, such as the built-in speakers or a Bluetooth headset.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AudioOutput {
    /// The audio server's name for it, used in `set_output`.
    pub id: String,
    pub name: String,
    /// Where on the device the sound goes, such as "Headphones" or "Speakers".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<String>,
    /// Whether sound plays here now.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub default: bool,
}

/// One program's sound stream.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AppVolume {
    /// The audio server's stream index, valid while the stream plays.
    pub id: u32,
    pub name: String,
    /// Volume in percent, 0 to 150.
    pub percent: u8,
    pub muted: bool,
}

/// What a media player (MPRIS) is playing.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Media {
    /// Identifies the player in `media` commands, such as `spotify` or `firefox.instance_1_42`.
    pub player: String,
    /// The player's own name, such as `Spotify` or `Mozilla Firefox`.
    pub app: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub title: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub artist: String,
    pub playing: bool,
    pub can_play_pause: bool,
    pub can_next: bool,
    pub can_previous: bool,
}

/// The power profile (power-profiles-daemon or tuned-ppd).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PowerProfiles {
    /// Such as `power-saver`, `balanced` or `performance`.
    pub active: String,
    pub available: Vec<String>,
}

/// Metric groups a phone can subscribe to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MetricGroup {
    Cpu,
    Mem,
    Temps,
    Net,
    Disk,
    Gpu,
    Game,
    Battery,
}

/// Choose which metric groups to receive and how often.
///
/// `interval_ms` is rounded up to a multiple of the daemon's sampling tick.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Subscribe {
    pub groups: Vec<MetricGroup>,
    pub interval_ms: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetBrightness {
    pub display: String,
    /// Brightness in percent, 0 to 100.
    pub percent: u8,
}

/// Change the volume, the mute flag, or both. Omitted fields are left as they are.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetVolume {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub percent: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub muted: Option<bool>,
}

/// Change the default microphone's volume, mute flag, or both.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetMic {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub percent: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub muted: Option<bool>,
}

/// Play sound through another output.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetOutput {
    /// `AudioOutput::id` from the state.
    pub output: String,
}

/// Change one program's volume, mute flag, or both.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetAppVolume {
    /// `AppVolume::id` from the state.
    pub app: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub percent: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub muted: Option<bool>,
}

/// Play, pause or skip on a media player.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MediaControl {
    pub action: MediaAction,
    /// `Media::player` from the state; without it, the player the state shows.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub player: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaAction {
    PlayPause,
    Next,
    Previous,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetPowerProfile {
    /// One of `PowerProfiles::available`.
    pub profile: String,
}

/// Lock, sleep, restart or shut down the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RunSystemAction {
    pub action: SystemAction,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SystemAction {
    Lock,
    Suspend,
    Reboot,
    PowerOff,
}

/// Stop the screen recording.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StopRecording {
    /// Also close the recorder app once the video is saved, unless it is busy
    /// with something else (streaming, for example).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub close_app: bool,
}

/// Reply to `stop_recording`: where the recording was saved on the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RecordingSaved {
    pub path: String,
}

/// Reply to `take_screenshot`: where the screenshot was saved on the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Screenshot {
    pub path: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Error {
    pub code: ErrorCode,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    /// The message could not be parsed or was not expected at this point.
    BadRequest,
    /// The token or pairing code was missing or wrong.
    Unauthorized,
    /// The phone speaks a protocol version the daemon does not support.
    UnsupportedVersion,
    UnsupportedDisplay,
    /// A brightness, volume, media or power backend failed to apply the change.
    BackendFailed,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn stats_round_trip() {
        let msg = ServerMessage::Stats(Stats {
            ts: 1_760_838_600_000,
            uptime: 3600,
            cpu: Some(Cpu {
                total: 23.5,
                cores: vec![18.0, 29.0],
            }),
            mem: Some(Mem {
                used: 6_549_000_000,
                total: 16_642_000_000,
            }),
            temps: Some(BTreeMap::from([("cpu_package".into(), 54.0)])),
            net: Some(Net {
                rx: 120_400,
                tx: 8_800,
            }),
            disk: None,
            gpus: None,
            game: Some(Game {
                name: "vkcube".into(),
                fps: 59.7,
                frametime: 16.7,
            }),
            battery: Some(Battery {
                percent: 13.1,
                state: BatteryState::NotCharging,
                seconds_left: None,
                power_w: Some(0.0),
                health: Some(56.2),
            }),
        });
        let value = serde_json::to_value(&msg).unwrap();
        assert_eq!(value["type"], "stats");
        assert!(value.get("disk").is_none());
        assert_eq!(value["battery"]["state"], "not_charging");
        // A state from a newer daemon still parses.
        let later: BatteryState = serde_json::from_value(json!("cooling")).unwrap();
        assert_eq!(later, BatteryState::Unknown);
        assert_eq!(serde_json::from_value::<ServerMessage>(value).unwrap(), msg);
    }

    #[test]
    fn gpu_readings_leave_out_what_is_unknown() {
        let integrated = GpuStats {
            id: "pci:0000:00:02.0".into(),
            usage: Some(12.0),
            clock_mhz: Some(350),
            ..GpuStats::default()
        };
        let sleeping = GpuStats {
            id: "pci:0000:01:00.0".into(),
            sleeping: true,
            ..GpuStats::default()
        };
        assert_eq!(
            serde_json::to_value(&integrated).unwrap(),
            json!({"id": "pci:0000:00:02.0", "usage": 12.0, "clock_mhz": 350})
        );
        assert_eq!(
            serde_json::to_value(&sleeping).unwrap(),
            json!({"id": "pci:0000:01:00.0", "sleeping": true})
        );
        // A welcome from a daemon without GPU support still parses.
        let welcome: ServerMessage = serde_json::from_value(json!({
            "type": "welcome", "version": 1, "hostname": "pc", "os": "Linux", "cpu_model": "x",
            "cores": 4, "sensors": [], "state": {"displays": []}
        }))
        .unwrap();
        let ServerMessage::Welcome(welcome) = welcome else {
            panic!("not a welcome")
        };
        assert!(welcome.gpus.is_empty());
    }

    #[test]
    fn controls_round_trip() {
        let state = State {
            outputs: vec![AudioOutput {
                id: "alsa_output.pci-0000_00_1f.3.analog-stereo".into(),
                name: "Built-in Audio Analog Stereo".into(),
                port: Some("Headphones".into()),
                default: true,
            }],
            apps: vec![AppVolume {
                id: 551,
                name: "Firefox".into(),
                percent: 80,
                muted: false,
            }],
            media: Some(Media {
                player: "spotify".into(),
                app: "Spotify".into(),
                title: "Song".into(),
                playing: true,
                can_play_pause: true,
                can_next: true,
                ..Media::default()
            }),
            power: Some(PowerProfiles {
                active: "balanced".into(),
                available: vec!["power-saver".into(), "balanced".into()],
            }),
            ..State::default()
        };
        let value = serde_json::to_value(&state).unwrap();
        assert!(value.get("mic").is_none());
        assert!(value["media"].get("artist").is_none());
        assert_eq!(serde_json::from_value::<State>(value).unwrap(), state);
        // A state from a daemon without these controls still parses.
        let old: State = serde_json::from_value(json!({"displays": []})).unwrap();
        assert!(old.apps.is_empty() && old.media.is_none());

        let msg: ClientMessage =
            serde_json::from_value(json!({"type": "media", "action": "play_pause"})).unwrap();
        assert_eq!(
            msg,
            ClientMessage::Media(MediaControl {
                action: MediaAction::PlayPause,
                player: None
            })
        );
        assert_eq!(
            serde_json::to_value(ClientMessage::SetAppVolume(SetAppVolume {
                app: 551,
                percent: None,
                muted: Some(true)
            }))
            .unwrap(),
            json!({"type": "set_app_volume", "app": 551, "muted": true})
        );
    }

    #[test]
    fn hello_without_optional_fields() {
        let msg: ClientMessage = serde_json::from_value(json!({
            "type": "hello", "version": 1, "device_id": "abc", "device_name": "Pixel"
        }))
        .unwrap();
        let ClientMessage::Hello(hello) = msg else {
            panic!("not a hello")
        };
        assert_eq!(hello.token, None);
        assert_eq!(hello.code, None);
    }

    #[test]
    fn unit_messages_and_enums() {
        assert_eq!(
            serde_json::to_value(ClientMessage::Ping).unwrap(),
            json!({"type": "ping"})
        );
        assert_eq!(
            serde_json::from_value::<ClientMessage>(json!({"type": "take_screenshot"})).unwrap(),
            ClientMessage::TakeScreenshot
        );
        assert_eq!(
            serde_json::from_value::<ClientMessage>(
                json!({"type": "system_action", "action": "power_off"})
            )
            .unwrap(),
            ClientMessage::SystemAction(RunSystemAction {
                action: SystemAction::PowerOff
            })
        );
        assert_eq!(
            serde_json::to_value(ClientMessage::StopRecording(StopRecording::default())).unwrap(),
            json!({"type": "stop_recording"})
        );
        assert_eq!(
            serde_json::from_value::<ClientMessage>(
                json!({"type": "stop_recording", "close_app": true})
            )
            .unwrap(),
            ClientMessage::StopRecording(StopRecording { close_app: true })
        );
        let state = State {
            recording: Some(Recording {
                active: true,
                paused: false,
                duration_ms: 42_000,
            }),
            ..State::default()
        };
        assert_eq!(
            serde_json::to_value(&state).unwrap()["recording"],
            json!({"active": true, "duration_ms": 42000})
        );
        assert_eq!(
            serde_json::to_value(ServerMessage::Screenshot(Screenshot {
                path: "/home/me/Pictures/Screenshots/a.png".into()
            }))
            .unwrap(),
            json!({"type": "screenshot", "path": "/home/me/Pictures/Screenshots/a.png"})
        );
        let sub: ClientMessage = serde_json::from_value(json!({
            "type": "subscribe", "groups": ["cpu", "temps"], "interval_ms": 2000
        }))
        .unwrap();
        assert_eq!(
            sub,
            ClientMessage::Subscribe(Subscribe {
                groups: vec![MetricGroup::Cpu, MetricGroup::Temps],
                interval_ms: 2000
            })
        );
        let err = ServerMessage::Error(Error {
            code: ErrorCode::UnsupportedDisplay,
            message: "no such display".into(),
        });
        assert_eq!(
            serde_json::to_value(err).unwrap()["code"],
            "unsupported_display"
        );
    }
}
