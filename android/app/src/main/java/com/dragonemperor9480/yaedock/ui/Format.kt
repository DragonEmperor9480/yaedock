package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState
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

fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

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
