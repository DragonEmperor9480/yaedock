package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.AudioOutput
import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.Cpu
import com.dragonemperor9480.yaedock.protocol.Game
import com.dragonemperor9480.yaedock.protocol.Mem
import com.dragonemperor9480.yaedock.protocol.Stats
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun formatsBytesAndRates() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("15.5 GiB", formatBytes(16_642_000_000))
    }

    @Test
    fun greetsByTheTimeOfDay() {
        assertEquals("Good morning, Shura", greeting(5, "Shura"))
        assertEquals("Good afternoon, Shura", greeting(12, " Shura "))
        assertEquals("Good evening, Shura", greeting(17, "Shura"))
        assertEquals("Good evening", greeting(2, "  "))
    }

    @Test
    fun formatsUptime() {
        assertEquals("12m", formatUptime(720))
        assertEquals("4h 12m", formatUptime(4 * 3600 + 720))
        assertEquals("3d 4h", formatUptime(3 * 86_400 + 4 * 3600 + 5))
    }

    @Test
    fun formatsRecordingTimes() {
        assertEquals("0:00", formatTimer(999))
        assertEquals("0:42", formatTimer(42_500))
        assertEquals("12:05", formatTimer(725_000))
        assertEquals("1:02:09", formatTimer(3_729_000))
    }

    @Test
    fun describesTheBattery() {
        assertEquals("Charging · full in 45m", batteryStatus(Battery(17f, BatteryState.CHARGING, secondsLeft = 2_727)))
        assertEquals("On battery · 2h 32m left", batteryStatus(Battery(64f, BatteryState.DISCHARGING, secondsLeft = 9_120)))
        assertEquals("On battery", batteryStatus(Battery(64f, BatteryState.DISCHARGING)))
        assertEquals("Plugged in, not charging", batteryStatus(Battery(80f, BatteryState.NOT_CHARGING)))
    }

    @Test
    fun labelsAudioOutputs() {
        assertEquals("Built-in Audio · Headphones", outputLabel(AudioOutput("a", "Built-in Audio Analog Stereo", "Headphones")))
        assertEquals("HDA Intel PCH (HDMI 2)", outputLabel(AudioOutput("b", "HDA Intel PCH Digital Stereo (HDMI 2)")))
        assertEquals("WH-1000XM4", outputLabel(AudioOutput("c", "WH-1000XM4")))
    }

    @Test
    fun startsAFreshFpsGraphForEachGame() {
        val history = StatsHistory()
        history.add(Stats(ts = 1, uptime = 0, game = Game("a", 60f, 16.7f)))
        history.add(Stats(ts = 2, uptime = 0, game = Game("a", 58f, 17.2f)))
        assertEquals(listOf(60f, 58f), history.history.value.fps)
        history.add(Stats(ts = 3, uptime = 0, game = Game("b", 144f, 6.9f)))
        assertEquals(listOf(144f), history.history.value.fps)
        history.add(Stats(ts = 4, uptime = 0))
        assertEquals(emptyList<Float>(), history.history.value.fps)
        assertEquals("eldenring", gameTitle("eldenring.exe"))
    }

    @Test
    fun labelsPowerProfiles() {
        assertEquals("Saver", profileLabel("power-saver"))
        assertEquals("Performance", profileLabel("performance"))
        assertEquals("Low power", profileLabel("low-power"))
    }

    @Test
    fun keepsTheLastSamples() {
        val history = StatsHistory(capacity = 3)
        for (i in 1..5) {
            history.add(Stats(ts = i.toLong(), uptime = 0, cpu = Cpu(i.toFloat(), emptyList()), mem = Mem(i.toLong(), 10)))
        }
        assertEquals(listOf(3f, 4f, 5f), history.history.value.cpu)
        assertEquals(listOf(30f, 40f, 50f), history.history.value.mem)
        assertEquals(emptyList<Float>(), history.history.value.netRx)
    }
}
