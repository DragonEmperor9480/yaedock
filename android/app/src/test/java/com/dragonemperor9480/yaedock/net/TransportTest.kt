package com.dragonemperor9480.yaedock.net

import com.dragonemperor9480.yaedock.protocol.Hello
import com.dragonemperor9480.yaedock.protocol.SetVolume
import com.dragonemperor9480.yaedock.protocol.VideoFrame
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DaemonConnection] over a made-up transport: it relies on nothing but [Transport]. */
class TransportTest {
    /** A link whose far end is the test. */
    class FakeLink(val events: LinkEvents) : Link {
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteArray>()
        var waiting = 0L
        @Volatile var cancelled = false

        override fun sendText(text: String) = texts.add(text)
        override fun sendBinary(bytes: ByteArray) = binaries.add(bytes)
        override fun queued() = waiting
        override fun close() {
            cancelled = true
        }
        override fun cancel() {
            cancelled = true
        }
    }

    class FakeTransport : Transport {
        val links = CopyOnWriteArrayList<FakeLink>()
        override fun open(events: LinkEvents) = FakeLink(events).also { links += it }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = FakeTransport()
    private val connection = DaemonConnection(
        Target("pc", 9481, "00"),
        scope,
        hello = { Hello(deviceId = "phone", deviceName = "Test phone", token = "t") },
        onPaired = { _, _ -> },
        transport = transport,
    )

    @After
    fun tearDown() {
        connection.stop()
        scope.cancel()
    }

    private suspend fun until(what: String, check: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!check()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("waited for $what", e)
        }
    }

    private suspend fun connected(): FakeLink {
        connection.start()
        until("a link") { transport.links.isNotEmpty() }
        val link = transport.links.last()
        link.events.onOpen()
        until("hello") { link.texts.any { "\"hello\"" in it } }
        link.events.onText(WELCOME)
        connection.status.first { it is DaemonConnection.Status.Connected }
        return link
    }

    @Test
    fun saysHelloAndCarriesMessages() = runBlocking {
        val link = connected()
        assertTrue(connection.send(SetVolume(percent = 40)))
        until("set_volume") { link.texts.any { "\"set_volume\"" in it } }
        link.events.onText("""{"type":"state","volume":{"percent":40,"muted":false}}""")
        until("the new state") { connection.state.value?.volume?.percent == 40 }
    }

    @Test
    fun handsScreenVideoToItsSink() = runBlocking {
        val link = connected()
        val pictures = CopyOnWriteArrayList<VideoFrame>()
        connection.videoSink = { pictures += it }
        // Kind 1, keyframe, time 7, then the picture.
        link.events.onBinary(byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0, 7, 9, 9))
        until("a picture") { pictures.isNotEmpty() }
        assertEquals(7L, pictures[0].ptsUs)
    }

    @Test
    fun lendsTheMicrophoneAgainOnANewLink() = runBlocking {
        val first = connected()
        connection.setPhoneMic(true)
        until("start_phone_mic") { first.texts.any { "start_phone_mic" in it } }
        first.events.onClosed("gone")
        until("the old link dropped") { first.cancelled }
        connection.status.first { it is DaemonConnection.Status.Waiting }
        connection.retryNow()
        until("a second link") { transport.links.size == 2 }
        val second = transport.links[1]
        second.events.onOpen()
        second.events.onText(WELCOME)
        until("start_phone_mic again") { second.texts.any { "start_phone_mic" in it } }
    }

    @Test
    fun stopsTryingWhenThePcRefuses() = runBlocking {
        connection.start()
        until("a link") { transport.links.isNotEmpty() }
        val link = transport.links[0]
        link.events.onOpen()
        link.events.onText("""{"type":"error","code":"unauthorized","message":"not paired"}""")
        connection.status.first { it is DaemonConnection.Status.Rejected }
        delay(1_500)
        assertEquals(1, transport.links.size)
    }

    private companion object {
        const val WELCOME = """{"type":"welcome","version":1,"hostname":"pc","os":"Linux","cpu_model":"x","cores":4,"sensors":[],"state":{},"phone_mic":true}"""
    }
}
