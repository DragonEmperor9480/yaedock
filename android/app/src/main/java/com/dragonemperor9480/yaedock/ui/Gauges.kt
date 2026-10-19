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

/** Accent colours for each kind of metric, readable on light and dark backgrounds. */
object MetricColors {
    val cpu = Color(0xFF29B6F6)
    val memory = Color(0xFF66BB6A)
    val network = Color(0xFFFFA726)
    val disk = Color(0xFFAB47BC)
    val system = Color(0xFF78909C)
    val cool = Color(0xFF66BB6A)
    val warm = Color(0xFFFFA726)
    val hot = Color(0xFFEF5350)
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
