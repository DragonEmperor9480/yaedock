package com.dragonemperor9480.yaedock.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.dragonemperor9480.yaedock.protocol.VideoFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the mirrored screen's H.264 onto [surface] with the phone's
 * hardware decoder, on its own thread. [feed] takes frames from the network
 * thread and never waits: when decoding falls behind, the waiting frames are
 * dropped and decoding starts over at the next keyframe.
 */
class ScreenDecoder(private val surface: Surface, private val width: Int, private val height: Int) {
    private val frames = ArrayBlockingQueue<VideoFrame>(QUEUE)

    /** Whether the next frame decoded has to be a keyframe. */
    @Volatile private var needKey = true

    @Volatile private var running = true
    private val thread = Thread(::decode, "screen-decoder").apply { start() }

    fun feed(frame: VideoFrame) {
        if (needKey && !frame.key) return
        if (frame.key) {
            // Everything before a keyframe is stale once it is here.
            frames.clear()
            needKey = false
        }
        if (!frames.offer(frame)) {
            frames.clear()
            needKey = true
        }
    }

    /** Stops decoding and lets go of the decoder; the surface is the caller's. */
    fun release() {
        running = false
        thread.interrupt()
        thread.join(1_000)
    }

    private fun decode() {
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Decode each picture as it comes, at full speed: without these a
            // hardware decoder may save power by working in batches.
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            // Pictures handed to the decoder and not shown yet. One the decoder
            // never shows keeps this above zero, which only costs short waits.
            var decoding = 0
            while (running) {
                // While a picture is being decoded, look for it again soon.
                val frame = frames.poll(if (decoding > 0) 2 else 100, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        buffer.put(frame.bytes, frame.offset, frame.size)
                        val flags = if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        codec.queueInputBuffer(index, 0, frame.size, frame.ptsUs, flags)
                        decoding++
                    } else {
                        // The decoder took nothing in time; start over at a keyframe.
                        needKey = true
                    }
                }
                // Show every finished picture at once: this is live, not played to a clock.
                // The first wait is for the picture just handed in.
                var wait = if (decoding > 0) OUTPUT_WAIT_US else 0
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, wait)
                    if (out < 0) break
                    codec.releaseOutputBuffer(out, true)
                    decoding = (decoding - 1).coerceAtLeast(0)
                    wait = 0
                }
            }
        } catch (_: InterruptedException) {
            // release() was called.
        } catch (e: Exception) {
            // A surface that went away, or a stream the decoder refuses.
            Log.w("ScreenDecoder", "decoding stopped", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private companion object {
        /** About a quarter of a second of frames. */
        const val QUEUE = 16
        const val INPUT_WAIT_US = 20_000L
        const val OUTPUT_WAIT_US = 4_000L
    }
}
