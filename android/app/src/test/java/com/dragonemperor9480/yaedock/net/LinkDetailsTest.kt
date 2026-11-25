package com.dragonemperor9480.yaedock.net

import android.net.wifi.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkDetailsTest {
    @Test
    fun namesTheBand() {
        assertEquals("2.4 GHz", wifiBand(2437))
        assertEquals("5 GHz", wifiBand(5180))
        assertEquals("6 GHz", wifiBand(5955))
        assertNull(wifiBand(900))
    }

    @Test
    fun namesTheGeneration() {
        assertEquals("Wi-Fi 5", wifiGeneration(ScanResult.WIFI_STANDARD_11AC, 5180))
        assertEquals("Wi-Fi 6", wifiGeneration(ScanResult.WIFI_STANDARD_11AX, 5180))
        assertEquals("Wi-Fi 6E", wifiGeneration(ScanResult.WIFI_STANDARD_11AX, 5955))
        assertEquals("Wi-Fi 7", wifiGeneration(ScanResult.WIFI_STANDARD_11BE, 5955))
        assertNull(wifiGeneration(null, 5180))
    }

    @Test
    fun summarisesWhatIsKnown() {
        assertEquals(
            "5 GHz · Wi-Fi 6 · 1201 Mbps · Excellent signal",
            wifiSummary(WifiDetails(5180, ScanResult.WIFI_STANDARD_11AX, 1201, -48)),
        )
        // Before Android 11 the generation is unknown.
        assertEquals("2.4 GHz · 72 Mbps · Weak signal", wifiSummary(WifiDetails(2412, null, 72, -80)))
    }

    @Test
    fun namesTheCableSpeed() {
        assertEquals("USB 2.0 · 480 Mbps", usbSpeed(480))
        assertEquals("USB 3 · 5 Gbps", usbSpeed(5000))
        assertEquals("USB 3 · 10 Gbps", usbSpeed(10000))
        assertEquals("USB 1.1 · 12 Mbps", usbSpeed(12))
    }
}
