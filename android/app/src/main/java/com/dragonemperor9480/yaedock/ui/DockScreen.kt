package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Game
import com.dragonemperor9480.yaedock.protocol.GpuInfo
import com.dragonemperor9480.yaedock.protocol.GpuStats
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.SystemAction
import com.dragonemperor9480.yaedock.protocol.TakeScreenshot
import com.dragonemperor9480.yaedock.protocol.Welcome
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The PC at a glance: six metric tiles, what is playing, and the controls
 * reached for most. In landscape the tiles fill the left and nothing scrolls;
 * in portrait everything stacks and scrolls.
 */
@Composable
fun DockScreen(
    pcName: String,
    status: Status,
    stats: Stats?,
    state: State?,
    history: History,
    wide: Boolean,
    allowVolumeBoost: Boolean,
    record: RecordControl?,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    onAllPcs: () -> Unit,
) {
    val welcome = (status as? Status.Connected)?.welcome
    val header = @Composable {
        DockHeader(pcName, status, stats, welcome, record, welcome?.systemActions.orEmpty(), send, onSettings, onAllPcs)
    }
    if (welcome == null || stats == null || state == null) {
        Column(Modifier.fillMaxSize()) {
            header()
            StatusPanel(pcName, status, onRetry, onPairAgain)
        }
        return
    }
    val tiles = tilesFor(stats, welcome, history, graphs = wide)
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TileGrid(tiles, columns = 3, Modifier.weight(0.63f).fillMaxHeight(), rowHeight = null)
                Column(Modifier.weight(0.37f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.media?.let { MediaCard(it, send, stacked = true) }
                    QuickControls(state, allowVolumeBoost, send, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            TileGrid(tiles, columns = 2, Modifier.fillMaxWidth(), rowHeight = 120)
            state.media?.let { MediaCard(it, send, stacked = false) }
            QuickControls(state, allowVolumeBoost, send, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DockHeader(
    pcName: String,
    status: Status,
    stats: Stats?,
    welcome: Welcome?,
    record: RecordControl?,
    systemActions: List<SystemAction>,
    send: (ClientMessage) -> Unit,
    onSettings: () -> Unit,
    onAllPcs: () -> Unit,
) {
    val (color, text) = statusLine(status)
    val uptime = stats?.uptime?.takeIf { status is Status.Connected }?.let { " · up ${formatUptime(it)}" }.orEmpty()
    ScreenHeader(pcName, text + uptime, dot = color) {
        if (welcome?.screenshot == true) {
            SquareButton(painterResource(R.drawable.ic_camera), "Take a screenshot on the PC", { send(TakeScreenshot) })
        }
        record?.let { RecordButton(it) }
        PcMenu(pcName, systemActions, send, onSettings, onAllPcs)
    }
}

/** Six tiles at most: what fits the landscape dock without scrolling. */
private fun tilesFor(stats: Stats, welcome: Welcome, history: History, graphs: Boolean): List<@Composable (Modifier) -> Unit> =
    buildList<@Composable (Modifier) -> Unit> {
        stats.cpu?.let { cpu -> add { CpuTile(cpu.total, cpu.cores, cpuTemperature(stats), welcome.cores, history.cpu, graphs, it) } }
        welcome.gpus.firstOrNull()?.let { gpu ->
            val reading = stats.gpus?.find { it.id == gpu.id }
            add { GpuTile(gpu, reading, history.gpus[gpu.id].orEmpty(), graphs, it) }
        }
        stats.mem?.let { mem -> add { MemoryTile(mem.used, mem.total, it) } }
        stats.game?.let { game -> add { GameTile(game, history.fps, graphs, it) } }
        stats.net?.let { net -> add { NetworkTile(net.rx, net.tx, history.netRx, history.netTx, graphs, it) } }
        stats.battery?.let { battery -> add { BatteryTile(battery, it) } }
        stats.disk?.let { disk -> add { DiskTile(disk.read, disk.write, history.diskRead, history.diskWrite, graphs, it) } }
        add { UptimeTile(stats.uptime, welcome.os, it) }
    }.take(6)

/**
 * Tiles in rows of [columns]. Without a [rowHeight] the rows share the height
 * (the landscape dock); with one, each row is that tall.
 */
@Composable
private fun TileGrid(tiles: List<@Composable (Modifier) -> Unit>, columns: Int, modifier: Modifier, rowHeight: Int?) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(columns)) {
            Row(
                (if (rowHeight == null) Modifier.weight(1f) else Modifier.height(rowHeight.dp)).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
                // Keep tiles the same width when the last row is short.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    modifier: Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DockCard(modifier) {
        CardHeader(label, trailing)
        content()
    }
}

@Composable
private fun CpuTile(
    total: Float,
    cores: List<Float>,
    temperature: Float?,
    threads: Int,
    history: List<Float>,
    graphs: Boolean,
    modifier: Modifier,
) {
    MetricTile("CPU", modifier, temperature?.let { { HeaderValue(celsius(it), temperatureColor(it)) } }) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueRing(total, if (graphs) 64.dp else 58.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CoreBars(cores, YaeColors.accent, Modifier.fillMaxWidth().height(22.dp))
                Muted("$threads threads")
            }
        }
        if (graphs) Sparkline(history, YaeColors.accent, Modifier.fillMaxWidth().height(22.dp), max = 100f)
    }
}

@Composable
private fun GpuTile(gpu: GpuInfo, reading: GpuStats?, history: List<Float>, graphs: Boolean, modifier: Modifier) {
    val temp = reading?.temp?.takeIf { !reading.sleeping }
    MetricTile("GPU", modifier, temp?.let { { HeaderValue(celsius(it), temperatureColor(it)) } }) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueRing(if (reading == null || reading.sleeping) 0f else reading.usage ?: 0f, if (graphs) 64.dp else 58.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val used = reading?.vramUsed
                val total = reading?.vramTotal
                when {
                    reading == null -> Muted("No reading yet", maxLines = 2)
                    reading.sleeping -> Muted("Asleep", size = 13.sp)
                    used != null && total != null && total > 0 -> {
                        MonoValue(gib(used), "/${gib(total)} GiB", size = 14.sp)
                        Muted("VRAM")
                    }
                    reading.clockMhz != null -> {
                        MonoValue("${reading.clockMhz}", " MHz", size = 14.sp)
                        Muted("Clock")
                    }
                }
                Muted(gpu.name.substringAfterLast('[').removeSuffix("]"))
            }
        }
        if (graphs) Sparkline(history, YaeColors.accent, Modifier.fillMaxWidth().height(22.dp), max = 100f)
    }
}

