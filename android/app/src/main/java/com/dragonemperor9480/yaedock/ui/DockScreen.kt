package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.SetMic
import com.dragonemperor9480.yaedock.protocol.SetOverlay
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.TakeScreenshot
import com.dragonemperor9480.yaedock.protocol.Welcome
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home: the things reached for on a dock. A thin strip of stats (Overview has
 * the rest), the time, what is playing, quick actions, shortcuts and sound. In
 * landscape nothing scrolls; in portrait everything stacks and scrolls.
 */
@Composable
fun DockScreen(
    pcName: String,
    status: Status,
    stats: Stats?,
    state: State?,
    wide: Boolean,
    allowVolumeBoost: Boolean,
    userName: String,
    record: RecordControl?,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    onAllPcs: () -> Unit,
    onOverview: () -> Unit,
) {
    val welcome = (status as? Status.Connected)?.welcome
    val actions = welcome?.systemActions.orEmpty()
    val (statusColor, statusText) = statusLine(status)
    if (welcome == null || stats == null || state == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(pcName, statusText, dot = statusColor) { PcMenu(pcName, actions, send, onSettings, onAllPcs) }
            StatusPanel(pcName, status, onRetry, onPairAgain)
        }
        return
    }
    val shortcuts = @Composable {
        if (state.shortcuts.isNotEmpty()) ShortcutButtons(state.shortcuts.take(SHORTCUTS_ON_HOME), send, SHORTCUTS_ON_HOME)
    }
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(128.dp)) {
                    Text(pcName, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(statusColor)
                        Muted(statusText, size = 12.sp)
                    }
                }
                StatStrip(stats, welcome, inline = true, onOverview, Modifier.weight(1f).height(48.dp))
                PcMenu(pcName, actions, send, onSettings, onAllPcs)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClockCard(userName, large = true, Modifier.weight(1f).fillMaxWidth())
                    MediaCard(state.media, send, stacked = true)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickActions(welcome, state, record, send, Modifier.fillMaxWidth().height(66.dp))
                    shortcuts()
                    QuickControls(state, allowVolumeBoost, send, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    } else {
        val uptime = " · up ${formatUptime(stats.uptime)}"
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScreenHeader(pcName, statusText + uptime, dot = statusColor) { PcMenu(pcName, actions, send, onSettings, onAllPcs) }
            StatStrip(stats, welcome, inline = false, onOverview, Modifier.fillMaxWidth().height(56.dp))
            ClockCard(userName, large = false, Modifier.fillMaxWidth())
            MediaCard(state.media, send, stacked = false)
            QuickActions(welcome, state, record, send, Modifier.fillMaxWidth().height(66.dp))
            shortcuts()
            QuickControls(state, allowVolumeBoost, send, Modifier.fillMaxWidth())
        }
    }
}

/** One row's worth: the rest are on the Controls tab. */
private const val SHORTCUTS_ON_HOME = 4

/** The frame rate while a game runs, then CPU, GPU and memory. A tap opens Overview. */
@Composable
private fun StatStrip(stats: Stats, welcome: Welcome, inline: Boolean, onOverview: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(YaeColors.card)
            .clickable(role = Role.Button, onClickLabel = "Open Overview", onClick = onOverview)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stats.game?.let { game ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoValue(String.format(Locale.ROOT, "%.0f", game.fps), size = 24.sp, color = YaeColors.accent)
                Muted("fps", size = 12.sp)
            }
            Box(Modifier.size(width = 1.dp, height = 24.dp).background(YaeColors.track))
        }
        stats.cpu?.let { cpu ->
            StripStat("CPU", "${cpu.total.roundToInt()}%", cpuTemperature(stats), inline, Modifier.weight(1f))
        }
        welcome.gpus.firstOrNull()?.let { gpu ->
            val reading = stats.gpus?.find { it.id == gpu.id }
            val asleep = reading == null || reading.sleeping
            val value = if (asleep) "off" else "${(reading.usage ?: 0f).roundToInt()}%"
            StripStat("GPU", value, reading?.temp?.takeIf { !asleep }, inline, Modifier.weight(1f))
        }
        stats.mem?.takeIf { it.total > 0 }?.let { mem ->
            StripStat("RAM", "${(mem.used * 100f / mem.total).roundToInt()}%", null, inline, Modifier.weight(1f))
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = YaeColors.muted)
    }
}

