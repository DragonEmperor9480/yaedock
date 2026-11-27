package com.dragonemperor9480.yaedock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.play.DayTotal
import com.dragonemperor9480.yaedock.play.GameTotal
import com.dragonemperor9480.yaedock.play.Period
import com.dragonemperor9480.yaedock.play.Span
import com.dragonemperor9480.yaedock.play.blocks
import com.dragonemperor9480.yaedock.play.colorIndex
import com.dragonemperor9480.yaedock.play.duration
import com.dragonemperor9480.yaedock.play.perDay
import com.dragonemperor9480.yaedock.play.perGame
import com.dragonemperor9480.yaedock.play.playedWithin
import com.dragonemperor9480.yaedock.play.shortDuration
import com.dragonemperor9480.yaedock.protocol.PlayHistory
import com.dragonemperor9480.yaedock.protocol.PlaySession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Whether the PC keeps play history, and if not, why. */
sealed interface PlayAccess {
    data object Available : PlayAccess
    /** MangoHud does not log for YaeDock, so nothing is recorded. */
    data object FrameRatesOff : PlayAccess
    /** A YaeDock from before play history; [version] is empty when too old to say. */
    data class OldPc(val version: String) : PlayAccess
}

/** How often a range that includes now asks again, so a running game's time grows. */
private const val REFRESH_MS = 60_000L

/** A game's colour, the same everywhere it shows. */
@Composable
fun gameColor(program: String): Color {
    val palette = listOf(YaeColors.accent, YaeColors.amber, Color(0xFF7AA7FF), Color(0xFFE58FD0), Color(0xFFB4A0FF))
    return palette[colorIndex(program, palette.size)]
}

/**
 * What was played on the PC, when and for how long: a day, a week or a month
 * at a time. [load] asks the PC; [renames] changes whenever a game is renamed,
 * which asks again.
 */
@Composable
fun PlayHistoryScreen(
    pcName: String,
    access: PlayAccess,
    wide: Boolean,
    renames: Int,
    load: suspend (from: Long, to: Long) -> PlayHistory?,
    onSession: (String) -> Unit,
    onRename: (program: String, name: String) -> Unit,
    onUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    var spanName by rememberSaveable { mutableStateOf(Span.DAY.name) }
    var shownDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val span = Span.valueOf(spanName)
    val period = Period.of(span, LocalDate.ofEpochDay(shownDay))
    val show = { next: Period -> shownDay = next.first.toEpochDay() }
    var renaming by remember { mutableStateOf<PlaySession?>(null) }

    var loaded by remember { mutableStateOf<Pair<Period, List<PlaySession>?>?>(null) }
    var attempt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(period, renames, access, attempt) {
        if (access != PlayAccess.Available) return@LaunchedEffect
        while (true) {
            val history = load(period.from(zone), period.to(zone))
            loaded = period to history?.sessions
            if (history == null || today !in period) break
            delay(REFRESH_MS)
        }
    }

    val picker = @Composable { SpanPicker(span, fill = !wide) { spanName = it.name; shownDay = today.toEpochDay() } }
    val pager = @Composable {
        Pager(periodLabel(period, today, short = wide), canGoOn = period.last.isBefore(today), wide, onBack = { show(period.previous()) }, onNext = { show(period.next()) })
    }
    val header = @Composable {
        ScreenHeader("Play history", pcName, leading = { BackButton(onBack) }) {
            if (wide && access == PlayAccess.Available) {
                picker()
                pager()
            }
        }
    }
    val sessions = loaded?.takeIf { it.first == period }?.second
    val body = @Composable { modifier: Modifier ->
        when {
            access != PlayAccess.Available -> AccessCard(pcName, access, wide, onUpdates, modifier)
            loaded?.first != period -> Box(modifier, contentAlignment = Alignment.Center) { Muted("Asking $pcName…", size = 14.sp) }
            sessions == null -> Box(modifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted("$pcName didn't answer", size = 14.sp)
                    ChipButton("Try again", { attempt++ }, Modifier.width(140.dp), height = 40.dp)
                }
            }
            span == Span.DAY -> DayView(period.first, today, sessions, zone, wide, onSession, { renaming = it }, modifier)
            else -> SpanView(period, today, sessions, zone, wide, { shownDay = it.toEpochDay(); spanName = Span.DAY.name }, modifier)
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
            if (access == PlayAccess.Available) {
                picker()
                pager()
            }
            body(Modifier.fillMaxWidth().heightIn(min = 240.dp))
            BarRoom()
        }
    }

    renaming?.let { session ->
        RenameGameDialog(session.program, session.game, onDismiss = { renaming = null }) { name ->
            onRename(session.program, name)
            renaming = null
        }
    }
}

