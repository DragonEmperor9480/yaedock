package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.GpuInfo
import com.dragonemperor9480.yaedock.protocol.GpuStats
import java.util.Locale

/** Formats a byte count with binary units: 512 B, 1.5 KiB, 6.1 GiB. */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

/** Formats seconds as `3d 4h`, `4h 12m` or `12m`. */
/** What the battery is doing, such as "Charging · full in 45m". */
fun batteryStatus(battery: Battery): String {
    val left = battery.secondsLeft?.let { formatUptime(it) }
    return when (battery.state) {
        BatteryState.CHARGING -> listOfNotNull("Charging", left?.let { "full in $it" }).joinToString(" · ")
        BatteryState.DISCHARGING -> listOfNotNull("On battery", left?.let { "$it left" }).joinToString(" · ")
        BatteryState.FULL -> "Full"
        BatteryState.NOT_CHARGING -> "Plugged in, not charging"
        BatteryState.UNKNOWN -> "Battery"
    }
}

/** A recording's length: `0:42`, `12:05`, `1:02:09`. */
fun formatTimer(millis: Long): String {
    val seconds = millis / 1000
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/** Words that say who made a GPU or what kind it is, not which one. */
private val GPU_FILLER = setOf("NVIDIA", "GeForce", "Intel", "AMD", "ATI", "Graphics", "Mobile", "Laptop", "Max-Q")

/**
 * A GPU's name short enough for a card's title, telling a PC's GPUs apart:
 * "NVIDIA GeForce GTX 1050 Mobile" is "GTX 1050", "Intel UHD Graphics 630"
 * is "UHD 630". A name made only of such words stays as it is.
 */
fun shortGpuName(name: String): String {
    val words = name.split(' ').filter(String::isNotEmpty)
    val kept = words.filterIndexed { i, word -> word !in GPU_FILLER && !(word == "GPU" && i > 0) }
    return kept.joinToString(" ").ifEmpty { name }
}

/**
 * The GPU that Home and the night dock show: the one with id [choice], or with
 * none chosen (or one this PC lacks), the busiest awake GPU, else the first.
 */
fun pickHomeGpu(gpus: List<GpuInfo>, readings: List<GpuStats>?, choice: String): GpuInfo? {
    gpus.find { it.id == choice }?.let { return it }
    val awake = gpus.mapNotNull { gpu -> readings?.find { it.id == gpu.id && !it.sleeping }?.let { gpu to it } }
    return awake.maxByOrNull { (_, reading) -> reading.usage ?: 0f }?.first ?: gpus.firstOrNull()
}
