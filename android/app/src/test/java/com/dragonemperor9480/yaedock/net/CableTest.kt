package com.dragonemperor9480.yaedock.net

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The USB cable's frames, and a link over a pair of streams, as the cable is. */
class CableTest {
    @Test
    fun theHeaderIsTheKindThenTheLength() {
        // The same bytes as yaedock-proto's link module.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 2, 9, 9), LinkFrames.encode(LinkFrames.BINARY, byteArrayOf(9, 9)))
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0), LinkFrames.encode(LinkFrames.START, ByteArray(0)))
    }

    @Test
    fun framesMayArriveInAnyPieces() {
        val bytes = LinkFrames.encode(LinkFrames.TEXT, "héllo".encodeToByteArray()) +
            LinkFrames.encode(LinkFrames.BINARY, ByteArray(70_000) { it.toByte() })
        val splitter = LinkFrames.Splitter()
        val frames = mutableListOf<LinkFrames.Frame>()
        for (piece in bytes.toList().chunked(1000)) {
            splitter.push(piece.toByteArray())
            while (true) frames += splitter.next() ?: break
        }
        assertEquals(LinkFrames.Frame.Text("héllo"), frames[0])
        assertEquals(70_000, (frames[1] as LinkFrames.Frame.Binary).bytes.size)
        assertEquals(69_999.toByte(), (frames[1] as LinkFrames.Frame.Binary).bytes.last())
        assertEquals(2, frames.size)
        assertNull(splitter.next())
    }

    @Test(expected = IOException::class)
    fun aStreamOutOfStepIsRefused() {
        LinkFrames.Splitter().apply { push(byteArrayOf(7, 0, 0, 0, 0)) }.next()
    }

    /** One end of a cable: what the PC sends goes into [toPhone], what the phone sends comes out of [fromPhone]. */
    private class Cable {
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 20)
        val phoneOut = PipedOutputStream()
        val fromPhone = PipedInputStream(phoneOut, 1 shl 20)
    }

    private class Heard : LinkEvents {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteArray>()
        override fun onOpen() = opened.countDown()
        override fun onText(text: String) {
            texts += text
        }
        override fun onBinary(bytes: ByteArray) {
            binaries += bytes
        }
        override fun onClosed(reason: String) = closed.countDown()
    }

    private fun readFrame(input: PipedInputStream): LinkFrames.Frame {
        val splitter = LinkFrames.Splitter()
        while (true) {
            splitter.next()?.let { return it }
            val byte = input.read()
            check(byte >= 0)
            splitter.push(byteArrayOf(byte.toByte()))
        }
    }

    @Test
    fun aStreamLinkStartsThenCarriesBothWays() {
        val cable = Cable()
        val heard = Heard()
        val link = StreamLink(cable.phoneIn, cable.phoneOut, heard, Via.CABLE) { cable.phoneIn.close() }
        assertTrue(heard.opened.await(2, TimeUnit.SECONDS))
        assertEquals(Via.CABLE, link.via)
        // The PC sees the start first, then what the phone sends.
        assertEquals(LinkFrames.Frame.Start, readFrame(cable.fromPhone))
        assertTrue(link.sendText("""{"type":"ping"}"""))
        assertEquals(LinkFrames.Frame.Text("""{"type":"ping"}"""), readFrame(cable.fromPhone))
        // And the phone hears what the PC sends.
        cable.toPhone.write(LinkFrames.encode(LinkFrames.TEXT, "hi".encodeToByteArray()))
        cable.toPhone.write(LinkFrames.encode(LinkFrames.BINARY, byteArrayOf(1, 2)))
        cable.toPhone.flush()
        val until = System.currentTimeMillis() + 2_000
        while (heard.binaries.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(listOf("hi"), heard.texts)
        assertArrayEquals(byteArrayOf(1, 2), heard.binaries.single())
        // The PC hanging up ends the link.
        cable.toPhone.close()
        assertTrue(heard.closed.await(2, TimeUnit.SECONDS))
        assertTrue(!link.sendText("late"))
    }

    @Test
    fun choosesTheCableWhenThereIsOne() {
        val wifi = TransportTest.FakeTransport()
        val cable = TransportTest.FakeTransport()
        var plugged = false
        val choosing = ChoosingTransport(wifi) { cable.takeIf { plugged } }
        val events = Heard()
        choosing.open(events)
        plugged = true
        choosing.open(events)
        assertEquals(1, wifi.links.size)
        assertEquals(1, cable.links.size)
    }
}