/** "Today", "Yesterday", "Fri 27 Nov"; "23 – 29 Nov"; "November 2026". */
private fun periodLabel(period: Period, today: LocalDate, short: Boolean): String = when (period.span) {
    Span.DAY -> when (period.first) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> period.first.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    Span.WEEK -> if (period.first.month == period.last.month) {
        "${period.first.dayOfMonth} – ${period.last.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
    } else {
        "${period.first.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} – ${period.last.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
    }
    Span.MONTH -> period.first.format(DateTimeFormatter.ofPattern(if (short) "MMM yyyy" else "MMMM yyyy", Locale.getDefault()))
}

@Composable
private fun SpanPicker(span: Span, fill: Boolean, onPick: (Span) -> Unit) {
    Row((if (fill) Modifier.fillMaxWidth() else Modifier).height(42.dp).clip(RoundedCornerShape(21.dp)).background(YaeColors.card).padding(3.dp)) {
        for (option in Span.entries) {
            val on = option == span
            Box(
                Modifier.fillMaxHeight().then(if (fill) Modifier.weight(1f) else Modifier.width(64.dp))
                    .clip(RoundedCornerShape(18.dp)).background(if (on) YaeColors.accentSoft else Color.Transparent)
                    .clickable(role = Role.Tab) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) YaeColors.accent else YaeColors.muted,
                )
            }
        }
    }
}

@Composable
private fun Pager(label: String, canGoOn: Boolean, wide: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        (if (wide) Modifier else Modifier.fillMaxWidth()).height(42.dp).clip(RoundedCornerShape(21.dp)).background(YaeColors.card).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerButton(R.drawable.ic_chevron_left, "Earlier", true, onBack)
        Text(
            label,
            (if (wide) Modifier.padding(horizontal = 6.dp) else Modifier.weight(1f)),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        PagerButton(R.drawable.ic_chevron_right, "Later", canGoOn, onNext)
    }
}

@Composable
private fun PagerButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, Modifier.size(18.dp), tint = if (enabled) YaeColors.text else YaeColors.track)
    }
}

/** A clock time in the phone's style. */
@Composable
fun clockTime(secs: Long, zone: ZoneId): String {
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return Instant.ofEpochSecond(secs).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Composable
private fun DayView(
    day: LocalDate,
    today: LocalDate,
    sessions: List<PlaySession>,
    zone: ZoneId,
    wide: Boolean,
    onSession: (String) -> Unit,
    onRename: (PlaySession) -> Unit,
    modifier: Modifier,
) {
    val from = day.atStartOfDay(zone).toEpochSecond()
    val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val games = perGame(sessions, from, to)
    val live = sessions.lastOrNull { it.live }
    val label = if (day == today) "Played today" else "Played"
    val total = @Composable { m: Modifier -> TotalCard(label, games, sessions.size, live, m) }
    val strip = @Composable { m: Modifier ->
        DockCard(m, padding = 16.dp, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("THROUGH THE DAY", Modifier.weight(1f), style = CardLabel)
                if (wide && sessions.isNotEmpty()) Muted("Tap a session for its frame rate", size = 12.sp)
            }
            DayStrip(sessions, day, zone, if (day == today) Instant.now().epochSecond else null)
            if (wide) SessionList(sessions.asReversed(), zone, onSession, onRename, Modifier.weight(1f).verticalScroll(rememberScrollState()))
        }
    }
    if (sessions.isEmpty()) {
        EmptyDay(day == today, modifier)
        return
    }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            total(Modifier.width(268.dp).fillMaxHeight())
            strip(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            total(Modifier.fillMaxWidth())
            strip(Modifier.fillMaxWidth())
            DockCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 0.dp) {
                Text("SESSIONS", Modifier.padding(bottom = 6.dp), style = CardLabel)
                SessionList(sessions.asReversed(), zone, onSession, onRename, Modifier)
                Muted("Tap a session for its frame rate; hold it to rename the game.", Modifier.padding(top = 8.dp), size = 12.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun EmptyDay(isToday: Boolean, modifier: Modifier) {
    DockCard(modifier, padding = 24.dp, spacing = 10.dp) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_gamepad), null, Modifier.size(26.dp), tint = YaeColors.accent)
        }
        Text(if (isToday) "Nothing played today yet" else "Nothing played this day", fontFamily = Grotesk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Muted("Games run with MangoHud show up here: when you played, for how long, and how smoothly they ran.", size = 13.sp, maxLines = 3)
        Spacer(Modifier.weight(1f))
    }
}

