// Protocol messages, mirroring daemon/yaedock-proto and protocol/protocol.md.
// All three must change together.

package com.dragonemperor9480.yaedock.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val PROTOCOL_VERSION = 1

/** Messages sent by the phone to the PC. */
@Serializable
sealed interface ClientMessage

/** Messages sent by the PC to the phone. */
@Serializable
sealed interface ServerMessage

/**
 * First message on every connection. A new phone sends the pairing [code];
 * a paired phone sends the [token] it received in its first [Welcome].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("hello")
data class Hello(
    // Defaults are normally left out of the JSON, but the daemon requires this.
    @EncodeDefault val version: Int = PROTOCOL_VERSION,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val token: String? = null,
    val code: String? = null,
) : ClientMessage

@Serializable
@SerialName("subscribe")
data class Subscribe(
    val groups: List<MetricGroup>,
    @SerialName("interval_ms") val intervalMs: Int,
) : ClientMessage

@Serializable
@SerialName("set_brightness")
data class SetBrightness(
    val display: String,
    /** 0 to 100. */
    val percent: Int,
) : ClientMessage

/** Changes the volume, the mute flag, or both; null fields are left as they are. */
@Serializable
@SerialName("set_volume")
data class SetVolume(
    /** 0 to 150. */
    val percent: Int? = null,
    val muted: Boolean? = null,
) : ClientMessage

/** Changes the default microphone's volume, mute flag, or both. */
@Serializable
@SerialName("set_mic")
data class SetMic(
    val percent: Int? = null,
    val muted: Boolean? = null,
) : ClientMessage

/** Plays sound through another output. */
@Serializable
@SerialName("set_output")
data class SetOutput(
    /** [AudioOutput.id] from the state. */
    val output: String,
) : ClientMessage

/** Changes one program's volume, mute flag, or both. */
@Serializable
@SerialName("set_app_volume")
data class SetAppVolume(
    /** [AppVolume.id] from the state. */
    val app: Int,
    val percent: Int? = null,
    val muted: Boolean? = null,
) : ClientMessage

/** Play, pause or skip on a media player. */
@Serializable
@SerialName("media")
data class MediaControl(
    val action: MediaAction,
    /** [Media.player]; without it, the player the state shows. */
    val player: String? = null,
) : ClientMessage

/** Moves what plays to [positionMs] from the start of the track. */
@Serializable
@SerialName("seek")
data class Seek(
    @SerialName("position_ms") val positionMs: Long,
    /** [Media.player]; without it, the player the state shows. */
    val player: String? = null,
) : ClientMessage

@Serializable
enum class MediaAction {
    @SerialName("play_pause") PLAY_PAUSE,
    @SerialName("next") NEXT,
    @SerialName("previous") PREVIOUS,
}

@Serializable
@SerialName("set_power_profile")
data class SetPowerProfile(
    /** One of [PowerProfiles.available]. */
    val profile: String,
) : ClientMessage

/** Shows or hides MangoHud's overlay on the PC; the frame rate keeps coming either way. */
@Serializable
@SerialName("set_overlay")
data class SetOverlay(
    val visible: Boolean,
) : ClientMessage

/** Saves a screenshot on the PC, silently; the PC answers with [Screenshot]. */
@Serializable
@SerialName("take_screenshot")
data object TakeScreenshot : ClientMessage

/** Starts recording the screen with OBS Studio, opening OBS if needed. */
@Serializable
@SerialName("start_recording")
data object StartRecording : ClientMessage

/**
 * Stops the recording; the PC answers with [RecordingSaved]. With [closeApp]
 * it also closes OBS afterwards, unless OBS is busy with something else.
 */
@Serializable
@SerialName("stop_recording")
data class StopRecording(@SerialName("close_app") val closeApp: Boolean = false) : ClientMessage

/**
 * Mirrors the PC's screen to this phone. The PC answers with [ScreenMirror]
 * messages, and the picture arrives as [VideoFrame]s in binary frames.
 */
