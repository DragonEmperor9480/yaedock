package com.dragonemperor9480.yaedock.ui

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Game
import com.dragonemperor9480.yaedock.protocol.SetBrightness
import com.dragonemperor9480.yaedock.protocol.Recording
import com.dragonemperor9480.yaedock.protocol.SetMic
import com.dragonemperor9480.yaedock.protocol.StartRecording
import com.dragonemperor9480.yaedock.protocol.StopRecording
import com.dragonemperor9480.yaedock.protocol.SetVolume
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.TakeScreenshot
import com.dragonemperor9480.yaedock.protocol.Welcome
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The live view of one PC. On a wide screen (a phone docked in landscape) the
 * header and controls sit in a column on the left and the metric tiles fill the
 * rest without scrolling; on a narrow screen everything stacks and scrolls.
 */
@Composable
fun DashboardScreen(
    pcName: String,
    status: Status,
    stats: Stats?,
    state: State?,
    history: History,
    allowVolumeBoost: Boolean,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onMonitor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val welcome = (status as? Status.Connected)?.welcome
    var mixerOpen by remember { mutableStateOf(false) }
    val controls = @Composable {
        if (welcome != null && state != null) {
            Controls(state, allowVolumeBoost, send, onMixer = { mixerOpen = true })
            state.media?.let { MediaCard(it, send) }
        }
    }
    if (mixerOpen && state != null) {
        MixerSheet(state.apps, allowVolumeBoost, send, onDismiss = { mixerOpen = false })
    }

    // Only when the PC can take screenshots (it has Spectacle).
    val onScreenshot = if (welcome?.screenshot == true) ({ send(TakeScreenshot) }) else null
    // Starting can take a while when OBS has to open first; the button shows
    // progress until the PC reports the change (or gives up, with an error toast).
    val recording = state?.recording
    var recordPending by remember { mutableStateOf(false) }
    LaunchedEffect(recording?.active) { recordPending = false }
    LaunchedEffect(recordPending) {
        if (recordPending) {
            delay(RECORD_PENDING_MS)
            recordPending = false
        }
    }
    val record = if (welcome?.recorder == true) {
        RecordControl(recording, recordPending) { start ->
            recordPending = true
            send(if (start) StartRecording else StopRecording())
        }
    } else {
        null
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 560.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Header(pcName, status, onBack, onSettings, onMonitor, onScreenshot, record)
                    // Scrolls when the controls are taller than a landscape screen.
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { controls() }
                    Clock(Modifier.padding(start = 4.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (welcome != null && stats != null) {
                        Tiles(stats, welcome, history, columns = 3, fillHeight = true)
                    } else {
                        StatusPanel(pcName, status, onRetry, onPairAgain, Modifier.align(Alignment.Center))
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(pcName, status, onBack, onSettings, onMonitor, onScreenshot, record)
                controls()
                if (welcome != null && stats != null) {
                    Tiles(stats, welcome, history, columns = 2, fillHeight = false)
                } else {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        StatusPanel(pcName, status, onRetry, onPairAgain, Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    pcName: String,
    status: Status,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onMonitor: () -> Unit,
    onScreenshot: (() -> Unit)?,
    record: RecordControl?,
) {
    val recording = record?.recording?.takeIf { it.active }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                pcName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (color, text) = when {
                    recording != null -> MetricColors.hot to
                        "${if (recording.paused) "Paused" else "REC"} ${formatTimer(recording.durationMs)}"
                    else -> statusLine(status)
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                // One line even when the header's buttons leave little room.
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recording != null) MetricColors.hot else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        record?.let { RecordButton(it) }
        onScreenshot?.let {
            IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_camera), contentDescription = "Take a screenshot on the PC") }
        }
        IconButton(onClick = onMonitor) { Icon(painterResource(R.drawable.ic_monitor), contentDescription = "Monitor") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        IconButton(onClick = onBack) { Icon(Icons.Default.List, contentDescription = "All PCs") }
    }
}

/** The screen recorder's state and what the record button does. */
private class RecordControl(val recording: Recording?, val pending: Boolean, val onRecord: (start: Boolean) -> Unit)

@Composable
private fun RecordButton(record: RecordControl) {
    val active = record.recording?.active == true
    IconButton(onClick = { record.onRecord(!active) }, enabled = !record.pending) {
        when {
            record.pending -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            active -> Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop recording", tint = MetricColors.hot)
            else -> Icon(painterResource(R.drawable.ic_videocam), contentDescription = "Record the PC's screen")
        }
    }
}

@Composable
private fun statusLine(status: Status): Pair<Color, String> = when (status) {
    // The uptime has its own tile; the header's buttons leave no room for it.
    is Status.Connected -> MetricColors.cool to "Connected"
    Status.Connecting -> MetricColors.warm to "Connecting…"
    is Status.Waiting -> MetricColors.warm to "Reconnecting…"
    is Status.Rejected -> MetricColors.hot to "Not allowed"
    Status.Stopped -> MaterialTheme.colorScheme.outline to "Not connected"
}

/** What to show instead of the tiles while not connected. */
@Composable
private fun StatusPanel(
    pcName: String,
    status: Status,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.widthIn(max = 420.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (status) {
            is Status.Waiting -> {
                Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(48.dp), tint = MetricColors.warm)
                Text("Can't reach $pcName", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    "Check that yaedockd is running on the PC and that both are on the same Wi-Fi. " +
                        "Trying again in ${status.retryInMs / 1000} s.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRetry) { Text("Try now") }
            }
            is Status.Rejected -> {
                Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(48.dp), tint = MetricColors.hot)
                Text(
                    "$pcName doesn't recognise this phone",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "It may have been unpaired on the PC. Run `yaedock pair` there and scan the new code.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onPairAgain) { Text("Pair again") }
            }
            else -> {
                CircularProgressIndicator()
                Text("Connecting to $pcName…", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Controls(state: State, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit, onMixer: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (display in state.displays) {
                SyncedSlider(
                    icon = R.drawable.ic_brightness,
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
                    icon = R.drawable.ic_volume,
                    label = if (volume.percent > 100) "Volume (boosted)" else "Volume",
                    value = volume.percent,
                    max = if (boost) 150 else 100,
                    onChange = { send(SetVolume(percent = it)) },
                    trailing = {
                        IconButton(onClick = onMixer, modifier = Modifier.size(36.dp)) {
                            Icon(painterResource(R.drawable.ic_tune), contentDescription = "App volumes", Modifier.size(18.dp))
                        }
                        state.mic?.let { mic ->
                            // Muted is the state worth noticing, so it is the highlighted one.
                            FilledTonalIconToggleButton(
                                checked = mic.muted,
                                onCheckedChange = { send(SetMic(muted = it)) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painterResource(if (mic.muted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                                    contentDescription = if (mic.muted) "Unmute microphone" else "Mute microphone",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        FilledTonalIconToggleButton(
                            checked = volume.muted,
                            onCheckedChange = { send(SetVolume(muted = it)) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painterResource(if (volume.muted) R.drawable.ic_volume_off else R.drawable.ic_volume),
                                contentDescription = if (volume.muted) "Unmute" else "Mute",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
            state.power?.let { PowerProfilePicker(it, send) }
            if (state.displays.isEmpty() && state.volume == null && state.power == null) {
                Text("This PC has no brightness or volume control.", style = MaterialTheme.typography.bodyMedium)
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
fun SyncedSlider(
    @DrawableRes icon: Int,
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
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("$shown%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
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
}

private const val SEND_INTERVAL_MS = 80L
/** Opening OBS and starting to record can take this long. */
private const val RECORD_PENDING_MS = 35_000L
private const val HOLD_MS = 1500L

/**
 * The metric tiles in a grid. With [fillHeight] the rows share the available
 * height (landscape dock); otherwise each row has a fixed height and the page scrolls.
 */
@Composable
private fun Tiles(stats: Stats, welcome: Welcome, history: History, columns: Int, fillHeight: Boolean) {
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        stats.cpu?.let { cpu -> add { CpuTile(cpu.total, cpu.cores, history.cpu, it) } }
        stats.mem?.let { mem -> add { MemoryTile(mem.used, mem.total, history.mem, it) } }
        stats.temps?.takeIf { it.isNotEmpty() }?.let { temps -> add { TemperatureTile(temps, history.temps, it) } }
        stats.net?.let { net -> add { NetworkTile(net.rx, net.tx, history.netRx, history.netTx, it) } }
        stats.disk?.let { disk -> add { DiskTile(disk.read, disk.write, history.diskRead, history.diskWrite, it) } }
        // While a game runs it takes the uptime tile's place (uptime is in the header too).
        val game = stats.game
        if (game != null) add { GameTile(game, history.fps, it) } else add { SystemTile(welcome, stats.uptime, it) }
    }
    Column(
        if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in tiles.chunked(columns)) {
            Row(
                (if (fillHeight) Modifier.weight(1f) else Modifier.height(176.dp)).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
                // Keep tiles the same width when the last row is short.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(
    @DrawableRes icon: Int,
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
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

@Composable
private fun BigValue(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Detail(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CpuTile(total: Float, cores: List<Float>, history: List<Float>, modifier: Modifier) {
    Tile(R.drawable.ic_cpu, "CPU", MetricColors.cpu, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(total, MetricColors.cpu, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            CoreBars(cores, MetricColors.cpu, Modifier.weight(1f).height(36.dp))
        }
        Sparkline(history, MetricColors.cpu, Modifier.fillMaxWidth().weight(1f), max = 100f)
    }
}

@Composable
private fun MemoryTile(used: Long, total: Long, history: List<Float>, modifier: Modifier) {
    val percent = if (total > 0) 100f * used / total else 0f
    Tile(R.drawable.ic_memory, "Memory", MetricColors.memory, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(percent, MetricColors.memory, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(formatBytes(used), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Detail("of ${formatBytes(total)}")
            }
        }
        Sparkline(history, MetricColors.memory, Modifier.fillMaxWidth().weight(1f), max = 100f)
    }
}

@Composable
private fun TemperatureTile(temps: Map<String, Float>, history: Map<String, List<Float>>, modifier: Modifier) {
    // The CPU package sensor if there is one, otherwise the hottest sensor.
    val main = temps.keys.firstOrNull { it == "cpu_package" } ?: temps.maxBy { it.value }.key
    val mainValue = temps.getValue(main)
    // Per-core sensors repeat what the package sensor says, so leave them out.
    val others = temps.filterKeys { it != main && !it.contains("_core_") }
        .entries.sortedByDescending { it.value }.take(2)
    Tile(R.drawable.ic_thermometer, sensorLabel(main), temperatureColor(mainValue), modifier) {
        BigValue(celsius(mainValue), color = temperatureColor(mainValue))
        for ((name, value) in others) {
            Row {
                Detail(sensorLabel(name), Modifier.weight(1f))
                Text(celsius(value), style = MaterialTheme.typography.bodySmall, color = temperatureColor(value))
            }
        }
        Sparkline(history[main].orEmpty(), temperatureColor(mainValue), Modifier.fillMaxWidth().weight(1f), max = 100f)
    }
}

@Composable
private fun NetworkTile(rx: Long, tx: Long, rxHistory: List<Float>, txHistory: List<Float>, modifier: Modifier) {
    Tile(R.drawable.ic_network, "Network", MetricColors.network, modifier) {
        RateRow("↓ Down", rx)
        RateRow("↑ Up", tx)
        TwoLines(rxHistory, txHistory, MetricColors.network, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun DiskTile(read: Long, write: Long, readHistory: List<Float>, writeHistory: List<Float>, modifier: Modifier) {
    Tile(R.drawable.ic_disk, "Disk", MetricColors.disk, modifier) {
        RateRow("Read", read)
        RateRow("Write", write)
        TwoLines(readHistory, writeHistory, MetricColors.disk, Modifier.fillMaxWidth().weight(1f))
    }
}

/** Two sparklines on one shared scale, the second fainter, so they can be compared. */
@Composable
private fun TwoLines(first: List<Float>, second: List<Float>, color: Color, modifier: Modifier) {
    val top = (first + second).maxOrNull()
    Box(modifier) {
        Sparkline(first, color, Modifier.fillMaxSize(), max = top)
        Sparkline(second, color.copy(alpha = 0.5f), Modifier.fillMaxSize(), max = top)
    }
}

@Composable
private fun RateRow(label: String, bytesPerSecond: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Detail(label, Modifier.weight(1f))
        Text(formatRate(bytesPerSecond), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SystemTile(welcome: Welcome, uptime: Long, modifier: Modifier) {
    Tile(R.drawable.ic_timer, "Uptime", MetricColors.system, modifier) {
        BigValue(formatUptime(uptime))
        Detail(welcome.os)
        Detail(welcome.cpuModel)
        Detail("${welcome.cores} threads")
    }
}

@Composable
private fun GameTile(game: Game, history: List<Float>, modifier: Modifier) {
    Tile(R.drawable.ic_game, gameTitle(game.name), MetricColors.game, modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue(String.format(Locale.ROOT, "%.0f", game.fps), color = MetricColors.game)
            Spacer(Modifier.width(6.dp))
            Detail("FPS", Modifier.padding(bottom = 4.dp))
        }
        Detail(String.format(Locale.ROOT, "%.1f ms per frame", game.frametime))
        Sparkline(history, MetricColors.game, Modifier.fillMaxWidth().weight(1f))
    }
}

/** `eldenring.exe` → `eldenring`: the program name, as readable as it gets. */
fun gameTitle(name: String): String = name.removeSuffix(".exe").removeSuffix(".x86_64").ifEmpty { "Game" }

private fun celsius(value: Float) = String.format(Locale.ROOT, "%.0f °C", value)
