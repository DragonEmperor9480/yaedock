package com.dragonemperor9480.yaedock.ui

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

/** Turns a sensor key such as `cpu_package` into `CPU package`. */
fun sensorLabel(key: String): String {
    val words = key.split('_').filter { it.isNotEmpty() }
    return words.mapIndexed { i, word ->
        when {
            word in setOf("cpu", "gpu", "nvme", "pch", "acpi") -> word.uppercase()
            i == 0 -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }.joinToString(" ")
}