@Serializable
@SerialName("start_screen")
data class StartScreen(
    val mode: ScreenMode = ScreenMode.MIRROR,
    /**
     * The largest picture wanted, in pixels. A mirrored monitor is scaled down
     * to fit, keeping its shape; a new display is made this size.
     */
    val width: Int? = null,
    val height: Int? = null,
) : ClientMessage

@Serializable
enum class ScreenMode {
    /** Shows one of the PC's monitors. */
    @SerialName("mirror") MIRROR,

    /** Makes the phone another display of the PC, beside its monitors. */
    @SerialName("extend") EXTEND,
}

/** Stops mirroring the screen. */
@Serializable
@SerialName("stop_screen")
data object StopScreen : ClientMessage

/** Makes this phone one of the PC's microphones; its sound follows in binary frames. */
@Serializable
@SerialName("start_phone_mic")
data object StartPhoneMic : ClientMessage

@Serializable
@SerialName("stop_phone_mic")
data object StopPhoneMic : ClientMessage

/**
 * Makes this phone one of the PC's cameras; its pictures follow in binary
 * frames. The PC turns them [rotation] degrees clockwise to stand upright.
 */
@Serializable
@SerialName("start_phone_camera")
data class StartPhoneCamera(val rotation: Int = 0) : ClientMessage

@Serializable
@SerialName("stop_phone_camera")
data object StopPhoneCamera : ClientMessage

/** Makes this phone one of the PC's speakers, and the PC's default output; sound comes as [PcSound] frames. */
@Serializable
@SerialName("start_phone_speaker")
data object StartPhoneSpeaker : ClientMessage

@Serializable
@SerialName("stop_phone_speaker")
data object StopPhoneSpeaker : ClientMessage

/** Moves the PC's pointer by whole pixels, right and down; at most one a frame. */
@Serializable
@SerialName("pointer_move")
data class PointerMove(val dx: Int, val dy: Int) : ClientMessage

/** Scrolls by wheel notches, down and right; fractions scroll smoothly. */
@Serializable
@SerialName("pointer_scroll")
data class PointerScroll(val dx: Float = 0f, val dy: Float = 0f) : ClientMessage

@Serializable
@SerialName("pointer_button")
data class PointerButton(val button: MouseButton, val down: Boolean) : ClientMessage

@Serializable
enum class MouseButton {
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("middle") MIDDLE,
}

/** Presses ([down]) or lets go of a key phones lack. */
@Serializable
@SerialName("key")
data class KeyPress(val key: Key, val down: Boolean) : ClientMessage

/** The keys the phone presses by name; letters and symbols go as [TypeText]. */
@Serializable
enum class Key {
    @SerialName("esc") ESC,
    @SerialName("tab") TAB,
    @SerialName("enter") ENTER,
    @SerialName("backspace") BACKSPACE,
    @SerialName("delete") DELETE,
    @SerialName("insert") INSERT,
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("home") HOME,
    @SerialName("end") END,
    @SerialName("page_up") PAGE_UP,
    @SerialName("page_down") PAGE_DOWN,
    @SerialName("f1") F1,
    @SerialName("f2") F2,
    @SerialName("f3") F3,
    @SerialName("f4") F4,
    @SerialName("f5") F5,
    @SerialName("f6") F6,
    @SerialName("f7") F7,
    @SerialName("f8") F8,
    @SerialName("f9") F9,
    @SerialName("f10") F10,
    @SerialName("f11") F11,
    @SerialName("f12") F12,
    @SerialName("ctrl") CTRL,
    @SerialName("alt") ALT,
    @SerialName("shift") SHIFT,
    @SerialName("super") SUPER,
}

/** Types [text] on the PC; keys held with [KeyPress] stay held, so Ctrl then "c" copies. */
@Serializable
@SerialName("type_text")
data class TypeText(val text: String) : ClientMessage

/**
 * Everything held on the phone's controller at once: the PC makes the
 * controller at the first one. Sticks go from -1 to 1, up and left negative;
 * triggers from 0 to 1.
 */
@Serializable
@SerialName("gamepad")
data class GamepadState(
    val buttons: List<GamepadButton> = emptyList(),
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val lt: Float = 0f,
    val rt: Float = 0f,
) : ClientMessage