/** The total large, the share of each game, and the game running now. */
@Composable
private fun TotalCard(label: String, games: List<GameTotal>, sessionCount: Int, live: PlaySession?, modifier: Modifier) {
    val total = games.sumOf { it.played }
    DockCard(modifier, padding = 16.dp, spacing = 8.dp) {
        Text(label.uppercase(), style = CardLabel)
        Text(duration(total), fontFamily = Grotesk, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.accent, maxLines = 1)
        val gameWord = if (games.size == 1) "game" else "games"
        val sessionWord = if (sessionCount == 1) "session" else "sessions"
        Muted("$sessionCount $sessionWord · ${games.size} $gameWord", size = 12.sp)
        Spacer(Modifier.weight(1f, fill = false).height(4.dp))
        if (total > 0) {
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (game in games) Box(Modifier.weight(game.played.coerceAtLeast(1).toFloat()).fillMaxHeight().background(gameColor(game.program)))
            }
        }
        for (game in games.take(4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(gameColor(game.program), 10.dp)
                Text(gameTitle(game.game), Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(duration(game.played), fontFamily = Grotesk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (live != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(YaeColors.accentSoft.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Dot(YaeColors.accent, 8.dp)
                Text("Playing now: ${gameTitle(live.game)}", Modifier.weight(1f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(duration(live.playedSecs), fontFamily = Grotesk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.accent)
            }
        }
    }
}

/** The day as a strip of 24 hours, a block for each session; [now] marks the time on today's. */
@Composable
private fun DayStrip(sessions: List<PlaySession>, day: LocalDate, zone: ZoneId, now: Long?) {
    val placed = blocks(sessions, day, zone)
    val from = day.atStartOfDay(zone).toEpochSecond()
    val length = (day.plusDays(1).atStartOfDay(zone).toEpochSecond() - from).toFloat()
    val colors = placed.map { gameColor(it.session.program) }
    val track = YaeColors.track
    val background = YaeColors.background
    val text = YaeColors.text
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "Sessions through the day" },
    ) {
        val radius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
        drawRoundRect(background, cornerRadius = radius)
        for (q in 1..3) {
            val x = size.width * q / 4
            drawLine(track.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1.dp.toPx())
        }
        val inset = 6.dp.toPx()
        placed.forEachIndexed { i, block ->
            val left = size.width * block.start
            // At least a few pixels, so a short session still shows.
            val width = (size.width * (block.end - block.start)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                colors[i],
                topLeft = androidx.compose.ui.geometry.Offset(left, inset),
                size = androidx.compose.ui.geometry.Size(width, size.height - 2 * inset),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
        now?.let {
            val x = size.width * ((it - from) / length).coerceIn(0f, 1f)
            drawLine(text, androidx.compose.ui.geometry.Offset(x, 3.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height - 3.dp.toPx()), 2.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth()) {
        for ((i, hour) in listOf("00", "06", "12", "18", "24").withIndex()) {
            if (i > 0) Spacer(Modifier.weight(1f))
            Muted(hour, size = 11.sp)
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<PlaySession>,
    zone: ZoneId,
    onSession: (String) -> Unit,
    onRename: (PlaySession) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        for (session in sessions) SessionRow(session, zone, { onSession(session.id) }, { onRename(session) })
    }
}

@Composable
private fun SessionRow(session: PlaySession, zone: ZoneId, onClick: () -> Unit, onLongClick: () -> Unit) {
    val end = if (session.live) "now" else clockTime(session.end, zone)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(14.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Show its frame rate", onLongClickLabel = "Rename the game", onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(gameColor(session.program), 10.dp)
        Column(Modifier.weight(1f)) {
            Text(gameTitle(session.game), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${clockTime(session.start, zone)} – $end", size = 12.sp)
        }
        if (session.live) Chip("Playing", YaeColors.accent, YaeColors.accentSoft)
        if (session.fps > 0) Chip("${session.fps.roundToInt()} fps", YaeColors.text, YaeColors.raised)
        Text(duration(session.playedSecs), Modifier.width(76.dp), fontFamily = Grotesk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1)
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = YaeColors.muted)
    }
}

@Composable
private fun Chip(text: String, color: Color, background: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(999.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
        fontFamily = Grotesk,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
    )
}

/** A week or a month: totals, a chart of the days, and the games. */
@Composable
private fun SpanView(
    period: Period,
    today: LocalDate,
    sessions: List<PlaySession>,
    zone: ZoneId,
    wide: Boolean,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val days = perDay(sessions, period, zone)
    val from = period.from(zone)
    val to = period.to(zone)
    val games = perGame(sessions, from, to)
    val summary = @Composable { m: Modifier -> SpanSummary(period, today, days, sessions, from, to, m) }
    val chart = @Composable { m: Modifier ->
        DockCard(m, padding = 16.dp, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EACH DAY", Modifier.weight(1f), style = CardLabel)
                Muted("Tap a day to open it", size = 12.sp)
            }
            if (period.span == Span.WEEK) WeekBars(days, today, onDay, Modifier.weight(1f).fillMaxWidth()) else MonthGrid(period, days, today, onDay, Modifier.weight(1f).fillMaxWidth())
        }
    }
    val list = @Composable { m: Modifier -> GamesCard(games, m) }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            summary(Modifier.width(200.dp).fillMaxHeight())
            chart(Modifier.weight(1f).fillMaxHeight())
            list(Modifier.width(236.dp).fillMaxHeight())
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            summary(Modifier.fillMaxWidth())
            chart(Modifier.fillMaxWidth().height(if (period.span == Span.WEEK) 240.dp else 330.dp))
            list(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SpanSummary(period: Period, today: LocalDate, days: List<DayTotal>, sessions: List<PlaySession>, from: Long, to: Long, modifier: Modifier) {
    val total = days.sumOf { it.total }
    val played = days.count { it.total > 0 }
    val longest = sessions.filter { playedWithin(it, from, to) > 0 }.maxByOrNull { it.playedSecs }
    DockCard(modifier, padding = 16.dp, spacing = 6.dp) {
        val title = when {
            period.span == Span.WEEK && today in period -> "This week"
            period.span == Span.WEEK -> "That week"
            else -> period.first.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
        }
        Text(title.uppercase(), style = CardLabel)
        Text(duration(total), fontFamily = Grotesk, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.accent, maxLines = 1)
        val elapsed = period.days.count { !it.isAfter(today) }
        Muted("Played on $played of $elapsed days", size = 12.sp)
        Spacer(Modifier.weight(1f, fill = false).height(6.dp))
        if (played > 0) Figure2("A day you played, on average", duration(total / played))
        longest?.let { Figure2("Longest session", duration(it.playedSecs), gameTitle(it.game)) }
    }
}

@Composable
private fun Figure2(label: String, value: String, note: String? = null) {
    Column(Modifier.padding(top = 4.dp)) {
        Muted(label, size = 12.sp)
        Text(value, fontFamily = Grotesk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        note?.let { Muted(it, size = 12.sp) }
    }
}

/** A bar for each day of the week, split by game. */
@Composable
private fun WeekBars(days: List<DayTotal>, today: LocalDate, onDay: (LocalDate) -> Unit, modifier: Modifier) {
    val most = days.maxOfOrNull { it.total }?.coerceAtLeast(60) ?: 60
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (day in days) {
            val future = day.day.isAfter(today)
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(if (day.day == today) YaeColors.raised.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable(enabled = !future, role = Role.Button) { onDay(day.day) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (day.total > 0) {
                        Column(Modifier.width(26.dp).fillMaxHeight(day.total.toFloat() / most), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for ((program, secs) in day.byProgram.entries.reversed()) {
                                Box(Modifier.weight(secs.coerceAtLeast(1).toFloat()).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(gameColor(program)))
                            }
                        }
                    }
                }
                Text(
                    when {
                        future -> " "
                        day.total == 0L -> "–"
                        else -> shortDuration(day.total)
                    },
                    fontFamily = Grotesk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (day.day == today) YaeColors.accent else YaeColors.muted,
                    maxLines = 1,
                )
                Text(
                    if (day.day == today) "Today" else day.day.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                    fontSize = 11.sp,
                    color = if (day.day == today) YaeColors.text else YaeColors.muted,
                    fontWeight = if (day.day == today) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The month as a calendar, each day darker the more was played. */
@Composable
private fun MonthGrid(period: Period, days: List<DayTotal>, today: LocalDate, onDay: (LocalDate) -> Unit, modifier: Modifier) {
    val blanks = period.first.dayOfWeek.value - 1
    val cells: List<DayTotal?> = List(blanks) { null } + days
    val weeks = cells.chunked(7)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (name in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
                Muted(name, Modifier.weight(1f), size = 10.5.sp, textAlign = TextAlign.Center)
            }
        }
        for (week in weeks) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    if (day == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        DayCell(day, today, { onDay(day.day) }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(day: DayTotal, today: LocalDate, onClick: () -> Unit, modifier: Modifier) {
    val future = day.day.isAfter(today)
    val minutes = day.total / 60
    val level = when {
        future || minutes == 0L -> 0
        minutes < 30 -> 1
        minutes < 60 -> 2
        minutes < 120 -> 3
        else -> 4
    }
    val accent = YaeColors.accent
    val background = when (level) {
        0 -> if (future) YaeColors.background else YaeColors.raised.copy(alpha = 0.5f)
        4 -> accent
        else -> accent.copy(alpha = 0.12f + 0.16f * level)
    }
    val text = when {
        level == 4 -> YaeColors.onAccent
        future || level == 0 -> YaeColors.muted
        else -> YaeColors.text
    }
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(background)
            .then(if (day.day == today) Modifier.border(2.dp, YaeColors.text, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(enabled = !future, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("${day.day.dayOfMonth}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = text, maxLines = 1)
        if (day.total >= 60) {
            Text(shortDuration(day.total).replace(" ", ""), Modifier.align(Alignment.End), fontFamily = Grotesk, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = text, maxLines = 1)
        }
    }
}

/** Each game's time, sessions and frame rate over the period. */
@Composable
private fun GamesCard(games: List<GameTotal>, modifier: Modifier) {
    val total = games.sumOf { it.played }.coerceAtLeast(1)
    DockCard(modifier, padding = 16.dp, spacing = 6.dp) {
        Text("GAMES", style = CardLabel)
        if (games.isEmpty()) Muted("Nothing played", size = 13.sp)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (game in games) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(gameColor(game.program), 10.dp)
                    Column(Modifier.weight(1f)) {
                        Text(gameTitle(game.game), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val word = if (game.sessions == 1) "session" else "sessions"
                        val fps = if (game.fps > 0) " · ${game.fps.roundToInt()} fps avg" else ""
                        Muted("${game.sessions} $word$fps", size = 12.sp)
                    }
                    Text(duration(game.played), fontFamily = Grotesk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(YaeColors.track)) {
                    Box(Modifier.fillMaxWidth(game.played.toFloat() / total).fillMaxHeight().background(gameColor(game.program)))
                }
            }
        }
    }
}

/** Why the PC keeps no history, and what to do about it. */
@Composable
private fun AccessCard(pcName: String, access: PlayAccess, wide: Boolean, onUpdates: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    DockCard(modifier, padding = 24.dp, spacing = 12.dp) {
        if (wide) Spacer(Modifier.weight(1f))
        when (access) {
            PlayAccess.Available -> {}
            PlayAccess.FrameRatesOff -> {
                IconTile(R.drawable.ic_gauge, YaeColors.amber, YaeColors.amberSoft)
                Text("Turn on game frame rates", fontFamily = Grotesk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Muted("$pcName learns what you play from MangoHud. Run this on the PC, then start games with MangoHud.", size = 14.sp, maxLines = 4)
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(YaeColors.background).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("yaedock fps on", Modifier.weight(1f), fontFamily = Mono, fontSize = 14.sp)
                    SquareButton(painterResource(R.drawable.ic_copy), "Copy the command", { copy(context, "yaedock fps on") }, background = YaeColors.raised)
                }
                Muted("In Steam, add mangohud %command% to a game's launch options.", size = 13.sp, maxLines = 2)
            }
            is PlayAccess.OldPc -> {
                IconTile(R.drawable.ic_laptop, YaeColors.text, YaeColors.raised)
                Text("Update YaeDock on $pcName", fontFamily = Grotesk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                val runs = if (access.version.isEmpty()) "an older YaeDock" else "YaeDock ${access.version}"
                Muted("$pcName runs $runs, which doesn't keep play history. Version 0.6.0 or newer does, from the day it's installed.", size = 14.sp, maxLines = 4)
                ChipButton("Update the PC", onUpdates, Modifier.width(180.dp), selected = true, height = 44.dp)
            }
        }
        if (wide) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun IconTile(icon: Int, tint: Color, background: Color) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(26.dp), tint = tint)
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Command", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/**
 * Renames a game everywhere: shows what MangoHud calls it, a field for the
 * new name, and a way back to the program's name.
 */
@Composable
fun RenameGameDialog(program: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YaeColors.card,
        title = { Text("Rename this game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted("MangoHud calls it", size = 13.sp)
                    Text(
                        program,
                        Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).background(YaeColors.raised).padding(horizontal = 6.dp, vertical = 1.dp),
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = YaeColors.text),
                    cursorBrush = SolidColor(YaeColors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(name) }),
                    decorationBox = { field ->
                        Box(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(YaeColors.background)
                                .border(2.dp, YaeColors.accent, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { field() }
                    },
                )
                Muted("Every phone paired with this PC shows this name, for past sessions too.", size = 12.sp, maxLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text("Use the program name") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
