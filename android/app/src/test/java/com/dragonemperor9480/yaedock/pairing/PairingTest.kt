package com.dragonemperor9480.yaedock.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fp = "d18c5506223c3e718a449d7ba0df9baec25a7f5ed1a6715f87b80715115cd03a"

    @Test
    fun parsesTheQrCodeUri() {
        assertEquals(
            PairingInfo("172.18.115.185", 47820, fp, "319820"),
            PairingUri.parse("yaedock://pair?host=172.18.115.185&port=47820&fp=$fp&code=319820"),
        )
    }

    @Test
    fun rejectsOtherCodes() {
        assertNull(PairingUri.parse("https://example.com/?host=a&port=1&fp=$fp&code=123456"))
        assertNull(PairingUri.parse("yaedock://pair?host=a&port=1&fp=abc&code=123456"))
        assertNull(PairingUri.parse("yaedock://pair?host=a&port=99999&fp=$fp&code=123456"))
        assertNull(PairingUri.parse("yaedock://pair?host=a&port=1&fp=$fp&code=12345"))
        assertNull(PairingUri.parse("not a uri at all"))
    }

    @Test
    fun acceptsDetailsTypedAsPrinted() {
        val spaced = fp.chunked(4).joinToString(" ")
        assertEquals(
            PairingInfo("192.168.1.5", 47820, fp, "319820"),
            PairingUri.validate(" 192.168.1.5 ", "47820", spaced.uppercase(), "319 820"),
        )
    }

    @Test
    fun storesPairedPcsAndDeviceId() {
        val dir = tmp.newFolder()
        val store = PcStore(dir)
        val pc = PairedPc(fp, "shura-pc", "192.168.1.5", 47820, "token1")
        store.save(pc)
        store.save(pc.copy(token = "token2"))
        store.updateAddress(fp, "192.168.1.9", 47820)
        val id = store.deviceId

        val reloaded = PcStore(dir)
        assertEquals(listOf(pc.copy(host = "192.168.1.9", token = "token2")), reloaded.pcs.value)
        assertEquals(id, reloaded.deviceId)
        assertNotEquals(id, PcStore(tmp.newFolder()).deviceId)

        reloaded.remove(fp)
        assertEquals(emptyList<PairedPc>(), PcStore(dir).pcs.value)
    }
}
