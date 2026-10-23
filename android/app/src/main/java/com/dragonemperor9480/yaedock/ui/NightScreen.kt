package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.Media
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.Welcome
import kotlin.math.roundToInt

/** Dimmer than the dock's usual colours, for a dark bedroom. */
private val NightText = Color(0xFFD5DDE8)
private val NightBar = Color(0xFF3FA88F)
private val NightAmber = Color(0xFFC98A2E)
private val NightTrack = Color(0xFF1A1F27)
private val NightHint = Color(0xFF6E7886)

/**
 * The dock at night: true black (an OLED screen lights no pixels there), a
 * large clock, what is playing, and a few faint stats. A tap wakes the dock.
 */
@Composable
fun NightScreen(stats: Stats?, welcome: Welcome?, media: Media?, onWake: () -> Unit, modifier: Modifier = Modifier) {
    val (time, date) = rememberClock()
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
        val wide = isWide(maxWidth, maxHeight)
        val clock = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    time,
                    fontFamily = Mono,
                    fontSize = if (wide) 132.sp else 104.sp,
                    lineHeight = if (wide) 132.sp else 104.sp,
                    letterSpacing = (-6).sp,
                    color = NightText,
                    maxLines = 1,
                )
                Text(date, Modifier.padding(start = 6.dp), fontSize = 18.sp, color = YaeColors.muted)
                media?.takeIf { it.title.isNotEmpty() }?.let {
                    Row(
                        Modifier.padding(start = 6.dp, top = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_music), null, Modifier.size(16.dp), tint = NightBar)
                        Text(
                            listOf(it.title, it.artist).filter(String::isNotEmpty).joinToString(" · "),
                            fontSize = 14.sp,
                            color = YaeColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val bars = @Composable { modifier: Modifier -> NightStats(stats, welcome, modifier) }
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                Box(Modifier.weight(1f)) { clock() }
                bars(Modifier.width(230.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 64.dp)) {
                Spacer(Modifier.weight(1f))
                clock()
                Spacer(Modifier.height(56.dp))
                bars(Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
            }
        }
        Text("Tap to wake", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), fontSize = 11.sp, color = NightHint)
    }
}

@Composable
private fun NightStats(stats: Stats?, welcome: Welcome?, modifier: Modifier) {
    if (stats == null) {
        Text("Not connected", modifier, fontSize = 12.sp, color = YaeColors.muted)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        stats.cpu?.let { cpu ->
            val temp = cpuTemperature(stats)?.let { " · ${celsius(it)}" }.orEmpty()
            NightBar("CPU", "${cpu.total.roundToInt()}%$temp", cpu.total / 100f)
        }
        welcome?.gpus?.firstOrNull()?.let { gpu ->
            val reading = stats.gpus?.find { it.id == gpu.id }
            if (reading == null || reading.sleeping) {
                NightBar("GPU", "Asleep", 0f)
            } else {
                NightBar("GPU", "${(reading.usage ?: 0f).roundToInt()}%", (reading.usage ?: 0f) / 100f)
            }
        }
        stats.mem?.takeIf { it.total > 0 }?.let { mem ->
            val fraction = mem.used.toFloat() / mem.total
            NightBar("Memory", "${(fraction * 100).roundToInt()}%", fraction)
        }
        stats.battery?.let { battery ->
            val charging = if (battery.state == BatteryState.CHARGING) " · charging" else ""
            NightBar("Battery", "${battery.percent.roundToInt()}%$charging", battery.percent / 100f, NightAmber)
        }
    }
}

@Composable
private fun NightBar(label: String, value: String, fraction: Float, color: Color = NightBar) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), fontSize = 12.sp, color = YaeColors.muted)
            Text(value, fontFamily = Mono, fontSize = 12.sp, color = NightText.copy(alpha = 0.85f))
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(NightTrack)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(color))
        }
    }
}
