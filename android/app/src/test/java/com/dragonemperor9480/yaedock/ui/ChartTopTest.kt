package com.dragonemperor9480.yaedock.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartTopTest {
    @Test
    fun roundsUpToATidyTop() {
        assertEquals(60f, chartTop(0f))
        assertEquals(75f, chartTop(68f))
        assertEquals(150f, chartTop(140f))
        assertEquals(1500f, chartTop(1250f))
        assertEquals(30f, chartTop(25f))
    }
}
