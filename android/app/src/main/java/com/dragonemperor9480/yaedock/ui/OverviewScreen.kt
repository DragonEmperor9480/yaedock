package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.TakeScreenshot
import com.dragonemperor9480.yaedock.protocol.Welcome
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything at once, for a game or a heavy job: the frame rate large (or the
 * CPU when no game runs) and a ring for each part of the PC.
 */
@Composable
fun OverviewScreen(
    pcName: String,
    status: Status,
    stats: Stats?,
    state: State?,
    history: History,
    wide: Boolean,
    record: RecordControl?,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    val welcome = (status as? Status.Connected)?.welcome
    val subtitle = listOfNotNull(pcName, state?.power?.let { profileLabel(it.active) }).joinToString(" · ")
    val header = @Composable {
        ScreenHeader("Overview", subtitle) {
            record?.let { RecordButton(it) }
            if (welcome?.screenshot == true) {
                SquareButton(painterResource(R.drawable.ic_camera), "Take a screenshot on the PC", { send(TakeScreenshot) })
            }
        }
    }
    if (welcome == null || stats == null) {
        Column(Modifier.fillMaxSize()) {
            header()
            StatusPanel(pcName, status, onRetry, onPairAgain)
        }
        return
    }
    val rings = ringsFor(stats, welcome)
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Hero(stats, welcome, history, Modifier.weight(0.4f).fillMaxHeight(), bigSize = 92, fill = true)
                RingGrid(rings, columns = 3, Modifier.weight(0.6f).fillMaxHeight(), rowHeight = null)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            Hero(stats, welcome, history, Modifier.fillMaxWidth(), bigSize = 104, fill = false)
            RingGrid(rings, columns = 2, Modifier.fillMaxWidth(), rowHeight = 156.dp)
        }
    }
}

/** The large panel: the game's frame rate, or the CPU's load when no game runs. */
@Composable
private fun Hero(stats: Stats, welcome: Welcome, history: History, modifier: Modifier, bigSize: Int, fill: Boolean) {
    val game = stats.game
    DockCard(modifier, padding = 16.dp, spacing = 6.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (game != null) "FRAME RATE" else "CPU", Modifier.weight(1f), style = CardLabel)
            Text(
                if (game != null) gameTitle(game.name) else welcome.cpuModel,
                Modifier.weight(1f, fill = false).clip(CircleShape).background(YaeColors.raised).padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val values = if (game != null) history.fps else history.cpu
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (game != null) String.format(Locale.ROOT, "%.0f", game.fps) else "${stats.cpu?.total?.roundToInt() ?: 0}",
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alignByBaseline(),
                fontSize = bigSize.sp,
                lineHeight = bigSize.sp,
                letterSpacing = (bigSize * -0.05f).sp,
                color = YaeColors.accent,
                maxLines = 1,
            )
            Text(if (game != null) "fps" else "%", Modifier.alignByBaseline(), fontSize = 16.sp, color = YaeColors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (game != null) {
                Figure("Frame time", String.format(Locale.ROOT, "%.1f ms", game.frametime))
                game.low1?.let { Figure("1% low", "${it.roundToInt()}") }
                game.low01?.let { Figure("0.1% low", "${it.roundToInt()}") }
                // Until there are enough frames for the lows, the range says something.
                if (game.low1 == null && values.isNotEmpty()) {
                    Figure("Last minute", "${values.min().roundToInt()}–${values.max().roundToInt()}")
                }
            } else {
                cpuTemperature(stats)?.let { Figure("Temperature", celsius(it)) }
                Figure("Threads", "${welcome.cores}")
                if (values.isNotEmpty()) Figure("Last minute", "${values.min().roundToInt()}–${values.max().roundToInt()}%")
            }
        }
        Spacer(if (fill) Modifier.weight(1f) else Modifier.height(8.dp))
        if (game == null) stats.cpu?.cores?.let { CoreBars(it, YaeColors.accent, Modifier.fillMaxWidth().height(28.dp)) }
        Box(Modifier.fillMaxWidth().height(if (game != null) 78.dp else 48.dp)) {
            GuideLines(Modifier.fillMaxSize())
            Sparkline(values, YaeColors.accent, Modifier.fillMaxSize(), max = if (game != null) null else 100f)
        }
        Row(Modifier.fillMaxWidth()) {
            Muted("60 s ago")
            Spacer(Modifier.weight(1f))
            if (game != null) Muted("via MangoHud")
            Spacer(Modifier.weight(1f))
            Muted("now")
        }
    }
}

