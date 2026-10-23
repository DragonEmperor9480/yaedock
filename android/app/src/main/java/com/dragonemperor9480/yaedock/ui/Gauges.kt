package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState

/**
 * Colours for each kind of metric. Usage is always the accent, so the dock
 * reads as one instrument; amber and red only mean warm and hot.
 */
object MetricColors {
    val cpu = YaeColors.accent
    val memory = YaeColors.accent
    val network = YaeColors.accent
    val disk = YaeColors.accent
    val system = YaeColors.muted
    val game = YaeColors.accent
    val cool = YaeColors.accent
    val warm = YaeColors.amber
    val hot = YaeColors.red
}

/** Green while charging or above 30%, amber above 15%, red below. */
fun batteryColor(battery: Battery): Color = when {
    battery.state == BatteryState.CHARGING || battery.percent > 30f -> MetricColors.cool
    battery.percent > 15f -> MetricColors.warm
    else -> MetricColors.hot
}

/** Green below 60 °C, amber below 80 °C, red above. */
fun temperatureColor(celsius: Float): Color = when {
    celsius < 60f -> MetricColors.cool
    celsius < 80f -> MetricColors.warm
    else -> MetricColors.hot
}

/** A ring that fills clockwise from the top with [percent] (0 to 100), the value in its centre. */
@Composable
fun RingGauge(percent: Float, color: Color, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    // No animation: values arrive every second, so an animated ring would keep
    // the phone redrawing (at up to 120 frames a second) almost all the time.
    val filled = percent.coerceIn(0f, 100f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                color, -90f, 360f * filled / 100f, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            "${percent.toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One small vertical bar per CPU thread, showing how busy each one is. */
@Composable
fun CoreBars(cores: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
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