/** The buttons of an Xbox-style controller; the D-pad's four are buttons too. */
@Serializable
enum class GamepadButton {
    @SerialName("a") A,
    @SerialName("b") B,
    @SerialName("x") X,
    @SerialName("y") Y,
    @SerialName("lb") LB,
    @SerialName("rb") RB,
    @SerialName("view") VIEW,
    @SerialName("menu") MENU,
    @SerialName("guide") GUIDE,
    @SerialName("left_stick") LEFT_STICK,
    @SerialName("right_stick") RIGHT_STICK,
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

/** The controller page closed: the PC lets go of everything and removes the controller. */
@Serializable
@SerialName("gamepad_off")
data object GamepadOff : ClientMessage

/** This phone was just plugged into a computer by USB; the PC switches it to the cable if it is that computer. */
@Serializable
@SerialName("usb_plugged")
data object UsbPlugged : ClientMessage

/** The user turned the cable off: the PC stops switching this phone, and lets it go if it is on the cable. */
@Serializable
@SerialName("usb_off")
data object UsbOff : ClientMessage

/** Runs one of the PC's shortcuts. */
@Serializable
@SerialName("run_shortcut")
data class RunShortcut(
    /** [Shortcut.id] from the state. */
    val id: String,
) : ClientMessage

/** Locks, sleeps, restarts or shuts down the PC. */
@Serializable
@SerialName("system_action")
data class RunSystemAction(val action: SystemAction) : ClientMessage

@Serializable
enum class SystemAction {
    @SerialName("lock") LOCK,
    @SerialName("suspend") SUSPEND,
    @SerialName("reboot") REBOOT,
    @SerialName("power_off") POWER_OFF,
}

/** Runs the system check on the PC, as `yaedock check` does; the PC answers with [CheckReport]. */
@Serializable
@SerialName("run_check")
data object RunCheck : ClientMessage

/**
 * Updates the PC to the release [version], the newest one the phone found;
 * the PC answers with [UpdateProgress], then restarts on it.
 */
@Serializable
@SerialName("update_pc")
data class UpdatePc(val version: String) : ClientMessage

@Serializable
@SerialName("ping")
data object ClientPing : ClientMessage

@Serializable
@SerialName("pong")
data object ClientPong : ClientMessage

/** Reply to a successful [Hello]. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val version: Int,
    val hostname: String,
    val os: String,
    @SerialName("cpu_model") val cpuModel: String,
    val cores: Int,
    /** Temperature sensor names, the keys of [Stats.temps]. */
    val sensors: List<String>,
    /** Long-lived device token, present only when pairing with a code. */
    val token: String? = null,
    val state: State,
    /** Graphics cards; their readings arrive in [Stats.gpus] under the same ids. */
    val gpus: List<GpuInfo> = emptyList(),
    /** Whether [TakeScreenshot] works on this PC. */
    val screenshot: Boolean = false,
    /** Whether [StartRecording] works (OBS Studio is set up; it may be closed). */
    val recorder: Boolean = false,
    /** Whether [StartScreen] works (the desktop can share a monitor and encode it). */
    val screen: Boolean = false,
    /** Whether [StartScreen] can also [ScreenMode.EXTEND]: the desktop can make a virtual display. */
    @SerialName("screen_extend") val screenExtend: Boolean = false,
    /** Whether [StartPhoneMic] works: the PC can make a microphone for this phone. */
    @SerialName("phone_mic") val phoneMic: Boolean = false,
    /** Whether the PC also takes this phone's sound as Opus, which is far smaller. */
    @SerialName("phone_mic_opus") val phoneMicOpus: Boolean = false,
    /** Whether [StartPhoneCamera] works: the PC can make a camera for this phone. */
    @SerialName("phone_camera") val phoneCamera: Boolean = false,
    /** Whether [StartPhoneSpeaker] works: the PC can make a speaker that plays on this phone. */
    @SerialName("phone_speaker") val phoneSpeaker: Boolean = false,
    /** Whether the PC serves phones over a USB cable, and understands [UsbPlugged] and [UsbOff]. */
    @SerialName("usb_cable") val usbCable: Boolean = false,
    /** Whether the PC takes [PointerMove], [KeyPress], [TypeText] and the rest: it can make a keyboard and mouse. */
    val input: Boolean = false,
    /** Whether the PC takes [GamepadState]: it can make a game controller for this phone. */
    val gamepad: Boolean = false,
    /** What [RunSystemAction] may do on this PC. */
    @SerialName("system_actions") val systemActions: List<SystemAction> = emptyList(),
    /** The YaeDock version on the PC; empty from a PC too old to update from the phone. */
    @SerialName("daemon_version") val daemonVersion: String = "",
    /** Why [UpdatePc] cannot work on this PC, such as a program from a package manager; null when it can. */
    @SerialName("cannot_update") val cannotUpdate: String? = null,
    /** The USB cable's speed in megabits a second (480 for USB 2.0, 5000 and up for USB 3); only over the cable. */
    @SerialName("usb_speed_mbps") val usbSpeedMbps: Int? = null,
) : ServerMessage

