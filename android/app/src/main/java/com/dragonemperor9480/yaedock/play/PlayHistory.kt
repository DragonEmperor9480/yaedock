package com.dragonemperor9480.yaedock.play

import com.dragonemperor9480.yaedock.protocol.PlaySession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The ranges the play history shows. */
enum class Span { DAY, WEEK, MONTH }

/** A stretch of local days, from [first] to [last], both included. */
data class Period(val span: Span, val first: LocalDate, val last: LocalDate) {
    val days: List<LocalDate> get() = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()

    /** Unix seconds at the start of [first] and the end of [last]. */
    fun from(zone: ZoneId): Long = first.atStartOfDay(zone).toEpochSecond()
    fun to(zone: ZoneId): Long = last.plusDays(1).atStartOfDay(zone).toEpochSecond()

    fun previous(): Period = of(span, first.minusDays(1))
    fun next(): Period = of(span, last.plusDays(1))

    operator fun contains(day: LocalDate): Boolean = !day.isBefore(first) && !day.isAfter(last)

    companion object {
        /** The day, the week from Monday, or the month that [day] falls in. */
        fun of(span: Span, day: LocalDate): Period = when (span) {
            Span.DAY -> Period(span, day, day)
            Span.WEEK -> {
                val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Period(span, monday, monday.plusDays(6))
            }
            Span.MONTH -> Period(span, day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()))
        }
    }
}

/**
 * The part of [session] that falls between [from] and [to] (Unix seconds), as
 * seconds played. A session past midnight counts on each day for its share,
 * at its own pace: played time spread evenly over its span.
 */
fun playedWithin(session: PlaySession, from: Long, to: Long): Long {
    val start = maxOf(session.start, from)
    val end = minOf(session.end, to)
    if (end <= start) return 0
    val span = session.end - session.start
    if (span <= 0) return session.playedSecs
    return session.playedSecs * (end - start) / span
}

/** Time played on one day, by program. */
data class DayTotal(val day: LocalDate, val byProgram: Map<String, Long>) {
    val total: Long get() = byProgram.values.sum()
}

/** Each day of [period] with the time played on it, by program. */
fun perDay(sessions: List<PlaySession>, period: Period, zone: ZoneId): List<DayTotal> =
    period.days.map { day ->
        val from = day.atStartOfDay(zone).toEpochSecond()
        val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val byProgram = LinkedHashMap<String, Long>()
        for (session in sessions) {
            val played = playedWithin(session, from, to)
            if (played > 0) byProgram.merge(session.program, played, Long::plus)
        }
        DayTotal(day, byProgram)
    }

/** One game's time over a period. [fps] is the average, weighted by time played. */
data class GameTotal(val program: String, val game: String, val played: Long, val sessions: Int, val fps: Float)

/** Each game played between [from] and [to], most played first. */
fun perGame(sessions: List<PlaySession>, from: Long, to: Long): List<GameTotal> =
    sessions.mapNotNull { session -> playedWithin(session, from, to).takeIf { it > 0 }?.let { session to it } }
        .groupBy { (session, _) -> session.program }
        .map { (program, played) ->
            val time = played.sumOf { it.second }
            val weighted = played.sumOf { (session, secs) -> session.fps.toDouble() * secs }
            GameTotal(
                program = program,
                // The newest name: a rename reaches every session alike.
                game = played.last().first.game,
                played = time,
                sessions = played.size,
                fps = if (time > 0) (weighted / time).toFloat() else 0f,
            )
        }
        .sortedByDescending { it.played }

/** A session's place on a day's 24-hour strip, as fractions of the day. */
data class Block(val session: PlaySession, val start: Float, val end: Float)

/** Where each of [sessions] sits on [day], cut at its midnights. */
fun blocks(sessions: List<PlaySession>, day: LocalDate, zone: ZoneId): List<Block> {
    val from = day.atStartOfDay(zone).toEpochSecond()
    val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val length = (to - from).toFloat()
    return sessions.filter { it.end > from && it.start < to }.map {
        Block(it, (maxOf(it.start, from) - from) / length, (minOf(it.end, to) - from) / length)
    }
}

/** `2 h 47 m`, `48 m`, `under a minute`. */
fun duration(secs: Long): String {
    val minutes = secs / 60
    return when {
        minutes == 0L -> "under a minute"
        minutes < 60 -> "$minutes m"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)} m"
    }
}

/** The same, short, for a bar's label: `2 h 47`, `48 m`. */
fun shortDuration(secs: Long): String {
    val minutes = secs / 60
    return if (minutes < 60) "$minutes m" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}

/** A program's place in the palette: stable, from its name, so a game keeps its colour. */
fun colorIndex(program: String, colors: Int): Int = Math.floorMod(program.hashCode(), colors)

/** The local day a Unix time falls on. */
fun localDay(secs: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(secs).atZone(zone).toLocalDate()
