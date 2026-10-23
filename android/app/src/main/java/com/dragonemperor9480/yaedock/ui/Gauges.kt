package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState

/** Mint while charging or above 30%, amber above 15%, red below. */
fun batteryColor(battery: Battery): Color = when {
    battery.state == BatteryState.CHARGING || battery.percent > 30f -> YaeColors.accent
    battery.percent > 15f -> YaeColors.amber
    else -> YaeColors.red
}

/** Mint below 60 °C, amber below 80 °C, red above. */
fun temperatureColor(celsius: Float): Color = when {
    celsius < 60f -> YaeColors.accent
    celsius < 80f -> YaeColors.amber
    else -> YaeColors.red
}

/** One small vertical bar per CPU thread, showing how busy each one is. */
@Composable
fun CoreBars(cores: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val track = YaeColors.track
    Canvas(modifier) {
        if (cores.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val width = (size.width - gap * (cores.size - 1)) / cores.size
        val radius = CornerRadius(width.coerceAtMost(3.dp.toPx()) / 2)
        cores.forEachIndexed { i, usage ->
            val x = i * (width + gap)
            drawRoundRect(track, Offset(x, 0f), Size(width, size.height), radius)
            val filled = size.height * (usage / 100f).coerceIn(0f, 1f)
            drawRoundRect(color, Offset(x, size.height - filled), Size(width, filled), radius)
        }
    }
}
