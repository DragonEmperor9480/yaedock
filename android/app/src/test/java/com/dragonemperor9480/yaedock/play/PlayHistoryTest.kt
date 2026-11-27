package com.dragonemperor9480.yaedock.play

import com.dragonemperor9480.yaedock.protocol.PlaySession
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayHistoryTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun at(date: String, time: String, zone: ZoneId): Long =
        LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(zone).toEpochSecond()

    private fun session(program: String, start: Long, end: Long, played: Long = end - start, fps: Float = 60f, game: String = program) =
        PlaySession("$program@$start", game, program, start, end, played, fps)

    @Test
    fun periodsRunMondayToSundayAndWholeMonths() {
        val friday = LocalDate.parse("2026-11-27")
        assertEquals(Period(Span.WEEK, LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-29")), Period.of(Span.WEEK, friday))
        val month = Period.of(Span.MONTH, friday)
        assertEquals(30, month.days.size)
        assertEquals(Period.of(Span.MONTH, LocalDate.parse("2026-10-05")), month.previous())
        assertEquals(LocalDate.parse("2026-11-28"), Period.of(Span.DAY, friday).next().first)
    }

    @Test
    fun aSessionPastMidnightCountsOnBothDays() {
        // 23:00 to 01:00, played throughout.
        val late = session("eldenring.exe", at("2026-11-26", "23:00", berlin), at("2026-11-27", "01:00", berlin))
        val days = perDay(listOf(late), Period.of(Span.WEEK, LocalDate.parse("2026-11-27")), berlin)
        assertEquals(3600L, days[3].total)
        assertEquals(3600L, days[4].total)
        assertEquals(0L, days[5].total)
        // The strip on Friday starts at midnight.
        val block = blocks(listOf(late), LocalDate.parse("2026-11-27"), berlin).single()
        assertEquals(0f, block.start)
        assertEquals(1f / 24, block.end, 0.0001f)
    }

    @Test
    fun daysFollowThePhonesTimeZone() {
        // 20:00 UTC is the next day in India.
        val evening = session("vkcube", at("2026-11-27", "20:00", ZoneId.of("UTC")), at("2026-11-27", "20:30", ZoneId.of("UTC")))
        val day = Period.of(Span.DAY, LocalDate.parse("2026-11-28"))
        assertEquals(1800L, perDay(listOf(evening), day, kolkata).single().total)
        assertEquals(LocalDate.parse("2026-11-28"), localDay(evening.start, kolkata))
    }

    @Test
    fun totalsByGameWeighTheFrameRateByTime() {
        val from = at("2026-11-27", "00:00", berlin)
        val to = at("2026-11-28", "00:00", berlin)
        val games = perGame(
            listOf(
                session("SB-Win64-Shipping", from + 3600, from + 7200, fps = 60f),
                session("vkcube", from + 8000, from + 8600, fps = 1000f),
                session("SB-Win64-Shipping", from + 36000, from + 46800, fps = 40f, game = "Stellar Blade"),
            ),
            from, to,
        )
        assertEquals(listOf("SB-Win64-Shipping", "vkcube"), games.map { it.program })
        val blade = games.first()
        assertEquals("Stellar Blade", blade.game)
        assertEquals(14400L, blade.played)
        assertEquals(2, blade.sessions)
        assertEquals(45f, blade.fps, 0.01f)
    }

    @Test
    fun playedTimeLeavesOutGaps() {
        // Two hours from start to end, but 90 minutes played.
        val gappy = session("x", 0, 7200, played = 5400)
        assertEquals(2700L, playedWithin(gappy, 0, 3600))
        assertEquals(0L, playedWithin(gappy, 7200, 9000))
    }

    @Test
    fun writesDurations() {
        assertEquals("under a minute", duration(59))
        assertEquals("48 m", duration(48 * 60 + 30))
        assertEquals("2 h 07 m", duration(2 * 3600 + 7 * 60))
        assertEquals("1 h 40", shortDuration(6000))
        assertEquals(colorIndex("vkcube", 4), colorIndex("vkcube", 4))
    }
}
