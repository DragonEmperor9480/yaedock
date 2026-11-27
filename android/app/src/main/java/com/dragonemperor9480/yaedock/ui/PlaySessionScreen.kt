package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.play.duration
import com.dragonemperor9480.yaedock.protocol.PlayMinute
import com.dragonemperor9480.yaedock.protocol.PlaySessionDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How often a session still being played asks again. */
private const val LIVE_REFRESH_MS = 30_000L

/**
 * One session: its frame rate minute by minute, with the 1% low and the
 * session's average, and the temperatures folded below.
 */
@Composable
fun PlaySessionScreen(
    pcName: String,
    id: String,
    wide: Boolean,
    renames: Int,
    load: suspend (String) -> PlaySessionDetail?,
    onRename: (program: String, name: String) -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var detail by remember { mutableStateOf<PlaySessionDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableLongStateOf(0L) }
    var renaming by remember { mutableStateOf(false) }
    var temperatures by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(id, renames, attempt) {
        failed = false
        while (true) {
            val answer = load(id)
            if (answer == null) {
                failed = detail == null
                break
            }
            detail = answer
            if (!answer.session.live) break
            delay(LIVE_REFRESH_MS)
        }
    }

    val session = detail?.session
    val subtitle = session?.let {
        val day = Instant.ofEpochSecond(it.start).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        val end = if (it.live) "now" else clockTime(it.end, zone)
        "$day · ${clockTime(it.start, zone)} – $end · $pcName"
    } ?: pcName
    val header = @Composable {
        ScreenHeader(session?.let { gameTitle(it.game) } ?: "Session", subtitle, leading = { BackButton(onBack) }) {
            if (session != null) SquareButton(painterResource(R.drawable.ic_pencil), "Rename the game", { renaming = true })
        }
    }

    val body = @Composable { modifier: Modifier ->
        val shown = detail
        when {
            shown != null -> SessionBody(shown, zone, wide, temperatures, { temperatures = !temperatures }, modifier)
            failed -> Box(modifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted("$pcName didn't answer", size = 14.sp)
                    ChipButton("Try again", { attempt++ }, Modifier.width(140.dp), height = 40.dp)
                }
            }
            else -> Box(modifier, contentAlignment = Alignment.Center) { Muted("Asking $pcName…", size = 14.sp) }
        }
    }

    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            body(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            body(Modifier.fillMaxWidth())
            BarRoom()
        }
    }

    if (renaming && session != null) {
        RenameGameDialog(session.program, session.game, onDismiss = { renaming = false }) { name ->
            onRename(session.program, name)
            renaming = false
        }
    }
}

@Composable
private fun SessionBody(detail: PlaySessionDetail, zone: ZoneId, wide: Boolean, temperatures: Boolean, onTemperatures: () -> Unit, modifier: Modifier) {
    val session = detail.session
    val minutes = detail.minutes
    val chips = @Composable { m: Modifier ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Average", if (session.fps > 0) "${session.fps.roundToInt()}" else "–", YaeColors.accent, Modifier.weight(1f))
            StatChip("1% low", session.low1?.roundToInt()?.toString() ?: "–", YaeColors.amber, Modifier.weight(1f))
            StatChip("0.1% low", session.low01?.roundToInt()?.toString() ?: "–", YaeColors.text, Modifier.weight(1f))
            StatChip("Played", duration(session.playedSecs), YaeColors.text, Modifier.weight(1.3f))
        }
    }
    val chart = @Composable { m: Modifier ->
        DockCard(m, padding = 16.dp, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("FRAME RATE", Modifier.weight(1f), style = CardLabel)
                Legend(YaeColors.accent, "fps")
                Legend(YaeColors.amber, "1% low")
                Legend(YaeColors.muted, "average", dashed = true)
            }
            if (minutes.size < 2) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).height(120.dp), contentAlignment = Alignment.Center) {
                    Muted("Too short for a graph", size = 13.sp)
                }
            } else {
                FpsChart(minutes, session.fps, Modifier.fillMaxWidth().then(if (wide) Modifier.weight(1f) else Modifier.height(200.dp)))
                Row(Modifier.fillMaxWidth()) {
                    Muted(clockTime(minutes.first().at, zone))
                    Spacer(Modifier.weight(1f))
                    Muted(if (session.live) "now" else clockTime(session.end, zone))
                }
            }
        }
    }
    val temps = @Composable { m: Modifier -> TemperatureCard(minutes, temperatures, onTemperatures, m) }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            chart(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip("Average", if (session.fps > 0) "${session.fps.roundToInt()}" else "–", YaeColors.accent, Modifier.weight(1f))
                        StatChip("Played", duration(session.playedSecs), YaeColors.text, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip("1% low", session.low1?.roundToInt()?.toString() ?: "–", YaeColors.amber, Modifier.weight(1f))
                        StatChip("0.1% low", session.low01?.roundToInt()?.toString() ?: "–", YaeColors.text, Modifier.weight(1f))
                    }
                }
                temps(Modifier.fillMaxWidth().weight(1f, fill = false))
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            chips(Modifier.fillMaxWidth())
            chart(Modifier.fillMaxWidth())
            temps(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(YaeColors.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Muted(label, size = 11.sp)
        Text(value, fontFamily = Grotesk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.width(14.dp).height(2.dp)) {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null,
            )
        }
        Muted(label, size = 11.sp)
    }
}

