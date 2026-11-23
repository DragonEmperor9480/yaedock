package com.dragonemperor9480.yaedock.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeakerJitterBufferTest {
    private val rate = 48_000
    private val chunk = rate / 100

    /** [ms] of a steady tone at [level], as the PC sends it. */
    private fun tone(ms: Int, level: Short = 10_000): ByteArray {
        val frames = rate * ms / 1000
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames * 2) {
            bytes[i * 2] = (level.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (level.toInt() shr 8).toByte()
        }
        return bytes
    }

    private fun SpeakerJitterBuffer.add(ms: Int, now: Long) = tone(ms).let { push(it, 0, it.size, now) }

    private fun SpeakerJitterBuffer.next(now: Long): ShortArray = ShortArray(chunk * 2).also { pull(it, now) }

    private fun ShortArray.silent() = all { it.toInt() == 0 }

    @Test
    fun waitsForTheTargetBeforePlaying() {
        val buffer = SpeakerJitterBuffer(rate)
        buffer.add(SpeakerJitterBuffer.START_MS - 20, 0)
        assertTrue(buffer.next(0).silent())
        buffer.add(20, 0)
        val first = buffer.next(0)
        assertTrue(!first.silent())
    }

    @Test
    fun fadesInAndPlaysWholePiecesAfter() {
        val buffer = SpeakerJitterBuffer(rate)
        buffer.add(200, 0)
        val first = buffer.next(0)
        assertEquals(0, first[0].toInt())
        assertEquals(10_000, first[chunk * 2 - 1].toInt())
        assertTrue(buffer.next(10).all { it.toInt() == 10_000 })
    }

    @Test
    fun fadesOutWhenRunningDryRatherThanCutting() {
        val buffer = SpeakerJitterBuffer(rate)
        buffer.add(SpeakerJitterBuffer.START_MS, 0)
        var last = ShortArray(0)
        var t = 0L
        while (buffer.waiting > 0) {
            last = buffer.next(t)
            t += 10
        }
        // The last sound before silence ends near zero, never at full level.
        val lastSound = last.indexOfLast { it.toInt() != 0 }
        assertTrue(lastSound < 0 || abs(last[lastSound].toInt()) < 10_000 / 2)
        assertTrue(buffer.next(t).silent())
    }

    @Test
    fun growsTheWaitAfterAStallAndShrinksItAfterACalmSpell() {
        val buffer = SpeakerJitterBuffer(rate)
        buffer.add(SpeakerJitterBuffer.START_MS, 0)
        var t = 0L
        while (buffer.waiting > 0) buffer.next(t).also { t += 10 }
        buffer.next(t)
        // Sound back soon after running dry: the link stalled.
        buffer.add(20, t + 100)
        assertEquals(SpeakerJitterBuffer.START_MS + SpeakerJitterBuffer.STEP_UP_MS, buffer.targetMs)

        // A new sound long after the last one is not a stall.
        val other = SpeakerJitterBuffer(rate)
        other.add(SpeakerJitterBuffer.START_MS, 0)
        var u = 0L
        while (other.waiting > 0) other.next(u).also { u += 10 }
        other.add(20, u + 5_000)
        assertEquals(SpeakerJitterBuffer.START_MS, other.targetMs)

        // A calm spell brings the first one's wait back down.
        buffer.next(t + 100 + SpeakerJitterBuffer.CALM_MS)
        assertEquals(SpeakerJitterBuffer.START_MS + SpeakerJitterBuffer.STEP_UP_MS - SpeakerJitterBuffer.STEP_DOWN_MS, buffer.targetMs)
    }

    @Test
    fun skipsAheadWhenTooFarBehind() {
        val buffer = SpeakerJitterBuffer(rate)
        buffer.add(SpeakerJitterBuffer.START_MS, 0)
        buffer.next(0)
        // A stalled burst arrives all at once.
        buffer.add(400, 10)
        buffer.next(10)
        assertTrue(buffer.waiting <= rate * SpeakerJitterBuffer.START_MS / 1000)
    }

    @Test
    fun keepsPlayingThroughEvenArrivals() {
        val buffer = SpeakerJitterBuffer(rate)
        var t = 0L
        var silentPieces = 0
        // Sound arrives every 21 ms, as the PC sends it; the speaker takes 10 ms at a time.
        var nextArrival = 0L
        repeat(500) {
            while (nextArrival <= t) {
                buffer.add(21, nextArrival)
                nextArrival += 21
            }
            if (buffer.next(t).silent()) silentPieces++
            t += 10
        }
        // Only the wait at the start is silent.
        assertTrue("silent pieces: $silentPieces", silentPieces <= SpeakerJitterBuffer.START_MS / 10 + 1)
    }

    @Test
    fun dropsQuietPiecesToCatchUp() {
        val buffer = SpeakerJitterBuffer(rate)
        val start = ByteArray(rate * SpeakerJitterBuffer.START_MS / 1000 * 4)
        buffer.push(start, 0, start.size, 0)
        buffer.next(0)
        // Quiet sound piles up past the wait, as when the link sent a burst.
        val quiet = ByteArray(rate * 100 / 1000 * 4)
        buffer.push(quiet, 0, quiet.size, 10)
        val before = buffer.waiting
        buffer.next(10)
        buffer.next(20)
        val target = rate * buffer.targetMs / 1000
        assertTrue(buffer.waiting < before - 2 * chunk)
        assertTrue(buffer.waiting >= target)
    }

    @Test
    fun neverDropsSoundToCatchUp() {
        val buffer = SpeakerJitterBuffer(rate)
        buffer.add(SpeakerJitterBuffer.START_MS + 100, 0)
        val before = buffer.waiting
        buffer.next(0)
        buffer.next(10)
        assertEquals(before - 2 * chunk, buffer.waiting)
    }
}
