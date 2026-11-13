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

        val battery = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"battery":{"percent":64.0,"state":"discharging","seconds_left":9120,"health":91.5}}""") as Stats
        assertEquals(Battery(64f, BatteryState.DISCHARGING, secondsLeft = 9120, health = 91.5f), battery.battery)
        val later = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"battery":{"percent":5.0,"state":"cooling"}}""") as Stats
        assertEquals(BatteryState.UNKNOWN, later.battery!!.state)

        val playing = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"game":{"name":"vkcube","fps":59.7,"frametime":16.7}}""") as Stats
        assertEquals(Game("vkcube", 59.7f, 16.7f), playing.game)
        val lows = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"game":{"name":"vkcube","fps":59.7,"frametime":16.7,"low_1":48.2}}""") as Stats
        assertEquals(Game("vkcube", 59.7f, 16.7f, low1 = 48.2f), lows.game)

        // A daemon without GPU support sends neither field.
        val old = Codec.decode("""{"type":"welcome","version":1,"hostname":"pc","os":"Linux","cpu_model":"x","cores":4,"sensors":[],"state":{}}""") as Welcome
        assertEquals(emptyList<GpuInfo>(), old.gpus)
    }

    @Test
    fun decodesAndEncodesControls() {
        val state = Codec.decode(
            """{"type":"state","displays":[],"mic":{"percent":100,"muted":true},
             "apps":[{"id":551,"name":"Firefox","percent":80,"muted":false}],
             "media":{"player":"spotify","app":"Spotify","title":"Song","playing":true,"can_play_pause":true,"can_next":true,"can_previous":false},
             "power":{"active":"balanced","available":["power-saver","balanced"]}}""",
        ) as State
        assertEquals(VolumeState(100, true), state.mic)
        assertEquals(listOf(AppVolume(551, "Firefox", 80, false)), state.apps)
        assertEquals(Media("spotify", "Spotify", title = "Song", playing = true, canPlayPause = true, canNext = true), state.media)
        assertEquals(PowerProfiles("balanced", listOf("power-saver", "balanced")), state.power)

        assertEquals("""{"type":"media","action":"play_pause"}""", Codec.encode(MediaControl(MediaAction.PLAY_PAUSE)))
        assertEquals("""{"type":"set_app_volume","app":551,"muted":true}""", Codec.encode(SetAppVolume(551, muted = true)))
        assertEquals("""{"type":"set_mic","percent":40}""", Codec.encode(SetMic(percent = 40)))
        assertEquals("""{"type":"set_output","output":"bluez_output.x"}""", Codec.encode(SetOutput("bluez_output.x")))
        val outputs = Codec.decode("""{"type":"state","displays":[],"outputs":[{"id":"a","name":"Built-in","port":"Headphones","default":true},{"id":"b","name":"HDMI"}]}""") as State
        assertEquals(listOf(AudioOutput("a", "Built-in", "Headphones", true), AudioOutput("b", "HDMI")), outputs.outputs)
        assertEquals("""{"type":"set_power_profile","profile":"performance"}""", Codec.encode(SetPowerProfile("performance")))
        assertEquals("""{"type":"set_overlay","visible":false}""", Codec.encode(SetOverlay(false)))
        assertEquals(false, (Codec.decode("""{"type":"state","displays":[],"overlay":false}""") as State).overlay)
        assertEquals(null, (Codec.decode("""{"type":"state","displays":[]}""") as State).overlay)
        assertEquals("""{"type":"take_screenshot"}""", Codec.encode(TakeScreenshot))
        assertEquals("""{"type":"start_recording"}""", Codec.encode(StartRecording))
        assertEquals("""{"type":"run_shortcut","id":"steam"}""", Codec.encode(RunShortcut("steam")))
        val shortcuts = Codec.decode("""{"type":"state","displays":[],"shortcuts":[{"id":"steam","name":"Steam"}]}""") as State
        assertEquals(listOf(Shortcut("steam", "Steam")), shortcuts.shortcuts)
        assertEquals("""{"type":"system_action","action":"power_off"}""", Codec.encode(RunSystemAction(SystemAction.POWER_OFF)))
        assertEquals("""{"type":"stop_recording"}""", Codec.encode(StopRecording()))
        assertEquals("""{"type":"stop_recording","close_app":true}""", Codec.encode(StopRecording(closeApp = true)))
        assertEquals(RecordingSaved("/v/a.mkv"), Codec.decode("""{"type":"recording_saved","path":"/v/a.mkv"}"""))
        val recording = Codec.decode("""{"type":"state","displays":[],"recording":{"active":true,"duration_ms":42000}}""") as State
        assertEquals(Recording(active = true, durationMs = 42_000), recording.recording)
        assertEquals(Screenshot("/p/a.png"), Codec.decode("""{"type":"screenshot","path":"/p/a.png"}"""))
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

    @Test
    fun encodesRunCheckAndDecodesTheReport() {
        assertEquals("""{"type":"run_check"}""", Codec.encode(RunCheck))
        val report = Codec.decode(
            """{"type":"check_report","checks":[
              {"area":"Brightness","outcome":"ok","summary":"Built-in display at 75%, changed through logind"},
              {"area":"Screenshot","outcome":"warn","summary":"Off; silent screenshots need Spectacle","fix":"Install Spectacle."},
              {"area":"Future","outcome":"maybe","summary":"From a newer daemon","command":"sudo true"}
            ]}""",
        )
        assertEquals(
            CheckReport(
                listOf(
                    CheckResult("Brightness", CheckOutcome.OK, "Built-in display at 75%, changed through logind"),
                    CheckResult("Screenshot", CheckOutcome.WARN, "Off; silent screenshots need Spectacle", fix = "Install Spectacle."),
                    CheckResult("Future", CheckOutcome.WARN, "From a newer daemon", command = "sudo true"),
                ),
            ),
            report,
        )
    }

    @Test
    fun decodesMediaArt() {
        val state = Codec.decode(
            """{"type":"state","displays":[],"media":{"player":"spotify","app":"Spotify","title":"Song","art":"9f2c1a7e44b0d6e1","playing":true}}""",
        ) as State
        assertEquals("9f2c1a7e44b0d6e1", state.media?.art)
        assertEquals(
            MediaArt("9f2c1a7e44b0d6e1", "image/jpeg", "/9j/4AAQ"),
            Codec.decode("""{"type":"media_art","id":"9f2c1a7e44b0d6e1","mime":"image/jpeg","data":"/9j/4AAQ"}"""),
        )
    }

    @Test
    fun seeks() {
        val media = (
            Codec.decode(
                """{"type":"state","displays":[],"media":{"player":"spotify","app":"Spotify","length_ms":214000,"position_ms":61500,"can_seek":true}}""",
            ) as State
            ).media!!
        assertEquals(214000L, media.lengthMs)
        assertEquals(61500L, media.positionMs)
        assertEquals(true, media.canSeek)
        assertEquals("""{"type":"seek","position_ms":90000}""", Codec.encode(Seek(90000)))
    }

    @Test
    fun asksForNoStatsInTheBackground() {
        assertEquals("""{"type":"subscribe","groups":[],"interval_ms":60000}""", Codec.encode(Subscribe(emptyList(), 60000)))
        val bare = Codec.decode("""{"type":"stats","ts":1,"uptime":2}""") as Stats
        assertEquals(false, bare.hasMetrics)
        val full = Codec.decode("""{"type":"stats","ts":1,"uptime":2,"battery":{"percent":80}}""") as Stats
        assertEquals(true, full.hasMetrics)
    }

    @Test
    fun lendsThePhonesMicrophone() {
        assertEquals("""{"type":"start_phone_mic"}""", Codec.encode(StartPhoneMic))
        assertEquals("""{"type":"stop_phone_mic"}""", Codec.encode(StopPhoneMic))
        assertEquals(PhoneMic(PhoneMicState.ON), Codec.decode("""{"type":"phone_mic","state":"on"}"""))
        assertEquals(
            PhoneMic(PhoneMicState.OFF, "Another phone is the PC's microphone."),
            Codec.decode("""{"type":"phone_mic","state":"off","message":"Another phone is the PC's microphone."}"""),
        )
    }

    @Test
    fun mirrorsTheScreen() {
        assertEquals("""{"type":"start_screen"}""", Codec.encode(StartScreen()))
        assertEquals("""{"type":"start_screen","height":720}""", Codec.encode(StartScreen(height = 720)))
        assertEquals(
            """{"type":"start_screen","mode":"extend","width":2340,"height":954}""",
            Codec.encode(StartScreen(ScreenMode.EXTEND, 2340, 954)),
        )
        assertEquals("""{"type":"stop_screen"}""", Codec.encode(StopScreen))
        assertEquals(ScreenMirror(ScreenState.ASKING), Codec.decode("""{"type":"screen","state":"asking"}"""))
        assertEquals(
            ScreenMirror(ScreenState.ON, 1920, 1080),
            Codec.decode("""{"type":"screen","state":"on","width":1920,"height":1080}"""),
        )
        assertEquals(
            ScreenMirror(ScreenState.OFF, message = "sharing the screen was not allowed on the PC"),
            Codec.decode("""{"type":"screen","state":"off","message":"sharing the screen was not allowed on the PC"}"""),
        )

        // Kind 1, keyframe, time 3 600 000 033 µs, then the picture.
        val bytes = byteArrayOf(1, 1, 0, 0, 0, 0, 0xD6.toByte(), 0x93.toByte(), 0xA4.toByte(), 0x21, 0, 0, 0, 1, 9, 0x10)
        val frame = VideoFrame.fromBytes(bytes)!!
        assertEquals(true, frame.key)
        assertEquals(3_600_000_033L, frame.ptsUs)
        assertEquals(10, frame.offset)
        assertEquals(6, frame.size)
        assertNull(VideoFrame.fromBytes(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(VideoFrame.fromBytes(byteArrayOf(1, 0)))
    }

    @Test
    fun updatesThePc() {
        assertEquals("""{"type":"update_pc","version":"0.2.0"}""", Codec.encode(UpdatePc("0.2.0")))
        assertEquals(
            UpdateProgress(UpdateStage.DOWNLOADING, 42),
            Codec.decode("""{"type":"update_progress","stage":"downloading","percent":42}"""),
        )
        assertEquals(UpdateProgress(UpdateStage.RESTARTING), Codec.decode("""{"type":"update_progress","stage":"restarting"}"""))
        val welcome = Codec.decode(
            """{"type":"welcome","version":1,"hostname":"pc","os":"Linux","cpu_model":"x","cores":4,"sensors":[],"state":{"displays":[]},
               "daemon_version":"0.1.0","cannot_update":"owned by root"}""",
        ) as Welcome
        assertEquals("0.1.0", welcome.daemonVersion)
        assertEquals("owned by root", welcome.cannotUpdate)
        // A PC from before updates.
        val old = Codec.decode("""{"type":"welcome","version":1,"hostname":"pc","os":"Linux","cpu_model":"x","cores":4,"sensors":[],"state":{"displays":[]}}""") as Welcome
        assertEquals("", old.daemonVersion)
    }
}