/** Rounds [value] up to a tidy top for a chart's scale: 60, 75, 100, 150 and so on. */
internal fun chartTop(value: Float): Float {
    if (value <= 0f) return 60f
    var step = 10f
    while (value / step > 10f) step *= 10f
    for (nice in listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 7.5f, 10f)) {
        if (nice * step >= value * 1.05f) return nice * step
    }
    return 10f * step
}

/** The frame rate, the 1% low under it, and the session's average dashed across. */
@Composable
private fun FpsChart(minutes: List<PlayMinute>, average: Float, modifier: Modifier) {
    val accent = YaeColors.accent
    val amber = YaeColors.amber
    val muted = YaeColors.muted
    val track = YaeColors.track
    val top = chartTop(minutes.maxOf { it.fps })
    Row(modifier.semantics { contentDescription = "Frame rate through the session" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Muted("${top.roundToInt()}", size = 10.sp)
            Muted("${(top / 2).roundToInt()}", size = 10.sp)
            Muted("0", size = 10.sp)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val step = size.width / (minutes.size - 1)
            fun y(value: Float) = size.height * (1f - (value / top).coerceIn(0f, 1f))
            for (q in 0..2) {
                val gy = size.height * q / 2
                drawLine(track.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
            }
            val line = Path().apply {
                minutes.forEachIndexed { i, m -> if (i == 0) moveTo(0f, y(m.fps)) else lineTo(step * i, y(m.fps)) }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, accent.copy(alpha = 0.14f))
            // The 1% low, where known, in runs: a minute without it breaks the line.
            val low = Path()
            var drawing = false
            minutes.forEachIndexed { i, m ->
                val v = m.low1
                if (v == null) {
                    drawing = false
                } else if (drawing) {
                    low.lineTo(step * i, y(v))
                } else {
                    low.moveTo(step * i, y(v))
                    drawing = true
                }
            }
            drawPath(low, amber, style = Stroke(width = 1.5.dp.toPx()))
            drawPath(line, accent, style = Stroke(width = 2.dp.toPx()))
            if (average > 0f) {
                drawLine(
                    muted,
                    Offset(0f, y(average)),
                    Offset(size.width, y(average)),
                    1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
            }
        }
    }
}

/** CPU and GPU temperature through the session, folded until asked for. */
@Composable
private fun TemperatureCard(minutes: List<PlayMinute>, open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val cpu = minutes.mapNotNull { it.cpuTemp }
    val gpu = minutes.mapNotNull { it.gpuTemp }
    if (cpu.isEmpty() && gpu.isEmpty()) return
    DockCard(modifier, padding = 0.dp, spacing = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Hide temperatures" else "Show temperatures", onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_thermometer), null, Modifier.size(18.dp), tint = YaeColors.muted)
            Column(Modifier.weight(1f)) {
                Text("Temperatures", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val peaks = listOfNotNull(
                    cpu.maxOrNull()?.let { "CPU up to ${celsius(it)}" },
                    gpu.maxOrNull()?.let { "GPU up to ${celsius(it)}" },
                ).joinToString(" · ")
                Muted(peaks, size = 12.sp)
            }
            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp), tint = YaeColors.muted)
        }
        if (open) {
            val cpuColor = Color(0xFF7AA7FF)
            val gpuColor = YaeColors.amber
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (cpu.isNotEmpty()) Legend(cpuColor, "CPU")
                    if (gpu.isNotEmpty()) Legend(gpuColor, "GPU")
                }
                val most = (cpu + gpu).max()
                val least = (cpu + gpu).min()
                val low = (least - 5f).coerceAtLeast(0f)
                val high = most + 5f
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    val step = size.width / (minutes.size - 1).coerceAtLeast(1)
                    fun y(v: Float) = size.height * (1f - ((v - low) / (high - low)).coerceIn(0f, 1f))
                    fun series(pick: (PlayMinute) -> Float?, color: Color) {
                        val path = Path()
                        var drawing = false
                        minutes.forEachIndexed { i, m ->
                            val v = pick(m)
                            if (v == null) {
                                drawing = false
                            } else if (drawing) {
                                path.lineTo(step * i, y(v))
                            } else {
                                path.moveTo(step * i, y(v))
                                drawing = true
                            }
                        }
                        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
                    }
                    series({ it.cpuTemp }, cpuColor)
                    series({ it.gpuTemp }, gpuColor)
                }
            }
        }
    }
}
