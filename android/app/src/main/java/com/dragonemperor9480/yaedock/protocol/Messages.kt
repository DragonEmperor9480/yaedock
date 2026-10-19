// Protocol messages, mirroring daemon/yaedock-proto and protocol/protocol.md.
// All three must change together.

package com.dragonemperor9480.yaedock.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
) : ServerMessage

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
) : ServerMessage

/** A running game's frame rate, as MangoHud measures it. */
@Serializable
data class Game(
    /** The game's program name, such as `eldenring.exe`. */
    val name: String,
    val fps: Float,
    /** Milliseconds per frame. */
    val frametime: Float,
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

/** Current brightness and volume, sent after any change from any source. */
@Serializable
@SerialName("state")
data class State(
    val displays: List<DisplayState> = emptyList(),
    val volume: VolumeState? = null,
) : ServerMessage

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
