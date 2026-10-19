package com.dragonemperor9480.yaedock.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.SetBrightness
import com.dragonemperor9480.yaedock.protocol.SetVolume
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    status: Status,
    stats: Stats?,
    state: State?,
    history: History,
    allowVolumeBoost: Boolean,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBar(status, stats, onRetry, onBack, onSettings)
        if (state != null && status is Status.Connected) Controls(state, allowVolumeBoost, send)
        if (stats != null) Tiles(stats, history)
    }
}

@Composable
private fun StatusBar(status: Status, stats: Stats?, onRetry: () -> Unit, onBack: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val (title, detail) = when (status) {
                is Status.Connected -> status.welcome.hostname to listOfNotNull(
                    status.welcome.os,
                    stats?.let { "up ${formatUptime(it.uptime)}" },
                ).joinToString(" · ")
                Status.Connecting -> "Connecting…" to ""
                is Status.Waiting -> "Disconnected" to "${status.reason}. Retrying in ${status.retryInMs / 1000} s."
                is Status.Rejected -> "Not allowed" to "${status.error.message}. Pair this phone again."
                Status.Stopped -> "Not connected" to ""
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (status is Status.Waiting) TextButton(onClick = onRetry) { Text("Retry now") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        TextButton(onClick = onBack) { Text("PCs") }
    }
}

@Composable
private fun Controls(state: State, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (display in state.displays) {
                SyncedSlider(
                    label = if (state.displays.size == 1) "Brightness" else display.name,
                    value = display.percent,
                    max = 100,
                    onChange = { send(SetBrightness(display.id, it)) },
                )
            }
            state.volume?.let { volume ->
                // Past 100% only when allowed, or when the PC is already boosted
                // (set from the desktop), so the real value is never hidden.
                val boost = allowVolumeBoost || volume.percent > 100
                SyncedSlider(
                    label = if (volume.percent > 100) "Volume (boosted)" else "Volume",
                    value = volume.percent,
                    max = if (boost) 150 else 100,
                    onChange = { send(SetVolume(percent = it)) },
                    trailing = {
                        FilterChip(
                            selected = volume.muted,
                            onClick = { send(SetVolume(muted = !volume.muted)) },
                            label = { Text(if (volume.muted) "Muted" else "Mute") },
                        )
                    },
                )
            }
        }
    }
}

/**
 * A slider that follows the PC's value, except while the user is dragging it.
 *
 * While dragging, changes are sent at most every [SEND_INTERVAL_MS] so the PC
 * follows along without being flooded, and the final value is always sent.
 * After release the slider keeps showing that value until the PC reports it
 * (or [HOLD_MS] passes), so older updates still in flight cannot make it jump back.
 */
@Composable
private fun SyncedSlider(
    label: String,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var held by remember { mutableStateOf<Int?>(null) }
    var lastSent by remember { mutableStateOf<Int?>(null) }
    var lastSentAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(value) { if (value == held) held = null }
    LaunchedEffect(held) {
        if (held != null) {
            delay(HOLD_MS)
            held = null
        }
    }

    val shown = dragging ?: held ?: value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label  $shown%", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        trailing()
    }
    Slider(
        value = shown.toFloat().coerceAtMost(max.toFloat()),
        onValueChange = {
            val percent = it.roundToInt()
            dragging = percent
            val now = SystemClock.uptimeMillis()
            if (percent != lastSent && now - lastSentAt >= SEND_INTERVAL_MS) {
                onChange(percent)
                lastSent = percent
                lastSentAt = now
            }
        },
        onValueChangeFinished = {
            dragging?.let { percent ->
                if (percent != lastSent) onChange(percent)
                held = percent
            }
            dragging = null
            lastSent = null
        },
        valueRange = 0f..max.toFloat(),
    )
}

private const val SEND_INTERVAL_MS = 80L
private const val HOLD_MS = 1500L

private val cpuColor = Color(0xFF4FC3F7)
private val memColor = Color(0xFFAED581)
private val netColor = Color(0xFFFFB74D)
private val diskColor = Color(0xFFBA68C8)
private val tempColor = Color(0xFFE57373)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tiles(stats: Stats, history: History) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 4,
    ) {
        val tile = Modifier.weight(1f).widthIn(min = 160.dp)
        stats.cpu?.let { cpu ->
            Tile("CPU", percent(cpu.total), history.cpu, cpuColor, tile, max = 100f) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (core in cpu.cores) {
                        LinearProgressIndicator(
                            progress = { core / 100f },
                            modifier = Modifier.weight(1f).height(4.dp),
                            color = cpuColor,
                        )
                    }
                }
            }
        }
        stats.mem?.let { mem ->
            Tile("Memory", "${formatBytes(mem.used)} / ${formatBytes(mem.total)}", history.mem, memColor, tile, max = 100f)
        }
        stats.net?.let { net ->
            Tile("Network", "↓ ${formatRate(net.rx)}\n↑ ${formatRate(net.tx)}", history.netRx, netColor, tile)
        }
        stats.disk?.let { disk ->
            Tile("Disk", "R ${formatRate(disk.read)}\nW ${formatRate(disk.write)}", history.diskWrite, diskColor, tile)
        }
        stats.temps?.entries?.sortedByDescending { it.key == "cpu_package" }?.forEach { (name, celsius) ->
            Tile(sensorLabel(name), String.format(Locale.ROOT, "%.0f °C", celsius), history.temps[name].orEmpty(), tempColor, tile, max = 100f)
        }
    }
}

@Composable
private fun Tile(
    title: String,
    value: String,
    history: List<Float>,
    color: Color,
    modifier: Modifier,
    max: Float? = null,
    extra: @Composable () -> Unit = {},
) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
            extra()
            Sparkline(history, color, Modifier.fillMaxWidth().height(40.dp), max = max)
        }
    }
}

private fun percent(value: Float) = String.format(Locale.ROOT, "%.0f%%", value)
