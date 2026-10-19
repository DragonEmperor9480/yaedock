package com.dragonemperor9480.yaedock.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Examples from protocol/protocol.md, so the app and the daemon stay in step. */
class CodecTest {
    @Test
    fun encodesHelloWithoutEmptyFields() {
        val hello = Hello(deviceId = "5f2c9a", deviceName = "Pixel 6", code = "482913")
        assertEquals(
            """{"type":"hello","version":1,"device_id":"5f2c9a","device_name":"Pixel 6","code":"482913"}""",
            Codec.encode(hello),
        )
    }

    @Test
    fun encodesCommands() {
        assertEquals("""{"type":"set_volume","muted":true}""", Codec.encode(SetVolume(muted = true)))
        assertEquals(
            """{"type":"set_brightness","display":"backlight:amdgpu_bl1","percent":75}""",
            Codec.encode(SetBrightness("backlight:amdgpu_bl1", 75)),
        )
        assertEquals(
            """{"type":"subscribe","groups":["cpu","mem","temps"],"interval_ms":2000}""",
            Codec.encode(Subscribe(listOf(MetricGroup.CPU, MetricGroup.MEM, MetricGroup.TEMPS), 2000)),
        )
        assertEquals("""{"type":"ping"}""", Codec.encode(ClientPing))
    }

    @Test
    fun decodesWelcome() {
        val welcome = Codec.decode(
            """{"type":"welcome","version":1,"hostname":"laptop","os":"CachyOS","cpu_model":"AMD Ryzen 7 7840HS","cores":16,
             "sensors":["cpu_package","nvme"],"token":"q8Zr","state":{"displays":[{"id":"backlight:amdgpu_bl1","name":"Built-in display","percent":60}],"volume":{"percent":45,"muted":false}}}""",
        )
        assertEquals(
            Welcome(
                version = 1,
                hostname = "laptop",
                os = "CachyOS",
                cpuModel = "AMD Ryzen 7 7840HS",
                cores = 16,
                sensors = listOf("cpu_package", "nvme"),
                token = "q8Zr",
                state = State(
                    displays = listOf(DisplayState("backlight:amdgpu_bl1", "Built-in display", 60)),
                    volume = VolumeState(45, false),
                ),
            ),
            welcome,
        )
    }

    @Test
    fun decodesStats() {
        val stats = Codec.decode(
            """{"type":"stats","ts":1760838600000,"uptime":3600,
             "cpu":{"total":23.4,"cores":[18.0,31.2,22.5,21.9]},
             "mem":{"used":6549000000,"total":16642000000},
             "temps":{"cpu_package":54.0,"nvme":41.0},
             "net":{"rx":120400,"tx":8800},
             "disk":{"read":0,"write":524288}}""",
        ) as Stats
        assertEquals(1760838600000L, stats.ts)
        assertEquals(4, stats.cpu!!.cores.size)
        assertEquals(16642000000L, stats.mem!!.total)
        assertEquals(54.0f, stats.temps!!["cpu_package"])
        assertEquals(Disk(0, 524288), stats.disk)
    }

    @Test
    fun decodesPartialStatsAndUnknownFields() {
        val stats = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"cpu":{"total":5.0,"cores":[5.0]},"gpu":{"total":1}}""")
        assertEquals(Stats(ts = 1, uptime = 2, cpu = Cpu(5.0f, listOf(5.0f))), stats)
    }

    @Test
    fun decodesGpus() {
        val welcome = Codec.decode(
            """{"type":"welcome","version":1,"hostname":"pc","os":"Linux","cpu_model":"x","cores":4,"sensors":[],
             "state":{"displays":[]},"gpus":[{"id":"pci:0000:01:00.0","name":"NVIDIA GeForce GTX 1050 Mobile"}]}""",
        ) as Welcome
        assertEquals(listOf(GpuInfo("pci:0000:01:00.0", "NVIDIA GeForce GTX 1050 Mobile")), welcome.gpus)

        val stats = Codec.decode(
            """{"type":"stats","ts":1,"uptime":2,"gpus":[{"id":"a","usage":12.0,"clock_mhz":350},{"id":"b","sleeping":true}]}""",
        ) as Stats
        assertEquals(GpuStats(id = "a", usage = 12f, clockMhz = 350), stats.gpus!![0])
        assertEquals(GpuStats(id = "b", sleeping = true), stats.gpus!![1])

        val playing = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"game":{"name":"vkcube","fps":59.7,"frametime":16.7}}""") as Stats
        assertEquals(Game("vkcube", 59.7f, 16.7f), playing.game)

        // A daemon without GPU support sends neither field.
        val old = Codec.decode("""{"type":"welcome","version":1,"hostname":"pc","os":"Linux","cpu_model":"x","cores":4,"sensors":[],"state":{}}""") as Welcome
        assertEquals(emptyList<GpuInfo>(), old.gpus)
    }

    @Test
    fun decodesErrorsIncludingUnknownCodes() {
        assertEquals(
            Error(ErrorCode.UNSUPPORTED_DISPLAY, "no display with id hdmi-1"),
            Codec.decode("""{"type":"error","code":"unsupported_display","message":"no display with id hdmi-1"}"""),
        )
        assertEquals(
            Error(ErrorCode.UNKNOWN, "x"),
            Codec.decode("""{"type":"error","code":"rate_limited","message":"x"}"""),
        )
    }

    @Test
    fun ignoresUnknownMessages() {
        assertNull(Codec.decode("""{"type":"notification","text":"hi"}"""))
        assertNull(Codec.decode("not json"))
        assertEquals(ServerPing, Codec.decode("""{"type":"ping"}"""))
    }
}
