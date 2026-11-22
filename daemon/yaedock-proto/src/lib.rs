//! Protocol message types shared by the daemon and the test client.
//!
//! Every message is a JSON object with a `type` field. See `protocol/protocol.md`
//! for the wire format; this crate and that document must change together.

use std::collections::BTreeMap;

pub mod link;

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
    Seek(Seek),
    SetPowerProfile(SetPowerProfile),
    SetOverlay(SetOverlay),
    TakeScreenshot,
    StartRecording,
    StopRecording(StopRecording),
    StartScreen(StartScreen),
    StopScreen,
    StartPhoneMic,
    StopPhoneMic,
    StartPhoneCamera(StartPhoneCamera),
    StopPhoneCamera,
    /// Make the phone one of the PC's speakers.
    StartPhoneSpeaker,
    StopPhoneSpeaker,
    /// Moves the PC's pointer, as a trackpad does.
    PointerMove(PointerMove),
    /// Scrolls, as a mouse wheel does.
    PointerScroll(PointerScroll),
    /// Presses or lets go of a mouse button.
    PointerButton(PointerButton),
    /// Presses or lets go of a key phones lack, such as Esc or Ctrl.
    Key(KeyPress),
    /// Types text on the PC, as if on its keyboard.
    TypeText(TypeText),
    /// The whole state of the phone's game controller; the PC makes the
    /// controller at the first one.
    Gamepad(GamepadState),
    /// The controller page closed: the PC lets go of everything and removes it.
    GamepadOff,
    /// The phone was just plugged into a computer by USB; if it is this PC,
    /// the phone would like to connect over the cable.
    UsbPlugged,
    /// The user turned the cable off on the phone: the PC stops switching it,
    /// and lets it go if it is on the cable now.
    UsbOff,
    SystemAction(RunSystemAction),
    RunShortcut(RunShortcut),
    RunCheck,
    UpdatePc(UpdatePc),
    Ping,
    Pong,
}

/// Messages sent by the PC to the phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
// `welcome` is the largest, and goes out once per connection: boxing it would
// save nothing worth the indirection everywhere a message is built.
#[allow(clippy::large_enum_variant)]
pub enum ServerMessage {
    Welcome(Welcome),
    Stats(Stats),
    State(State),
    Screenshot(Screenshot),
    RecordingSaved(RecordingSaved),
    Screen(Screen),
    PhoneMic(PhoneMic),
    PhoneCamera(PhoneCamera),
    PhoneSpeaker(PhoneSpeaker),
    PhoneCameraSeen(PhoneCameraSeen),
    /// A game asked the phone's controller to rumble.
    GamepadRumble(GamepadRumble),
    CheckReport(CheckReport),
    MediaArt(MediaArt),
    UpdateProgress(UpdateProgress),
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
    /// Whether `start_screen` works (the desktop can share a monitor and
    /// GStreamer can encode it).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub screen: bool,
    /// Whether `start_screen` can also `extend`: the desktop can make a
    /// virtual display.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub screen_extend: bool,
    /// Whether `start_phone_mic` works: the PC's sound server can make a
    /// virtual microphone.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub phone_mic: bool,
    /// Whether the PC also takes the phone's sound as Opus (binary frames of
    /// kind 4), which is far smaller: it has `libopus`.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub phone_mic_opus: bool,
    /// Whether `start_phone_camera` works: GStreamer can decode the phone's
    /// video and offer it as a camera in PipeWire.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub phone_camera: bool,
    /// Whether `start_phone_speaker` works: the PC's sound server can make a
    /// virtual output that feeds the phone.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub phone_speaker: bool,
    /// Whether this PC serves phones over a USB cable, and so understands
    /// `usb_plugged` and `usb_off`.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub usb_cable: bool,
    /// Whether the PC takes `pointer_*`, `key` and `type_text`: it can make a
    /// keyboard and a mouse for the phone.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub input: bool,
    /// Whether the PC takes `gamepad` and `gamepad_off`: it can make a game
    /// controller for the phone.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub gamepad: bool,
    /// What `system_action` may do on this PC.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub system_actions: Vec<SystemAction>,
    /// The version of YaeDock on the PC, such as `0.1.0`. Empty from daemons
    /// older than updates, which cannot `update_pc`.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub daemon_version: String,
    /// Why `update_pc` cannot work on this PC, such as a program installed by
    /// a package manager. Left out when it can.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cannot_update: Option<String>,
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
    /// Frames per second in the slowest 1% of frames over the last minute.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub low_1: Option<f32>,
    /// The same for the slowest 0.1%, once the minute holds enough frames.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub low_0_1: Option<f32>,
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
    /// Shortcut buttons set up on the PC (`yaedock shortcuts`).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub shortcuts: Vec<Shortcut>,
    /// The screen recorder (OBS Studio), present while it is running.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recording: Option<Recording>,
    /// Whether MangoHud's overlay shows on the PC, present while game frame
    /// rates are on (`yaedock fps on`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub overlay: Option<bool>,
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
    /// Names the cover of what plays, which follows in a `media_art` message;
    /// it changes with the cover. Missing when the player gives none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub art: Option<String>,
    /// The track's length, when the player knows it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub length_ms: Option<u64>,
    /// How far into the track the player was when this state was sent. The
    /// phone moves it on by itself while `playing`; the PC sends it again
    /// only when it jumps (a seek, a new track) or playback stops or starts.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub position_ms: Option<u64>,
    /// Whether `seek` works on this player.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub can_seek: bool,
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

/// Updates the PC to `version`, which must be the newest release and newer
/// than the PC's own. Answered with `update_progress` as it goes, then the
/// daemon restarts on the new version; or with an `error`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UpdatePc {
    pub version: String,
}

