package com.dragonemperor9480.yaedock

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePartTest {
    @Test
    fun storesWhatIsHidden() {
        val hidden = setOf(HomePart.MEDIA, HomePart.DRIVES)
        assertEquals(hidden, HomePart.decode(HomePart.encode(hidden)))
        assertEquals(emptySet<HomePart>(), HomePart.decode(HomePart.encode(emptySet())))
    }

    @Test
    fun skipsPartsItDoesNotKnow() {
        // Nothing stored, and a part from a later version.
        assertEquals(emptySet<HomePart>(), HomePart.decode(null))
        assertEquals(setOf(HomePart.CLOCK), HomePart.decode("CLOCK,WEATHER"))
    }
}
