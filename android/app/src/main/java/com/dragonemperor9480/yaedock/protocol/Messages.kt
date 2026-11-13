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
data object StartScreen : ClientMessage

/** Stops mirroring the screen. */
@Serializable
@SerialName("stop_screen")
data object StopScreen : ClientMessage

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
    /** What [RunSystemAction] may do on this PC. */
    @SerialName("system_actions") val systemActions: List<SystemAction> = emptyList(),
    /** The YaeDock version on the PC; empty from a PC too old to update from the phone. */
    @SerialName("daemon_version") val daemonVersion: String = "",
    /** Why [UpdatePc] cannot work on this PC, such as a program from a package manager; null when it can. */
    @SerialName("cannot_update") val cannotUpdate: String? = null,
) : ServerMessage

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
    val gpus: List<GpuStats>? = null,
    /** The game running right now, if its frame rate is known (through MangoHud). */
    val game: Game? = null,
    /** The laptop battery; null on PCs without one. */
    val battery: Battery? = null,
) : ServerMessage {
    /** Whether any metric group is filled in; stats for a phone subscribed to none have only the time. */
    val hasMetrics: Boolean
        get() = cpu != null || mem != null || temps != null || net != null || disk != null ||
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