/** Whether this phone is one of the PC's cameras, after [StartPhoneCamera] and whenever it changes. */
@Serializable
@SerialName("phone_camera")
data class PhoneCamera(
    val state: PhoneCameraState,
    /** Why it went [PhoneCameraState.OFF], when it was not this phone's [StopPhoneCamera]. */
    val message: String? = null,
) : ServerMessage

/** How far the PC has got with this phone's pictures: when the newest it has was filmed, in microseconds. */
@Serializable
@SerialName("phone_camera_seen")
data class PhoneCameraSeen(val time: Long) : ServerMessage

/** A game asked the controller to rumble: each motor from 0 to 1, for [ms] (0: until the next one). */
@Serializable
@SerialName("gamepad_rumble")
data class GamepadRumble(val strong: Float, val weak: Float, val ms: Int = 0) : ServerMessage

@Serializable
enum class PhoneCameraState {
    /** The PC has the camera and takes this phone's pictures. */
    @SerialName("on") ON,

    @SerialName("off") OFF,
}

/** Whether this phone is one of the PC's microphones, after [StartPhoneMic] and whenever it changes. */
@Serializable
@SerialName("phone_mic")
data class PhoneMic(
    val state: PhoneMicState,
    /** Why it went [PhoneMicState.OFF], when it was not this phone's [StopPhoneMic]. */
    val message: String? = null,
) : ServerMessage

@Serializable
enum class PhoneMicState {
    /** The PC has the microphone and takes this phone's sound. */
    @SerialName("on") ON,

    @SerialName("off") OFF,
}

/** Whether this phone is one of the PC's speakers, after [StartPhoneSpeaker] and whenever it changes. */
@Serializable
@SerialName("phone_speaker")
data class PhoneSpeaker(
    val state: PhoneSpeakerState,
    /** Why it went [PhoneSpeakerState.OFF], when it was not this phone's [StopPhoneSpeaker]. */
    val message: String? = null,
) : ServerMessage

@Serializable
enum class PhoneSpeakerState {
    /** The PC plays through this phone, and [PcSound] frames follow. */
    @SerialName("on") ON,

    @SerialName("off") OFF,
}

/**
 * The PC's sound for this phone as a speaker: two channels at 48 000 samples
 * a second, interleaved left then right, each a signed 16-bit little-endian
 * number. It arrives outside JSON, as a binary frame of kind 5.
 */
object PcSound {
    const val KIND: Byte = 5
    const val RATE = 48_000

    /** Where the sound starts in [bytes], or -1 if they are not the PC's sound. */
    fun offset(bytes: ByteArray): Int = if (bytes.isNotEmpty() && bytes[0] == KIND) 1 else -1
}

/** How mirroring the screen stands, after [StartScreen] and whenever it changes. */
@Serializable
@SerialName("screen")
data class ScreenMirror(
    val state: ScreenState,
    /** The picture's size in pixels while [ScreenState.ON], when the PC knows it. */
    val width: Int? = null,
    val height: Int? = null,
    /** Why it went [ScreenState.OFF], when it was not this phone's [StopScreen]. */
    val message: String? = null,
    /** While [ScreenState.ON]: the PC's screen is locked, so the picture is black. */
    val locked: Boolean = false,
) : ServerMessage

