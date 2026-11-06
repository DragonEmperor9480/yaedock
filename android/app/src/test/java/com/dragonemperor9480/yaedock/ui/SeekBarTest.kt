package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekBarTest {
    private fun media(playing: Boolean, position: Long? = 60_000) =
        Media("spotify", "Spotify", playing = playing, lengthMs = 200_000, positionMs = position, canSeek = true)

    @Test
    fun movesOnOnlyWhilePlaying() {
        assertEquals(61_500L, playedMs(media(playing = true), 1_500))
        assertEquals(60_000L, playedMs(media(playing = false), 1_500))
    }

    @Test
    fun staysWithinTheTrack() {
        assertEquals(200_000L, playedMs(media(playing = true), 500_000))
        assertNull(playedMs(media(playing = true, position = null), 0))
    }

    @Test
    fun keepsTheArrivalTimeWhileNothingChanges() {
        val first = media(playing = true).stamped(null, 1_000)
        assertEquals(1_000L, first.receivedAt)
        // The PC resends the same position while the track plays on.
        assertEquals(1_000L, media(playing = true).stamped(first, 5_000).receivedAt)
        // A jump is counted from when it arrived.
        assertEquals(5_000L, media(playing = true, position = 90_000).stamped(first, 5_000).receivedAt)
    }
}