/// How far an `update_pc` has got.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UpdateProgress {
    pub stage: UpdateStage,
    /// How much of the download is done, while `downloading`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub percent: Option<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdateStage {
    Downloading,
    Verifying,
    Installing,
    /// Installed; the daemon restarts now, closing the connection.
    Restarting,
}

/// Jumps to `position_ms` in the track `player` plays (`Media::player`), or
/// in the one the state shows when `player` is missing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Seek {
    pub position_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub player: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetPowerProfile {
    /// One of `PowerProfiles::available`.
    pub profile: String,
}

/// Shows or hides MangoHud's overlay on the PC; games keep logging either way.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetOverlay {
    pub visible: bool,
}

/// A shortcut button. The command it runs stays on the PC; the phone only
/// ever names a shortcut, so it cannot run anything the user did not set up.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Shortcut {
    /// Used in `run_shortcut`.
    pub id: String,
    pub name: String,
}

/// Run one of the PC's shortcuts.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RunShortcut {
    /// `Shortcut::id` from the state.
    pub id: String,
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

/// Asks for the PC's screen on this phone.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct StartScreen {
    #[serde(default, skip_serializing_if = "ScreenMode::is_mirror")]
    pub mode: ScreenMode,
    /// The largest picture wanted, in pixels. A mirrored monitor is scaled
    /// down to fit, keeping its shape; a new display is made this size.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScreenMode {
    /// Shows one of the PC's monitors.
    #[default]
    Mirror,
    /// Makes the phone another display of the PC, beside its monitors.
    Extend,
}

impl ScreenMode {
    fn is_mirror(&self) -> bool {
        *self == ScreenMode::Mirror
    }
}

/// How mirroring the screen to this phone stands, after `start_screen` and
/// whenever it changes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Screen {
    pub state: ScreenState,
    /// The picture's size in pixels while `on`, when it is known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// Why it went `off`, when it was not the phone's `stop_screen`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    /// While `on`: the PC's screen is locked, so the desktop shares only
    /// black. Sent again with `false` once it is unlocked.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub locked: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScreenState {
    /// The desktop is asking the user at the PC whether to share.
    Asking,
    /// Video frames follow.
    On,
    Off,
}

/// Whether the phone's microphone is one of the PC's, after
/// `start_phone_mic` and whenever it changes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PhoneMic {
    pub state: PhoneMicState,
    /// Why it went `off`, when it was not the phone's `stop_phone_mic`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PhoneMicState {
    /// The PC has the microphone and takes the phone's sound.
    On,
    Off,
}