@Serializable
enum class ScreenState {
    /** The PC is asking its user whether to share the screen. */
    @SerialName("asking") ASKING,

    /** [VideoFrame]s follow. */
    @SerialName("on") ON,

    @SerialName("off") OFF,
}

/**
 * One picture of the mirrored screen: an H.264 access unit in Annex B form.
 * It arrives outside JSON, as a binary frame.
 */
class VideoFrame(
    /** A picture that needs no earlier one and carries the stream's settings; decoding starts at one. */
    val key: Boolean,
    /** When it was captured, in microseconds on the PC's encoder clock. */
    val ptsUs: Long,
    /** The whole binary frame; the picture starts at [offset]. */
    val bytes: ByteArray,
    val offset: Int,
) {
    val size: Int get() = bytes.size - offset

    companion object {
        private const val KIND: Byte = 1
        private const val HEADER = 10

        /** The frame in [bytes] (kind, flags, the time as a big-endian long, the picture), or null if they are not screen video. */
        fun fromBytes(bytes: ByteArray): VideoFrame? {
            if (bytes.size < HEADER || bytes[0] != KIND) return null
            var pts = 0L
            for (i in 2 until HEADER) pts = (pts shl 8) or (bytes[i].toLong() and 0xff)
            return VideoFrame(key = bytes[1].toInt() and 1 != 0, ptsUs = pts, bytes = bytes, offset = HEADER)
        }
    }
}

/** Where the PC saved the recording [StopRecording] ended. */
@Serializable
@SerialName("recording_saved")
data class RecordingSaved(val path: String) : ServerMessage

/** The cover [Media.art] names: a small JPEG, base64-encoded in [data]. */
@Serializable
@SerialName("media_art")
data class MediaArt(val id: String, val mime: String = "image/jpeg", val data: String) : ServerMessage

/** Where the PC saved the screenshot [TakeScreenshot] asked for. */
@Serializable
@SerialName("screenshot")
data class Screenshot(val path: String) : ServerMessage

/** How far an [UpdatePc] has got. */
@Serializable
@SerialName("update_progress")
data class UpdateProgress(
    val stage: UpdateStage,
    /** How much of the download is done, while [UpdateStage.DOWNLOADING]. */
    val percent: Int? = null,
) : ServerMessage

@Serializable
enum class UpdateStage {
    @SerialName("downloading") DOWNLOADING,
    @SerialName("verifying") VERIFYING,
    @SerialName("installing") INSTALLING,
    /** Installed; the PC restarts now and the link drops for a moment. */
    @SerialName("restarting") RESTARTING,
}

/** What the system check found on the PC, in the order it checked. */
@Serializable
@SerialName("check_report")
data class CheckReport(val checks: List<CheckResult>) : ServerMessage

/** One line of the system check. */
@Serializable
data class CheckResult(
    /** What was checked, such as "Brightness" or "Game FPS". */
    val area: String,
    /** An outcome added by a newer daemon reads as a warning. */
    val outcome: CheckOutcome = CheckOutcome.WARN,
    val summary: String,
    /** What to do about a warning or failure. */
    val fix: String? = null,
    /** A command to run on the PC that fixes it; it may need root. */
    val command: String? = null,
)

@Serializable
enum class CheckOutcome {
    @SerialName("ok") OK,
    /** Works, with a limitation. */
    @SerialName("warn") WARN,
    /** Will not work until fixed. */
    @SerialName("fail") FAIL,
}

@Serializable
data class GpuInfo(val id: String, val name: String)

