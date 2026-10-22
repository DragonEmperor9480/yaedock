package com.dragonemperor9480.yaedock.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.Game
import com.dragonemperor9480.yaedock.protocol.GpuInfo
import com.dragonemperor9480.yaedock.protocol.GpuStats
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.Welcome
import java.util.Locale

/**
 * A closer look at the PC's hardware: the CPU by name with every core, memory,
 * and each graphics card. Two columns side by side on a wide screen.
 */
@Composable
fun MonitorScreen(
    pcName: String,
    status: Status,
    stats: Stats?,
    history: History,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val welcome = (status as? Status.Connected)?.welcome
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Monitor", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(pcName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (welcome == null || stats == null) {
            Text(
                "Waiting for $pcName…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val cards = buildList<@Composable (Modifier) -> Unit> {
            stats.game?.let { game -> add { GameCard(game, history.fps, it) } }
            add { CpuCard(welcome, stats, history, it) }
            stats.mem?.let { mem -> add { MemoryCard(mem.used, mem.total, history.mem, it) } }
            stats.battery?.let { battery -> add { BatteryCard(battery, it) } }
            for (gpu in welcome.gpus) {
                val reading = stats.gpus?.find { it.id == gpu.id }
                add { GpuCard(gpu, reading, history.gpus[gpu.id].orEmpty(), it) }
            }
        }
        BoxWithConstraints {
            val columns = if (maxWidth >= 600.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in cards.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (card in row) card(Modifier.weight(1f))
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (welcome.gpus.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "No graphics card readings from this PC. Update YaeDock on the PC to see them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonitorCard(
    @DrawableRes icon: Int,
    label: String,
    title: String,
    color: Color,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            content()
        }
    }
}

/** A labelled value, such as "Temperature  54 °C". */
@Composable
private fun Reading(label: String, value: String, color: Color = Color.Unspecified) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** A thin horizontal bar filled to [fraction]. */
@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
    }
}

@Composable
private fun GameCard(game: Game, history: List<Float>, modifier: Modifier) {
    MonitorCard(R.drawable.ic_game, "Game", gameTitle(game.name), MetricColors.game, modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.ROOT, "%.0f", game.fps),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MetricColors.game,
            )
            Spacer(Modifier.width(8.dp))
            Text("FPS", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
        }
        Reading("Frame time", String.format(Locale.ROOT, "%.1f ms", game.frametime))
        if (history.isNotEmpty()) {
            Reading("Average (last minute)", String.format(Locale.ROOT, "%.0f FPS", history.average()))
            Reading("Lowest (last minute)", String.format(Locale.ROOT, "%.0f FPS", history.min()))
        }
        Sparkline(history, MetricColors.game, Modifier.fillMaxWidth().height(56.dp))
    }
}

@Composable
private fun CpuCard(welcome: Welcome, stats: Stats, history: History, modifier: Modifier) {
    val cpu = stats.cpu
    MonitorCard(R.drawable.ic_cpu, "CPU", welcome.cpuModel, MetricColors.cpu, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(cpu?.total ?: 0f, MetricColors.cpu, size = 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Reading("Threads", "${welcome.cores}")
                cpuTemperature(stats)?.let { Reading("Temperature", celsius(it), temperatureColor(it)) }
            }
        }
        cpu?.cores?.let { CoreBars(it, MetricColors.cpu, Modifier.fillMaxWidth().height(48.dp)) }
        Sparkline(history.cpu, MetricColors.cpu, Modifier.fillMaxWidth().height(56.dp), max = 100f)
    }
}

/** The CPU package sensor, or failing that a sensor whose name mentions the CPU. */
private fun cpuTemperature(stats: Stats): Float? {
    val temps = stats.temps ?: return null
    return temps["cpu_package"] ?: temps.entries.firstOrNull { it.key.startsWith("cpu") || it.key.startsWith("k10temp") }?.value
}

@Composable
private fun MemoryCard(used: Long, total: Long, history: List<Float>, modifier: Modifier) {
    val percent = if (total > 0) 100f * used / total else 0f
    MonitorCard(R.drawable.ic_memory, "Memory", "${formatBytes(total)} RAM", MetricColors.memory, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(percent, MetricColors.memory, size = 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Reading("In use", formatBytes(used))
                Reading("Free", formatBytes((total - used).coerceAtLeast(0)))
            }
        }
        Sparkline(history, MetricColors.memory, Modifier.fillMaxWidth().height(56.dp), max = 100f)
    }
}

@Composable
private fun BatteryCard(battery: Battery, modifier: Modifier) {
    val color = batteryColor(battery)
    val icon = if (battery.state == BatteryState.CHARGING) R.drawable.ic_battery_charging else R.drawable.ic_battery
    MonitorCard(icon, "Battery", batteryStatus(battery), color, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(battery.percent, color, size = 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                battery.powerW?.let {
                    val label = if (battery.state == BatteryState.CHARGING) "Charging at" else "Using"
                    Reading(label, String.format(Locale.ROOT, "%.1f W", it))
                }
                battery.health?.let {
                    // Below 80% of its original capacity a battery is usually considered worn.
                    Reading("Health", String.format(Locale.ROOT, "%.0f%%", it), if (it < 80f) MetricColors.warm else MetricColors.cool)
                }
            }
        }
    }
}

@Composable
private fun GpuCard(gpu: GpuInfo, reading: GpuStats?, history: List<Float>, modifier: Modifier) {
    val color = MetricColors.disk
    MonitorCard(R.drawable.ic_gpu, "Graphics", gpu.name, color, modifier) {
        when {
            reading == null -> Text(
                "No reading yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            reading.sleeping -> Text(
                "Sleeping: powered down to save energy",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(reading.usage ?: 0f, color, size = 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        reading.temp?.let { Reading("Temperature", celsius(it), temperatureColor(it)) }
                        reading.clockMhz?.let { Reading("Clock", "$it MHz") }
                        if (reading.vramTotal == null) Reading("Memory", "Shared with RAM")
                    }
                }
                val vramUsed = reading.vramUsed
                val vramTotal = reading.vramTotal
                if (vramUsed != null && vramTotal != null && vramTotal > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Reading("Video memory", "${formatBytes(vramUsed)} of ${formatBytes(vramTotal)}")
                        Bar(vramUsed.toFloat() / vramTotal, color)
                    }
                }
                if (reading.usage != null) {
                    Sparkline(history, color, Modifier.fillMaxWidth().height(56.dp), max = 100f)
                }
            }
        }
    }
}

private fun celsius(value: Float) = String.format(Locale.ROOT, "%.0f °C", value)
