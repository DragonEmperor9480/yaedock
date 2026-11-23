package com.dragonemperor9480.yaedock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPlacementTest {
    @Test
    fun aLayoutComesBackAsItWasStored() {
        val custom = mapOf(
            ControllerPiece.LEFT_STICK to Placement(0.2f, 0.7f, 1.3f),
            ControllerPiece.GUIDE to Placement(0.5f, 0.1f, 0.8f),
        )
        assertEquals(custom, ControllerPlacements.decode(ControllerPlacements.encode(custom)))
    }

    @Test
    fun partsItCannotReadAreLeftOut() {
        val read = ControllerPlacements.decode("LEFT_STICK:0.2,0.7,1.0;NOPE:1,1,1;DPAD:x,1,1;FACE:0.5;RT:0.9,0.1,1")
        assertEquals(setOf(ControllerPiece.LEFT_STICK, ControllerPiece.RT), read.keys)
        assertTrue(ControllerPlacements.decode(null).isEmpty())
        assertTrue(ControllerPlacements.decode("").isEmpty())
    }

    @Test
    fun storedValuesAreKeptOnTheScreenAndWithinTheSizes() {
        val read = ControllerPlacements.decode("LB:-0.5,1.5,9")
        assertEquals(Placement(0f, 1f, Placement.MAX_SCALE), read[ControllerPiece.LB])
    }

    @Test
    fun everyLayoutPlacesEveryPiece() {
        for (layout in ControllerLayout.entries) {
            val placed = ControllerSettings(layout = layout).placements()
            assertEquals(ControllerPiece.entries.toSet(), placed.keys)
        }
    }

    @Test
    fun theCustomLayoutFillsInWhatTheUserDidNotMove() {
        val moved = Placement(0.3f, 0.3f, 1.2f)
        val settings = ControllerSettings(layout = ControllerLayout.CUSTOM, custom = mapOf(ControllerPiece.FACE to moved))
        val placed = settings.placements()
        assertEquals(moved, placed[ControllerPiece.FACE])
        assertEquals(ControllerPlacements.XBOX[ControllerPiece.DPAD], placed[ControllerPiece.DPAD])
    }
}