/** One sample of system metrics. Groups the phone has not subscribed to are null. */
@Serializable
@SerialName("stats")
data class Stats(
    /** Unix time in milliseconds. */
    val ts: Long,
    /** Seconds since boot. */
    val uptime: Long,
    val cpu: Cpu? = null,
    val mem: Mem? = null,
    /** Degrees Celsius by sensor name. */
    val temps: Map<String, Float>? = null,
    val net: Net? = null,
    val disk: Disk? = null,
    /** Each drive's space, in the `disk` group. */
    val drives: List<Drive>? = null,
    val gpus: List<GpuStats>? = null,
    /** The game running right now, if its frame rate is known (through MangoHud). */
    val game: Game? = null,
    /** The laptop battery; null on PCs without one. */
    val battery: Battery? = null,
) : ServerMessage {
    /** Whether any metric group is filled in; stats for a phone subscribed to none have only the time. */
    val hasMetrics: Boolean
        get() = cpu != null || mem != null || temps != null || net != null || disk != null || drives != null ||
            gpus != null || game != null || battery != null
}

/** The laptop battery (all batteries together, if there are several). */
@Serializable
data class Battery(
    /** 0 to 100. */
    val percent: Float,
    val state: BatteryState = BatteryState.UNKNOWN,
    /** Until empty while discharging, until full while charging. */
    @SerialName("seconds_left") val secondsLeft: Long? = null,
    @SerialName("power_w") val powerW: Float? = null,
    /** Full capacity now, in percent of the capacity when new. */
    val health: Float? = null,
)

@Serializable
enum class BatteryState {
    @SerialName("charging") CHARGING,
    @SerialName("discharging") DISCHARGING,
    @SerialName("full") FULL,
    @SerialName("not_charging") NOT_CHARGING,

    /** Also a state added by a newer daemon. */
    @SerialName("unknown") UNKNOWN,
}

/** A running game's frame rate, as MangoHud measures it. */
@Serializable
data class Game(
    /** The game's program name, such as `eldenring.exe`. */
    val name: String,
    val fps: Float,
    /** Milliseconds per frame. */
    val frametime: Float,
    /** Frames per second in the slowest 1% of frames over the last minute. */
    @SerialName("low_1") val low1: Float? = null,
    /** The same for the slowest 0.1%, once the minute holds enough frames. */
    @SerialName("low_0_1") val low01: Float? = null,
)

/**
 * One graphics card's readings. What a GPU or its driver cannot report is null.
 * A [sleeping] GPU is powered down and was not woken up to be read.
 */
@Serializable
data class GpuStats(
    val id: String,
    /** Percent busy, 0 to 100. */
    val usage: Float? = null,
    /** Degrees Celsius. */
    val temp: Float? = null,
    /** Video memory in bytes; integrated GPUs share system RAM and report none. */
    @SerialName("vram_used") val vramUsed: Long? = null,
    @SerialName("vram_total") val vramTotal: Long? = null,
    @SerialName("clock_mhz") val clockMhz: Int? = null,
    val sleeping: Boolean = false,
)

/**
 * Current brightness, volume, media and power profile, sent after any change
 * from any source. What the PC cannot control is null or empty.
 */
@Serializable
@SerialName("state")
data class State(
    val displays: List<DisplayState> = emptyList(),
    val volume: VolumeState? = null,
    /** The default microphone. */
    val mic: VolumeState? = null,
    /** The audio outputs to choose from; [AudioOutput.default] marks the one in use. */
    val outputs: List<AudioOutput> = emptyList(),
    /** Programs playing sound right now. */
    val apps: List<AppVolume> = emptyList(),
    val media: Media? = null,
    val power: PowerProfiles? = null,
    /** Shortcut buttons set up on the PC; the commands stay there. */
    val shortcuts: List<Shortcut> = emptyList(),
    /** The screen recorder, while OBS is running. */
    val recording: Recording? = null,
    /** Whether MangoHud's overlay shows on the PC, while game frame rates are on. */
    val overlay: Boolean? = null,
) : ServerMessage

/** A shortcut button set up on the PC. */
@Serializable
data class Shortcut(val id: String, val name: String)

