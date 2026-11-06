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
}
