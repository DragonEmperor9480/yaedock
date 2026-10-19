package com.dragonemperor9480.yaedock.ui

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
        assertEquals("117.6 KiB/s", formatRate(120_400))
    }

    @Test
    fun formatsUptime() {
        assertEquals("12m", formatUptime(720))
        assertEquals("4h 12m", formatUptime(4 * 3600 + 720))
        assertEquals("3d 4h", formatUptime(3 * 86_400 + 4 * 3600 + 5))
    }

    @Test
    fun labelsSensors() {
        assertEquals("CPU package", sensorLabel("cpu_package"))
        assertEquals("Coretemp core 3", sensorLabel("coretemp_core_3"))
        assertEquals("NVME", sensorLabel("nvme"))
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
