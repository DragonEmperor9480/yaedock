package com.dragonemperor9480.yaedock.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Frames on a link that is a plain byte stream, such as the USB cable: a kind
 * byte, a big-endian length and the payload. See "Over a USB cable" in
 * protocol.md; `yaedock-proto`'s `link` module is the PC's side.
 */
object LinkFrames {
    const val TEXT = 0
    const val BINARY = 1
    const val START = 2
    const val HEADER = 5
    /** The largest frame either side accepts. */
    const val MOST = 16 * 1024 * 1024

    sealed interface Frame {
        data class Text(val text: String) : Frame
        class Binary(val bytes: ByteArray) : Frame
        data object Start : Frame
    }

    fun encode(kind: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArray(HEADER + payload.size)
        bytes[0] = kind.toByte()
        for (i in 0 until 4) bytes[1 + i] = (payload.size ushr (24 - 8 * i)).toByte()
        payload.copyInto(bytes, HEADER)
        return bytes
    }

    /** Collects bytes as they arrive, in pieces of any size, and hands back whole frames. */
    class Splitter {
        private var buffer = ByteArray(64 * 1024)
        private var start = 0
        private var end = 0

        fun push(bytes: ByteArray, length: Int = bytes.size) {
            if (end + length > buffer.size) {
                // Move what is left to the front, and grow if that is not enough.
                val waiting = end - start
                val bigger = if (waiting + length > buffer.size) buffer.copyOf(maxOf(buffer.size * 2, waiting + length)) else buffer
                buffer.copyInto(bigger, 0, start, end)
                buffer = bigger
                start = 0
                end = waiting
            }
            bytes.copyInto(buffer, end, 0, length)
            end += length
        }

        /** The next whole frame, or null until one has arrived. Throws when the stream is out of step. */
        fun next(): Frame? {
            if (end - start < HEADER) return null
            val kind = buffer[start].toInt()
            if (kind !in TEXT..START) throw IOException("a frame of unknown kind $kind")
            var length = 0L
            for (i in 1..4) length = (length shl 8) or (buffer[start + i].toLong() and 0xff)
            if (length > MOST) throw IOException("a frame of $length bytes")
            if (end - start < HEADER + length) return null
            val payload = buffer.copyOfRange(start + HEADER, start + HEADER + length.toInt())
            start += HEADER + length.toInt()
            if (start == end) {
                start = 0
                end = 0
            }
            return when (kind) {
                TEXT -> Frame.Text(payload.decodeToString(throwOnInvalidSequence = true))
                BINARY -> Frame.Binary(payload)
                else -> Frame.Start
            }
        }
    }
}

/**
 * A [Link] over a pair of byte streams, carrying [LinkFrames]: the USB
 * cable's. It sends a start frame first, so the PC begins a new session.
 * One thread reads and one writes; [queued] counts what waits for the
 * writer, so slow links drop pictures the same as on Wi-Fi.
 */
class StreamLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val events: LinkEvents,
    override val via: Via,
    /** Closes the streams' source; it must make a blocked read or write end. */
    private val closer: () -> Unit,
) : Link {
    private val waiting = LinkedBlockingQueue<ByteArray>()
    private val waitingBytes = AtomicLong()
    private val over = AtomicBoolean(false)

    init {
        enqueue(LinkFrames.encode(LinkFrames.START, ByteArray(0)))
        Thread(::write, "yaedock-link-write").apply { isDaemon = true }.start()
        Thread(::read, "yaedock-link-read").apply { isDaemon = true }.start()
    }

    private fun read() {
        events.onOpen()
        val splitter = LinkFrames.Splitter()
        // The cable hands over its data in pieces of up to this much; a smaller buffer loses some.
        val buffer = ByteArray(READ_SIZE)
        try {
            while (!over.get()) {
                val read = input.read(buffer)
                if (read < 0) return end("the PC closed the cable link")
                splitter.push(buffer, read)
                while (true) {
                    when (val frame = splitter.next() ?: break) {
                        is LinkFrames.Frame.Text -> events.onText(frame.text)
                        is LinkFrames.Frame.Binary -> events.onBinary(frame.bytes)
                        // Only the phone starts a link.
                        LinkFrames.Frame.Start -> {}
                    }
                }
            }
        } catch (e: IOException) {
            end(e.message ?: "the cable link failed")
        }
    }

    private fun write() {
        try {
            while (!over.get()) {
                val bytes = waiting.poll(1, TimeUnit.SECONDS) ?: continue
                output.write(bytes)
                output.flush()
                waitingBytes.addAndGet(-bytes.size.toLong())
            }
        } catch (e: IOException) {
            end(e.message ?: "the cable link failed")
        } catch (_: InterruptedException) {}
    }

    private fun end(reason: String) {
        if (!over.compareAndSet(false, true)) return
        waiting.clear()
        runCatching(closer)
        events.onClosed(reason)
    }

    private fun enqueue(bytes: ByteArray): Boolean {
        if (over.get()) return false
        waitingBytes.addAndGet(bytes.size.toLong())
        waiting.put(bytes)
        return true
    }

    override fun sendText(text: String) = enqueue(LinkFrames.encode(LinkFrames.TEXT, text.encodeToByteArray()))
    override fun sendBinary(bytes: ByteArray) = enqueue(LinkFrames.encode(LinkFrames.BINARY, bytes))
    override fun queued() = waitingBytes.get()
    override fun close() = end("closed by this phone")
    override fun cancel() = end("closed by this phone")

    companion object {
        const val READ_SIZE = 16 * 1024
    }
}
