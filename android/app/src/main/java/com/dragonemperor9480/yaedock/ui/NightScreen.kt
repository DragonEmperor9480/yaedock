package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.Media
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.Welcome
import kotlin.math.roundToInt

/** Dimmer than the dock's usual colours, for a dark bedroom. */
private val NightText = Color(0xFFD5DDE8)
private val NightValue = Color(0xFFC9D2DE)
private val NightTeal = Color(0xFF3FA88F)
private val NightAmber = Color(0xFFC98A2E)
private val NightTrack = Color(0xFF1A1F27)

/** One small stat: its [fraction] filled in a tiny ring, or an empty ring without one. */
private class NightStat(val label: String, val value: String, val fraction: Float?, val color: Color = NightTeal)

/**
 * The always-on dock: true black (an OLED screen lights no pixels there), a
 * thin clock with the hours in white and the minutes in teal, and a line each
 * for the date, what is playing and the PC. A tap anywhere wakes the dock.
 */
@Composable
fun NightScreen(stats: Stats?, welcome: Welcome?, media: Media?, homeGpu: String, onWake: () -> Unit, modifier: Modifier = Modifier) {
    val clock = rememberClock()
    val hours = clock.time.substringBefore(':')
    val minutes = clock.time.substringAfter(':')
    val playing = media?.takeIf { it.title.isNotEmpty() }?.let { listOf(it.title, it.artist).filter(String::isNotEmpty).joinToString(" · ") }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Wake the dock",
                onClick = onWake,
            ),
    ) {
        val nightStats = nightStats(stats, welcome, homeGpu)
        if (isWide(maxWidth, maxHeight)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(clock.date, fontSize = 15.sp, color = YaeColors.muted, maxLines = 1)
                    playing?.let {
                        Box(Modifier.size(3.dp).clip(CircleShape).background(YaeColors.track))
                        Playing(it, 15.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Digits(hours, NightText, 190.sp, 190.sp)
                    Column(
                        Modifier.width(34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                        repeat(2) { Box(Modifier.size(9.dp).clip(CircleShape).background(NightTeal)) }
                    }
                    Digits(minutes, NightTeal, 190.sp, 190.sp)
                }
                if (nightStats.isEmpty()) {
                    Disconnected()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                        for (stat in nightStats) NightStatRow(stat)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 72.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Digits(hours, NightText, 196.sp, 180.sp)
                    Digits(minutes, NightTeal, 196.sp, 180.sp)
                    Text(clock.date, Modifier.padding(start = 6.dp, top = 28.dp), fontSize = 19.sp, color = NightValue, maxLines = 1)
                    playing?.let {
                        Box(Modifier.padding(start = 6.dp, top = 8.dp)) { Playing(it, 14.sp) }
                    }
                }
                if (nightStats.isEmpty()) {
                    Disconnected()
                } else {
                    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (row in nightStats.chunked(2)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                for (stat in row) {
                                    Box(Modifier.weight(1f)) { NightStatRow(stat) }
                                }
                                if (row.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun nightStats(stats: Stats?, welcome: Welcome?, homeGpu: String): List<NightStat> {
    if (stats == null) return emptyList()
    return buildList {
        stats.cpu?.let { add(NightStat("CPU", "${it.total.roundToInt()}%", it.total / 100f)) }
        welcome?.let { pickHomeGpu(it.gpus, stats.gpus, homeGpu) }?.let { gpu ->
            val reading = stats.gpus?.find { it.id == gpu.id }
            if (reading == null || reading.sleeping) {
                add(NightStat("GPU", "asleep", null))
            } else {
                add(NightStat("GPU", "${(reading.usage ?: 0f).roundToInt()}%", (reading.usage ?: 0f) / 100f))
            }
        }
        stats.mem?.takeIf { it.total > 0 }?.let { mem ->
            val fraction = mem.used.toFloat() / mem.total
            add(NightStat("RAM", "${(fraction * 100).roundToInt()}%", fraction))
        }
        stats.battery?.let { add(NightStat("Battery", "${it.percent.roundToInt()}%", it.percent / 100f, NightAmber)) }
    }
}

@Composable
private fun Digits(text: String, color: Color, size: TextUnit, lineHeight: TextUnit) {
    Text(
        text,
        fontFamily = Grotesk,
        fontWeight = FontWeight.Light,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = (size.value * -0.04f).sp,
        color = color,
        maxLines = 1,
        style = TextStyle(fontFeatureSettings = "tnum"),
    )
}

@Composable
private fun Playing(text: String, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_music), null, Modifier.size(14.dp), tint = NightTeal)
        Text(text, fontSize = size, color = YaeColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Disconnected() {
    Text("Not connected", fontSize = 13.sp, color = YaeColors.muted)
}

@Composable
private fun NightStatRow(stat: NightStat) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(NightTrack, style = Stroke(2.dp.toPx()), radius = size.minDimension / 2 - 2.dp.toPx())
            stat.fraction?.let {
                val inset = 2.dp.toPx()
                drawArc(
                    stat.color,
                    startAngle = -90f,
                    sweepAngle = 360f * it.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = stroke,
                )
            }
        }
        Text(stat.label, fontSize = 13.sp, color = YaeColors.muted, maxLines = 1)
        Text(stat.value, fontFamily = Mono, fontSize = 13.sp, color = NightValue, maxLines = 1)
    }
}