/** A label and its value, side by side when [inline] and otherwise stacked; with a temperature if known. */
@Composable
private fun StripStat(label: String, value: String, temperature: Float?, inline: Boolean, modifier: Modifier) {
    val name = @Composable {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = YaeColors.muted, maxLines = 1)
    }
    val reading = @Composable {
        Text(value, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        temperature?.let {
            Text(
                String.format(Locale.ROOT, "%.0f°", it),
                fontFamily = Mono,
                fontSize = 12.sp,
                color = temperatureColor(it),
                maxLines = 1,
            )
        }
    }
    if (inline) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            name()
            reading()
        }
    } else {
        Column(modifier) {
            name()
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) { reading() }
        }
    }
}

/** The time large, then the greeting and the date. */
@Composable
private fun ClockCard(userName: String, large: Boolean, modifier: Modifier) {
    val clock = rememberClock()
    DockCard(modifier, padding = 16.dp, spacing = 4.dp) {
        if (large) Fill()
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val size = if (large) 68.sp else 54.sp
            Text(clock.time, fontFamily = Mono, fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, letterSpacing = (-3).sp, maxLines = 1)
            clock.period?.let { Muted(it, Modifier.padding(bottom = 8.dp), size = 15.sp) }
        }
        Text(
            greeting(clock.hour, userName),
            Modifier.padding(top = 6.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = YaeColors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Muted(clock.date, size = 13.sp)
        if (large) Fill()
    }
}

/** Screenshot, record, microphone and the MangoHud overlay, as far as this PC has them. */
@Composable
private fun QuickActions(welcome: Welcome, state: State, record: RecordControl?, send: (ClientMessage) -> Unit, modifier: Modifier) {
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if (welcome.screenshot) {
            add { ActionTile(R.drawable.ic_camera, "Screenshot", it) { send(TakeScreenshot) } }
        }
        record?.let { control -> add { RecordTile(control, it) } }
        state.mic?.let { mic ->
            add {
                ActionTile(
                    if (mic.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                    if (mic.muted) "Muted" else "Mic",
                    it,
                    colors = if (mic.muted) YaeColors.redSoft to YaeColors.redText else YaeColors.card to YaeColors.text,
                ) { send(SetMic(muted = !mic.muted)) }
            }
        }
        state.overlay?.let { visible ->
            add {
                ActionTile(
                    R.drawable.ic_overlay,
                    if (visible) "Overlay on" else "Overlay off",
                    it,
                    colors = if (visible) YaeColors.accentSoft to YaeColors.accent else YaeColors.card to YaeColors.text,
                ) { send(SetOverlay(!visible)) }
            }
        }
    }
    if (tiles.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (tile in tiles) tile(Modifier.weight(1f).fillMaxHeight())
    }
}

/** A big square-ish button: an icon over a word. */
@Composable
private fun ActionTile(
    icon: Int,
    label: String,
    modifier: Modifier,
    colors: Pair<Color, Color> = YaeColors.card to YaeColors.text,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(CardShape).background(colors.first).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = colors.second)
        Text(label, fontSize = 12.sp, color = colors.second, maxLines = 1)
    }
}

/** Idle: starts a recording. Recording: a red timer that stops it. */
@Composable
private fun RecordTile(record: RecordControl, modifier: Modifier) {
    val recording = record.recording?.takeIf { it.active }
    when {
        record.pending -> Box(modifier.clip(CardShape).background(YaeColors.card), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = YaeColors.red, strokeWidth = 2.dp)
        }
        recording != null -> ActionTile(
            R.drawable.ic_record,
            formatTimer(recording.durationMs),
            modifier,
            colors = YaeColors.redSoft to YaeColors.redText,
        ) { record.onRecord(false) }
        else -> ActionTile(R.drawable.ic_record, "Record", modifier) { record.onRecord(true) }
    }
}

/** The quick controls card: volume, brightness, then the audio output and power profile. */
@Composable
private fun QuickControls(state: State, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit, modifier: Modifier) {
    DockCard(modifier, padding = 10.dp, spacing = 2.dp) {
        state.volume?.let { VolumeSlider(it, allowVolumeBoost, send) }
        state.displays.firstOrNull()?.let { BrightnessSlider(it, send) }
        Spacer(Modifier.weight(1f))
        val chips = state.outputs.isNotEmpty() || state.power != null
        if (chips) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.outputs.isNotEmpty()) OutputChip(state.outputs, send, Modifier.weight(1f))
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