/// The first byte of a binary frame carrying the phone microphone's sound.
/// The rest is the sound: one channel at 48 000 samples a second, each a
/// signed 16-bit little-endian number.
pub const PHONE_SOUND: u8 = 2;

/// The first byte of a binary frame carrying the phone microphone's sound as
/// Opus. The rest is one Opus packet: one channel at 48 000 samples a second,
/// usually 20 ms of it.
pub const PHONE_OPUS: u8 = 4;

/// The Opus packet in a binary frame from the phone, when that is what it
/// carries.
pub fn phone_opus(frame: &[u8]) -> Option<&[u8]> {
    match frame.split_first() {
        Some((&PHONE_OPUS, packet)) => Some(packet),
        _ => None,
    }
}

/// The sound in a binary frame from the phone, when that is what it carries.
pub fn phone_sound(frame: &[u8]) -> Option<&[u8]> {
    match frame.split_first() {
        Some((&PHONE_SOUND, sound)) => Some(sound),
        _ => None,
    }
}

/// Whether the phone is one of the PC's speakers, after
/// `start_phone_speaker` and whenever it changes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PhoneSpeaker {
    pub state: PhoneSpeakerState,
    /// Why it went `off`, when it was not the phone's `stop_phone_speaker`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PhoneSpeakerState {
    /// The PC plays through the phone, and sound frames follow.
    On,
    Off,
}

/// The first byte of a binary frame carrying the PC's sound to the phone as
/// a speaker. The rest is the sound: two channels at 48 000 samples a second,
/// interleaved left then right, each a signed 16-bit little-endian number.
pub const PC_SOUND: u8 = 5;

/// A binary frame from the PC to the phone.
#[derive(Debug, Clone, PartialEq)]
pub enum PcFrame {
    /// A picture of the mirrored screen.
    Video(VideoFrame),
    /// Sound for the phone as a speaker, in the format of [`PC_SOUND`].
    Sound(Vec<u8>),
}

impl PcFrame {
    /// The frame as sent: its kind, then what it carries.
    pub fn to_bytes(&self) -> Vec<u8> {
        match self {
            PcFrame::Video(frame) => frame.to_bytes(),
            PcFrame::Sound(sound) => {
                let mut bytes = Vec::with_capacity(1 + sound.len());
                bytes.push(PC_SOUND);
                bytes.extend_from_slice(sound);
                bytes
            }
        }
    }
}

impl From<VideoFrame> for PcFrame {
    fn from(frame: VideoFrame) -> Self {
        PcFrame::Video(frame)
    }
}

/// Make the phone's camera one of the PC's. Sent again with another
/// `rotation`, it makes the camera anew.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct StartPhoneCamera {
    /// How far the PC turns the pictures clockwise to stand them upright, in
    /// degrees: 0, 90, 180 or 270.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub rotation: u16,
}

fn is_zero(number: &u16) -> bool {
    *number == 0
}

/// Whether the phone's camera is one of the PC's, after
/// `start_phone_camera` and whenever it changes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PhoneCamera {
    pub state: PhoneCameraState,
    /// Why it went `off`, when it was not the phone's `stop_phone_camera`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PhoneCameraState {
    /// The PC has the camera and takes the phone's video.
    On,
    Off,
}

/// How far the PC has got with the phone camera's pictures, sent for each
/// one that comes. The phone sets it against what it is filming to
/// know how far behind the PC is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PhoneCameraSeen {
    /// When the newest picture the PC has was filmed: the time in its frame.
    pub time: u64,
}

/// The first byte of a binary frame carrying one picture from the phone's
/// camera. The rest is laid out like the screen's video: see
/// [`VideoFrame::to_phone_bytes`].
pub const PHONE_VIDEO: u8 = 3;

/// One picture of the mirrored screen: an H.264 access unit in Annex B form.
/// It travels outside JSON, as a binary frame: see [`VideoFrame::to_bytes`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoFrame {
    /// A picture that needs no earlier one, and carries the stream's
    /// settings (SPS and PPS). Decoding starts at one.
    pub key: bool,
    /// When it was captured, in microseconds on the encoder's clock.
    pub pts_us: u64,
    pub data: Vec<u8>,
}

