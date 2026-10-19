package com.dragonemperor9480.yaedock.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PinningTest {
    @Test
    fun hashesLikeTheDaemon() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun normalizesFingerprints() {
        assertEquals("d18c55060a", normalizeFingerprint("D1:8C 5506 0A"))
    }

    @Test
    fun backsOffUpToThirtySeconds() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map(DaemonConnection::backoffMs))
    }
}
