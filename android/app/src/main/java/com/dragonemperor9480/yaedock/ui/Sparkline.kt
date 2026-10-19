package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A line of recent values, newest on the right. The vertical scale runs from 0
 * to [max], or to the largest value when [max] is null (for rates).
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    max: Float? = null,
    capacity: Int = 60,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val top = (max ?: values.max()).coerceAtLeast(1f)
        val step = size.width / (capacity - 1)
        val start = size.width - step * (values.size - 1)
        fun point(i: Int) = Offset(
            start + step * i,
            size.height * (1f - (values[i] / top).coerceIn(0f, 1f)),
        )
        val line = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until values.size) lineTo(point(i).x, point(i).y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(point(values.lastIndex).x, size.height)
            lineTo(start, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
    }
}