impl VideoFrame {
    /// The first byte of a binary frame carrying screen video.
    const KIND: u8 = 1;
    const HEADER: usize = 10;

    /// The binary frame: the kind (1), flags (bit 0: keyframe), the time as
    /// a big-endian u64, then the picture.
    pub fn to_bytes(&self) -> Vec<u8> {
        self.framed(Self::KIND)
    }

    /// The binary frame for a picture from the phone's camera: the same
    /// behind the kind 3.
    pub fn to_phone_bytes(&self) -> Vec<u8> {
        self.framed(PHONE_VIDEO)
    }

    fn framed(&self, kind: u8) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(Self::HEADER + self.data.len());
        bytes.push(kind);
        bytes.push(u8::from(self.key));
        bytes.extend_from_slice(&self.pts_us.to_be_bytes());
        bytes.extend_from_slice(&self.data);
        bytes
    }

    /// The frame in `bytes`, or `None` if they are not screen video.
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        Self::parsed(bytes, Self::KIND)
    }

    /// The frame in `bytes`, or `None` if they are not a picture from the
    /// phone's camera.
    pub fn from_phone_bytes(bytes: &[u8]) -> Option<Self> {
        Self::parsed(bytes, PHONE_VIDEO)
    }

    fn parsed(bytes: &[u8], kind: u8) -> Option<Self> {
        let (header, data) = bytes.split_at_checked(Self::HEADER)?;
        (header[0] == kind).then(|| Self {
            key: header[1] & 1 != 0,
            pts_us: u64::from_be_bytes(header[2..].try_into().expect("8 bytes")),
            data: data.to_vec(),
        })
    }
}

/// The cover named by `Media::art`, sent once per phone when it changes: a
/// small image (at most 256 pixels a side), base64-encoded.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MediaArt {
    /// The same as `Media::art`.
    pub id: String,
    /// Always `image/jpeg` for now.
    pub mime: String,
    pub data: String,
}

/// Reply to `take_screenshot`: where the screenshot was saved on the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Screenshot {
    pub path: String,
}

/// The answer to `run_check`: what `yaedock check` finds on the PC, so the
/// phone can say why a feature is missing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CheckReport {
    pub checks: Vec<CheckResult>,
}

/// One line of the system check.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CheckResult {
    /// What was checked, such as "Brightness" or "Game FPS".
    pub area: String,
    pub outcome: CheckOutcome,
    pub summary: String,
    /// What to do about a warning or failure.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fix: Option<String>,
    /// A command to run on the PC (it may need root) that fixes it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckOutcome {
    Ok,
    /// Works, with a limitation.
    Warn,
    /// Will not work until fixed.
    Fail,
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

/// Moves the pointer by whole pixels on the PC. The phone adds up small moves
/// and sends at most one of these a frame.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PointerMove {
    pub dx: i32,
    pub dy: i32,
}

/// Scrolls by wheel notches, which may be fractions for smooth scrolling.
/// Positive `dy` scrolls down, positive `dx` right.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PointerScroll {
    #[serde(default)]
    pub dx: f32,
    #[serde(default)]
    pub dy: f32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PointerButton {
    pub button: MouseButton,
    pub down: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MouseButton {
    Left,
    Right,
    Middle,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct KeyPress {
    pub key: Key,
    pub down: bool,
}

/// The keys the phone can press by name: the ones its keyboard lacks.
/// Letters, digits and symbols go as `type_text`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Key {
    Esc,
    Tab,
    Enter,
    Backspace,
    Delete,
    Insert,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
    Ctrl,
    Alt,
    Shift,
    Super,
    /// A key from a newer phone that this PC does not know; ignored.
    #[serde(other)]
    Unknown,
}

/// Everything held on the phone's controller at once. Sticks go from -1 to 1,
/// with up and left negative; triggers from 0 to 1. Sent whenever anything
/// changes, at most once a frame, so a lost or late one is soon made good.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GamepadState {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub buttons: Vec<GamepadButton>,
    #[serde(default)]
    pub lx: f32,
    #[serde(default)]
    pub ly: f32,
    #[serde(default)]
    pub rx: f32,
    #[serde(default)]
    pub ry: f32,
    #[serde(default)]
    pub lt: f32,
    #[serde(default)]
    pub rt: f32,
}