/** Whether OBS is recording, and for how long so far. */
@Serializable
data class Recording(
    val active: Boolean = false,
    val paused: Boolean = false,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

/** An audio output, such as the built-in speakers or a Bluetooth headset. */
@Serializable
data class AudioOutput(
    val id: String,
    val name: String,
    /** Where on the device the sound goes, such as "Headphones". */
    val port: String? = null,
    val default: Boolean = false,
)

/** One program's sound stream. */
@Serializable
data class AppVolume(
    val id: Int,
    val name: String,
    /** 0 to 150. */
    val percent: Int,
    val muted: Boolean,
)

/** What a media player is playing. */
@Serializable
data class Media(
    val player: String,
    /** The player's own name, such as `Spotify`. */
    val app: String,
    val title: String = "",
    val artist: String = "",
    /** Names the cover, which arrives as [MediaArt]; null when the player gives none. */
    val art: String? = null,
    val playing: Boolean = false,
    @SerialName("can_play_pause") val canPlayPause: Boolean = false,
    @SerialName("can_next") val canNext: Boolean = false,
    @SerialName("can_previous") val canPrevious: Boolean = false,
    /** The track's length; null when the player does not say. */
    @SerialName("length_ms") val lengthMs: Long? = null,
    /** How far it has played when this state was sent; advance it while [playing]. */
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("can_seek") val canSeek: Boolean = false,
    /**
     * When [positionMs] arrived ([android.os.SystemClock.elapsedRealtime]),
     * to count on from; set on the phone, never sent. See [Media.stamped].
     */
    @Transient val receivedAt: Long = 0,
) {
    /**
     * This media as it arrived at [now], keeping [old]'s time when nothing
     * changed: the PC resends the same position while a track plays on.
     */
    fun stamped(old: Media?, now: Long): Media =
        if (old != null && old.copy(receivedAt = 0) == this) old else copy(receivedAt = now)
}

/** The PC's power profile, such as `balanced`, and the ones it offers. */
@Serializable
data class PowerProfiles(
    val active: String,
    val available: List<String>,
)

@Serializable
@SerialName("error")
data class Error(
    val code: ErrorCode = ErrorCode.UNKNOWN,
    val message: String,
) : ServerMessage

@Serializable
@SerialName("ping")
data object ServerPing : ServerMessage

@Serializable
@SerialName("pong")
data object ServerPong : ServerMessage

/** CPU usage in percent, 0 to 100. */
@Serializable
data class Cpu(val total: Float, val cores: List<Float>)

/** Memory in bytes. */
@Serializable
data class Mem(val used: Long, val total: Long)

/** Network throughput in bytes per second. */
@Serializable
data class Net(val rx: Long, val tx: Long)

/** Disk throughput in bytes per second. */
@Serializable
data class Disk(val read: Long, val write: Long)

/** A drive the PC has mounted, and how much room is on it. */
@Serializable
data class Drive(
    /** "System" for the drive the PC runs from, otherwise the name it is mounted under. */
    val name: String,
    /** Where it is mounted, such as `/` or `/run/media/me/Backup`. */
    val mount: String,
    /** Its size in bytes. */
    val total: Long,
    /** Bytes the PC's user can still write. */
    val free: Long,
)

@Serializable
data class DisplayState(
    val id: String,
    val name: String,
    /** 0 to 100. */
    val percent: Int,
)

@Serializable
data class VolumeState(
    /** 0 to 150. */
    val percent: Int,
    val muted: Boolean,
)

@Serializable
enum class MetricGroup {
    @SerialName("cpu") CPU,
    @SerialName("mem") MEM,
    @SerialName("temps") TEMPS,
    @SerialName("net") NET,
    @SerialName("disk") DISK,
    @SerialName("gpu") GPU,
    @SerialName("game") GAME,
    @SerialName("battery") BATTERY,
}

@Serializable
enum class ErrorCode {
    @SerialName("bad_request") BAD_REQUEST,
    @SerialName("unauthorized") UNAUTHORIZED,
    @SerialName("unsupported_version") UNSUPPORTED_VERSION,
    @SerialName("unsupported_display") UNSUPPORTED_DISPLAY,
    @SerialName("backend_failed") BACKEND_FAILED,

    /** A code added by a newer daemon. */
    UNKNOWN,
}