/** Two dashed lines across a graph, at a quarter and three quarters of its height. */
@Composable
private fun GuideLines(modifier: Modifier) {
    val colors = YaeColors
    Canvas(modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
        for (y in listOf(size.height * 0.25f, size.height * 0.75f)) {
            drawLine(colors.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
    }
}

/** A small label over a value: "Frame time / 6.9 ms". */
@Composable
private fun Figure(label: String, value: String) {
    Column {
        Muted(label)
        Text(value, fontFamily = Mono, fontSize = 15.sp, maxLines = 1)
    }
}

/** One ring card: what it measures, the ring, and a line under it. */
private class RingItem(
    val label: String,
    val percent: Float?,
    val caption: String,
    val temperature: Float? = null,
    val battery: Boolean = false,
    val charging: Boolean = false,
    val rates: Pair<Long, Long>? = null,
    /** A line under the label, such as a GPU's full name. */
    val subtitle: String? = null,
)

private fun ringsFor(stats: Stats, welcome: Welcome): List<RingItem> = buildList {
    // Without a game the large panel already shows the CPU.
    if (stats.game != null) stats.cpu?.let { add(RingItem("CPU", it.total, "${welcome.cores} threads", cpuTemperature(stats))) }
    // Each GPU with its full name, in the PC's order so the cards never swap
    // places. A laptop's discrete GPU sleeps when idle.
    for (gpu in welcome.gpus) {
        val reading = stats.gpus?.find { it.id == gpu.id } ?: continue
        if (reading.sleeping) {
            add(RingItem("GPU", 0f, "Asleep", subtitle = gpu.name))
            continue
        }
        val used = reading.vramUsed
        val total = reading.vramTotal
        val caption = when {
            used != null && total != null && total > 0 -> "${gib(used)} of ${gib(total)} GiB VRAM"
            reading.clockMhz != null -> "${reading.clockMhz} MHz"
            else -> "Awake"
        }
        add(RingItem("GPU", reading.usage ?: 0f, caption, reading.temp, subtitle = gpu.name))
    }
    stats.mem?.let { mem ->
        add(RingItem("RAM", if (mem.total > 0) 100f * mem.used / mem.total else 0f, "${gib(mem.used)} of ${gib(mem.total)} GiB"))
    }
    stats.battery?.let { battery ->
        val charging = battery.state == BatteryState.CHARGING
        val watts = battery.powerW?.takeIf { it >= 0.5f }?.let { String.format(Locale.ROOT, " · %.0f W", it) }.orEmpty()
        add(RingItem("Battery", battery.percent, (if (charging) "Charging" else batteryStatus(battery).substringBefore(" ·")) + watts, battery = true, charging = charging))
    }
    stats.net?.let { add(RingItem("Network", null, "", rates = it.rx to it.tx)) }
    stats.disk?.let { add(RingItem("Disk", null, "", rates = it.read to it.write)) }
}.take(6)

@Composable
private fun RingGrid(items: List<RingItem>, columns: Int, modifier: Modifier, rowHeight: Dp?) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(columns)) {
            Row(
                (if (rowHeight == null) Modifier.weight(1f) else Modifier.height(rowHeight)).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in row) RingCard(item, Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RingCard(item: RingItem, modifier: Modifier) {
    DockCard(modifier) {
        CardHeader(item.label) {
            item.temperature?.let { HeaderValue(celsius(it), temperatureColor(it)) }
            if (item.charging) {
                Icon(painterResource(R.drawable.ic_bolt), null, Modifier.height(14.dp), tint = YaeColors.amber)
            }
        }
        item.subtitle?.let { Muted(it, size = 11.sp) }
        val rates = item.rates
        if (rates != null) {
            val (first, second) = if (item.label == "Disk") "Read" to "Write" else "↓ Down" to "↑ Up"
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                Rate(first, rates.first)
                Rate(second, rates.second)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ValueRing(item.percent ?: 0f, 84.dp, color = if (item.battery) YaeColors.amber else YaeColors.accent)
            }
            Muted(item.caption, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun Rate(label: String, bytesPerSecond: Long) {
    val (number, unit) = splitBytes(bytesPerSecond)
    Column {
        Muted(label)
        MonoValue(number, " $unit/s", size = 20.sp)
    }
}