/// The buttons of an Xbox-style controller; the D-pad's four are buttons too.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GamepadButton {
    A,
    B,
    X,
    Y,
    Lb,
    Rb,
    View,
    Menu,
    Guide,
    LeftStick,
    RightStick,
    Up,
    Down,
    Left,
    Right,
    /// A button from a newer phone that this PC does not know; ignored.
    #[serde(other)]
    Unknown,
}

/// How hard the controller should rumble, each motor from 0 to 1, and for how
/// long. Both at 0 stops it; `ms` 0 means until the next one.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GamepadRumble {
    pub strong: f32,
    pub weak: f32,
    #[serde(default)]
    pub ms: u32,
}

/// Text to type. The PC types each character with its keyboard layout's keys,
/// holding Shift where needed, and skips characters it has no key for. Keys
/// held with `key` stay held, so Ctrl then `c` copies.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TypeText {
    pub text: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn phone_camera_messages_round_trip() {
        let start: ClientMessage =
            serde_json::from_value(json!({"type": "start_phone_camera"})).unwrap();
        assert_eq!(
            start,
            ClientMessage::StartPhoneCamera(StartPhoneCamera { rotation: 0 })
        );
        let turned = ClientMessage::StartPhoneCamera(StartPhoneCamera { rotation: 90 });
        assert_eq!(
            serde_json::to_value(turned).unwrap(),
            json!({"type": "start_phone_camera", "rotation": 90})
        );
        assert_eq!(
            serde_json::to_value(ClientMessage::StopPhoneCamera).unwrap(),
            json!({"type": "stop_phone_camera"})
        );
        assert_eq!(
            serde_json::to_value(ClientMessage::UsbPlugged).unwrap(),
            json!({"type": "usb_plugged"})
        );
        assert_eq!(
            serde_json::to_value(ClientMessage::UsbOff).unwrap(),
            json!({"type": "usb_off"})
        );
        let off = ServerMessage::PhoneCamera(PhoneCamera {
            state: PhoneCameraState::Off,
            message: Some("in use".into()),
        });
        assert_eq!(
            serde_json::to_value(off).unwrap(),
            json!({"type": "phone_camera", "state": "off", "message": "in use"})
        );
        let seen = ServerMessage::PhoneCameraSeen(PhoneCameraSeen { time: 1_500_000 });
        assert_eq!(
            serde_json::to_value(seen).unwrap(),
            json!({"type": "phone_camera_seen", "time": 1_500_000})
        );
        let picture = VideoFrame {
            key: true,
            pts_us: 0x0102,
            data: vec![0, 0, 1, 0x65],
        };
        let bytes = picture.to_phone_bytes();
        assert_eq!(bytes[..10], [3, 1, 0, 0, 0, 0, 0, 0, 1, 2]);
        assert_eq!(VideoFrame::from_phone_bytes(&bytes), Some(picture));
        // Not the screen's video, and the screen's is not the camera's.
        assert_eq!(VideoFrame::from_bytes(&bytes), None);
        assert_eq!(VideoFrame::from_phone_bytes(&[1; 12]), None);
        assert_eq!(VideoFrame::from_phone_bytes(&[3, 0]), None);
    }

    #[test]
    fn input_messages_round_trip() {
        let cases = [
            (
                json!({"type": "pointer_move", "dx": -3, "dy": 12}),
                ClientMessage::PointerMove(PointerMove { dx: -3, dy: 12 }),
            ),
            (
                json!({"type": "pointer_scroll", "dx": 0.0, "dy": 1.5}),
                ClientMessage::PointerScroll(PointerScroll { dx: 0.0, dy: 1.5 }),
            ),
            (
                json!({"type": "pointer_button", "button": "right", "down": true}),
                ClientMessage::PointerButton(PointerButton {
                    button: MouseButton::Right,
                    down: true,
                }),
            ),
            (
                json!({"type": "key", "key": "page_up", "down": false}),
                ClientMessage::Key(KeyPress {
                    key: Key::PageUp,
                    down: false,
                }),
            ),
            (
                json!({"type": "key", "key": "f12", "down": true}),
                ClientMessage::Key(KeyPress {
                    key: Key::F12,
                    down: true,
                }),
            ),
            (
                json!({"type": "type_text", "text": "Hi!"}),
                ClientMessage::TypeText(TypeText { text: "Hi!".into() }),
            ),
            (
                json!({"type": "gamepad", "buttons": ["a", "lb", "up"], "lx": -1.0, "ly": 0.5, "rx": 0.0, "ry": 0.0, "lt": 0.0, "rt": 1.0}),
                ClientMessage::Gamepad(GamepadState {
                    buttons: vec![GamepadButton::A, GamepadButton::Lb, GamepadButton::Up],
                    lx: -1.0,
                    ly: 0.5,
                    rt: 1.0,
                    ..Default::default()
                }),
            ),
            (json!({"type": "gamepad_off"}), ClientMessage::GamepadOff),
        ];
        for (wire, message) in cases {
            assert_eq!(serde_json::to_value(&message).unwrap(), wire);
            assert_eq!(
                serde_json::from_value::<ClientMessage>(wire).unwrap(),
                message
            );
        }
        // A key from a newer phone still parses, and is ignored.
        let newer: ClientMessage =
            serde_json::from_value(json!({"type": "key", "key": "media_play", "down": true}))
                .unwrap();
        assert_eq!(
            newer,
            ClientMessage::Key(KeyPress {
                key: Key::Unknown,
                down: true
            })
        );
        // Scroll may leave out an axis.
        let down: ClientMessage =
            serde_json::from_value(json!({"type": "pointer_scroll", "dy": 2.0})).unwrap();
        assert_eq!(
            down,
            ClientMessage::PointerScroll(PointerScroll { dx: 0.0, dy: 2.0 })
        );
        // A controller at rest may leave out everything, and a newer button is ignored.
        let rest: ClientMessage =
            serde_json::from_value(json!({"type": "gamepad", "buttons": ["share"]})).unwrap();
        assert_eq!(
            rest,
            ClientMessage::Gamepad(GamepadState {
                buttons: vec![GamepadButton::Unknown],
                ..Default::default()
            })
        );
        let rumble = ServerMessage::GamepadRumble(GamepadRumble {
            strong: 0.5,
            weak: 0.25,
            ms: 200,
        });
        assert_eq!(
            serde_json::to_value(rumble).unwrap(),
            json!({"type": "gamepad_rumble", "strong": 0.5, "weak": 0.25, "ms": 200})
        );
    }

    #[test]
    fn phone_mic_messages_round_trip() {
        let start: ClientMessage =
            serde_json::from_value(json!({"type": "start_phone_mic"})).unwrap();
        assert_eq!(start, ClientMessage::StartPhoneMic);
        assert_eq!(
            serde_json::to_value(ClientMessage::StopPhoneMic).unwrap(),
            json!({"type": "stop_phone_mic"})
        );
        let on = ServerMessage::PhoneMic(PhoneMic {
            state: PhoneMicState::On,
            message: None,
        });
        assert_eq!(
            serde_json::to_value(&on).unwrap(),
            json!({"type": "phone_mic", "state": "on"})
        );
        let off: ServerMessage = serde_json::from_value(
            json!({"type": "phone_mic", "state": "off", "message": "in use"}),
        )
        .unwrap();
        assert_eq!(
            off,
            ServerMessage::PhoneMic(PhoneMic {
                state: PhoneMicState::Off,
                message: Some("in use".into()),
            })
        );

        assert_eq!(phone_sound(&[2, 0x34, 0x12]), Some(&[0x34, 0x12][..]));
        assert_eq!(phone_sound(&[2]), Some(&[][..]));
        assert_eq!(phone_opus(&[4, 0x78, 1]), Some(&[0x78, 1][..]));
        assert_eq!(phone_opus(&[2, 0x78, 1]), None);
        assert_eq!(phone_sound(&[4, 0x78, 1]), None);
        assert_eq!(phone_sound(&[1, 0, 0]), None);
        assert_eq!(phone_sound(&[]), None);
    }

    #[test]
    fn phone_speaker_messages_round_trip() {
        let start: ClientMessage =
            serde_json::from_value(json!({"type": "start_phone_speaker"})).unwrap();
        assert_eq!(start, ClientMessage::StartPhoneSpeaker);
        assert_eq!(
            serde_json::to_value(ClientMessage::StopPhoneSpeaker).unwrap(),
            json!({"type": "stop_phone_speaker"})
        );
        let on = ServerMessage::PhoneSpeaker(PhoneSpeaker {
            state: PhoneSpeakerState::On,
            message: None,
        });
        assert_eq!(
            serde_json::to_value(&on).unwrap(),
            json!({"type": "phone_speaker", "state": "on"})
        );
        let off: ServerMessage = serde_json::from_value(
            json!({"type": "phone_speaker", "state": "off", "message": "in use"}),
        )
        .unwrap();
        assert_eq!(
            off,
            ServerMessage::PhoneSpeaker(PhoneSpeaker {
                state: PhoneSpeakerState::Off,
                message: Some("in use".into()),
            })
        );
        assert_eq!(PcFrame::Sound(vec![1, 2, 3, 4]).to_bytes(), [5, 1, 2, 3, 4]);
    }

    #[test]
    fn screen_messages_round_trip() {
        let start: ClientMessage = serde_json::from_value(json!({"type": "start_screen"})).unwrap();
        assert_eq!(start, ClientMessage::StartScreen(StartScreen::default()));
        let extend = ClientMessage::StartScreen(StartScreen {
            mode: ScreenMode::Extend,
            width: Some(2340),
            height: Some(1080),
        });
        let text = json!({"type": "start_screen", "mode": "extend", "width": 2340, "height": 1080});
        assert_eq!(serde_json::to_value(&extend).unwrap(), text);
        assert_eq!(
            serde_json::from_value::<ClientMessage>(text).unwrap(),
            extend
        );
        let smaller: ClientMessage =
            serde_json::from_value(json!({"type": "start_screen", "height": 720})).unwrap();
        assert_eq!(
            serde_json::to_value(&smaller).unwrap(),
            json!({"type": "start_screen", "height": 720})
        );
        let on = ServerMessage::Screen(Screen {
            state: ScreenState::On,
            width: Some(1920),
            height: Some(1080),
            message: None,
            locked: false,
        });
        assert_eq!(
            serde_json::to_value(&on).unwrap(),
            json!({"type": "screen", "state": "on", "width": 1920, "height": 1080})
        );
        let locked: ServerMessage =
            serde_json::from_value(json!({"type": "screen", "state": "on", "locked": true}))
                .unwrap();
        assert!(matches!(
            locked,
            ServerMessage::Screen(Screen { locked: true, .. })
        ));
        let off: ServerMessage =
            serde_json::from_value(json!({"type": "screen", "state": "off", "message": "denied"}))
                .unwrap();
        assert!(matches!(
            off,
            ServerMessage::Screen(Screen {
                state: ScreenState::Off,
                ..
            })
        ));
    }

    #[test]
    fn video_frames_round_trip() {
        let frame = VideoFrame {
            key: true,
            pts_us: 3_600_000_033,
            data: vec![0, 0, 0, 1, 9, 0x10],
        };
        let bytes = frame.to_bytes();
        assert_eq!(&bytes[..2], &[1, 1]);
        assert_eq!(bytes.len(), 16);
        assert_eq!(VideoFrame::from_bytes(&bytes), Some(frame));
        assert_eq!(
            VideoFrame::from_bytes(&[2, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
            None
        );
        assert_eq!(VideoFrame::from_bytes(&[1, 0]), None);
    }

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
                low_1: Some(48.2),
                low_0_1: None,
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
        assert_eq!(value["game"]["low_1"], 48.2_f32 as f64);
        assert!(value["game"].get("low_0_1").is_none());
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
            shortcuts: vec![Shortcut {
                id: "steam".into(),
                name: "Steam".into(),
            }],
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
        assert_eq!(
            serde_json::to_value(ClientMessage::SetOverlay(SetOverlay { visible: false })).unwrap(),
            json!({"type": "set_overlay", "visible": false})
        );
        // Hidden is not the same as unknown, so `false` has to be sent.
        let hidden = State {
            overlay: Some(false),
            ..State::default()
        };
        let value = serde_json::to_value(&hidden).unwrap();
        assert_eq!(value["overlay"], json!(false));
        assert!(old.overlay.is_none());
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

    #[test]
    fn check_round_trip() {
        assert_eq!(
            serde_json::from_value::<ClientMessage>(json!({"type": "run_check"})).unwrap(),
            ClientMessage::RunCheck
        );
        let msg = ServerMessage::CheckReport(CheckReport {
            checks: vec![
                CheckResult {
                    area: "Volume".into(),
                    outcome: CheckOutcome::Ok,
                    summary: "PipeWire (wpctl) at 31%".into(),
                    fix: None,
                    command: None,
                },
                CheckResult {
                    area: "GPU names".into(),
                    outcome: CheckOutcome::Warn,
                    summary: "Shown by PCI id".into(),
                    fix: Some("Install hwdata.".into()),
                    command: Some("sudo pacman -S --needed hwdata".into()),
                },
            ],
        });
        let value = serde_json::to_value(&msg).unwrap();
        assert_eq!(
            value,
            json!({"type": "check_report", "checks": [
                {"area": "Volume", "outcome": "ok", "summary": "PipeWire (wpctl) at 31%"},
                {"area": "GPU names", "outcome": "warn", "summary": "Shown by PCI id",
                 "fix": "Install hwdata.", "command": "sudo pacman -S --needed hwdata"}
            ]})
        );
        assert_eq!(serde_json::from_value::<ServerMessage>(value).unwrap(), msg);
    }

    #[test]
    fn update_round_trip() {
        let ask = ClientMessage::UpdatePc(UpdatePc {
            version: "0.2.0".into(),
        });
        let text = serde_json::to_string(&ask).unwrap();
        assert_eq!(text, r#"{"type":"update_pc","version":"0.2.0"}"#);
        assert_eq!(serde_json::from_str::<ClientMessage>(&text).unwrap(), ask);
        let progress = ServerMessage::UpdateProgress(UpdateProgress {
            stage: UpdateStage::Downloading,
            percent: Some(42),
        });
        let text = serde_json::to_string(&progress).unwrap();
        assert_eq!(
            text,
            r#"{"type":"update_progress","stage":"downloading","percent":42}"#
        );
        assert_eq!(
            serde_json::from_str::<ServerMessage>(&text).unwrap(),
            progress
        );
        let restarting: ServerMessage =
            serde_json::from_str(r#"{"type":"update_progress","stage":"restarting"}"#).unwrap();
        assert_eq!(
            restarting,
            ServerMessage::UpdateProgress(UpdateProgress {
                stage: UpdateStage::Restarting,
                percent: None
            })
        );
    }

    #[test]
    fn media_art_round_trip() {
        let media: Media = serde_json::from_value(json!({
            "player": "spotify", "app": "Spotify", "title": "Song",
            "art": "9f2c1a7e44b0d6e1", "playing": true,
            "can_play_pause": true, "can_next": true, "can_previous": true
        }))
        .unwrap();
        assert_eq!(media.art.as_deref(), Some("9f2c1a7e44b0d6e1"));
        // Left out when the player has no cover.
        let plain = Media { art: None, ..media };
        assert!(serde_json::to_value(&plain).unwrap().get("art").is_none());

        let msg = ServerMessage::MediaArt(MediaArt {
            id: "9f2c1a7e44b0d6e1".into(),
            mime: "image/jpeg".into(),
            data: "/9j/4AAQ".into(),
        });
        let value = serde_json::to_value(&msg).unwrap();
        assert_eq!(
            value,
            json!({"type": "media_art", "id": "9f2c1a7e44b0d6e1", "mime": "image/jpeg", "data": "/9j/4AAQ"})
        );
        assert_eq!(serde_json::from_value::<ServerMessage>(value).unwrap(), msg);
    }
}