@Composable
private fun MemoryTile(used: Long, total: Long, modifier: Modifier) {
    val fraction = if (total > 0) used.toFloat() / total else 0f
    MetricTile("Memory", modifier, { HeaderValue("${(fraction * 100).roundToInt()}%") }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            MonoValue(gib(used), " / ${gib(total)} GiB", size = 24.sp)
            Muted("${gib((total - used).coerceAtLeast(0))} GiB free")
        }
        MeterBar(fraction)
    }
}

@Composable
private fun NetworkTile(rx: Long, tx: Long, rxHistory: List<Float>, txHistory: List<Float>, graphs: Boolean, modifier: Modifier) {
    MetricTile("Network", modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            RateRow("↓", "Download", rx, YaeColors.accent)
            RateRow("↑", "Upload", tx, YaeColors.amber)
        }
        if (graphs) TwoLines(rxHistory, txHistory, YaeColors.accent, YaeColors.amber, Modifier.fillMaxWidth().height(22.dp))
    }
}

@Composable
private fun DiskTile(read: Long, write: Long, readHistory: List<Float>, writeHistory: List<Float>, graphs: Boolean, modifier: Modifier) {
    MetricTile("Disk", modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            RateRow("R", "Read", read, YaeColors.muted)
            RateRow("W", "Write", write, YaeColors.muted)
        }
        if (graphs) TwoLines(readHistory, writeHistory, YaeColors.accent, YaeColors.amber, Modifier.fillMaxWidth().height(22.dp))
    }
}

/** `↓ 2.4 MiB/s`: a symbol, the number large, the unit small. */
@Composable
private fun RateRow(symbol: String, label: String, bytesPerSecond: Long, color: Color) {
    val (number, unit) = splitBytes(bytesPerSecond)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$label ${formatRate(bytesPerSecond)}" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(symbol, Modifier.width(12.dp), fontFamily = Mono, fontSize = 13.sp, color = color)
        MonoValue(number, " $unit/s", size = 19.sp)
    }
}

/** Two sparklines on one shared scale, so they can be compared. */
@Composable
fun TwoLines(first: List<Float>, second: List<Float>, firstColor: Color, secondColor: Color, modifier: Modifier) {
    val top = (first + second).maxOrNull()
    Box(modifier) {
        Sparkline(first, firstColor, Modifier.fillMaxSize(), max = top)
        Sparkline(second, secondColor, Modifier.fillMaxSize(), max = top)
    }
}

@Composable
private fun BatteryTile(battery: Battery, modifier: Modifier) {
    val color = batteryColor(battery)
    val trailing: @Composable RowScope.() -> Unit = {
        battery.powerW?.takeIf { it >= 0.5f }?.let { watts ->
            val charging = battery.state == BatteryState.CHARGING
            if (charging) Icon(painterResource(R.drawable.ic_bolt), null, Modifier.size(12.dp), tint = YaeColors.amber)
            HeaderValue(String.format(Locale.ROOT, "%.0f W", watts), if (charging) YaeColors.amber else YaeColors.muted)
        }
    }
    MetricTile("Battery", modifier, trailing) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            MonoValue("${battery.percent.roundToInt()}", "%", size = 24.sp)
            Muted(batteryStatus(battery))
        }
        SegmentBar(battery.percent, color = color)
    }
}

@Composable
private fun GameTile(game: Game, history: List<Float>, graphs: Boolean, modifier: Modifier) {
    MetricTile(gameTitle(game.name), modifier, { HeaderValue("FPS", YaeColors.accent) }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            MonoValue(String.format(Locale.ROOT, "%.0f", game.fps), size = 30.sp, color = YaeColors.accent)
            Muted(String.format(Locale.ROOT, "%.1f ms per frame", game.frametime))
        }
        if (graphs) Sparkline(history, YaeColors.accent, Modifier.fillMaxWidth().height(22.dp))
    }
}

@Composable
private fun UptimeTile(uptime: Long, os: String, modifier: Modifier) {
    MetricTile("Uptime", modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            MonoValue(formatUptime(uptime), size = 24.sp)
            Muted(os, maxLines = 2)
        }
    }
}

/** The quick controls card: brightness, volume, then output, microphone and power profile. */
@Composable
private fun QuickControls(state: State, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit, modifier: Modifier) {
    DockCard(modifier, padding = 10.dp, spacing = 2.dp) {
        state.displays.firstOrNull()?.let { BrightnessSlider(it, send) }
        state.volume?.let { VolumeSlider(it, allowVolumeBoost, send) }
        Spacer(Modifier.weight(1f))
        val chips = state.outputs.isNotEmpty() || state.mic != null || state.power != null
        if (chips) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.outputs.isNotEmpty()) OutputChip(state.outputs, send, Modifier.weight(1f))
                state.mic?.let { MicButton(it, send) }
                state.power?.let { PowerProfileChip(it, send, Modifier.weight(1f)) }
            }
        }
        if (state.displays.isEmpty() && state.volume == null && !chips) {
            Muted("This PC has no brightness or volume control.", size = 13.sp, maxLines = 2)
        }
    }
}

/** `9.4`: gibibytes with one decimal. */
fun gib(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_073_741_824.0)

/** `2.4 MiB` as `2.4` and `MiB`. */
fun splitBytes(bytes: Long): Pair<String, String> {
    val text = formatBytes(bytes)
    return text.substringBefore(' ') to text.substringAfter(' ')
}

/** The CPU package sensor, or failing that a sensor whose name mentions the CPU. */
fun cpuTemperature(stats: Stats): Float? {
    val temps = stats.temps ?: return null
    return temps["cpu_package"] ?: temps.entries.firstOrNull { it.key.startsWith("cpu") || it.key.startsWith("k10temp") }?.value
}

/** `eldenring.exe` → `eldenring`: the program name, as readable as it gets. */
fun gameTitle(name: String): String = name.removeSuffix(".exe").removeSuffix(".x86_64").ifEmpty { "Game" }

fun celsius(value: Float) = String.format(Locale.ROOT, "%.0f°C", value)
