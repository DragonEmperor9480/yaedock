package com.dragonemperor9480.yaedock.net

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * Records this phone's microphone for the PC, on its own thread: one channel
 * at 48 000 samples a second, 16 bits each, handed to [send] 20 ms at a time
 * behind the byte that says what the frame is. With [opus], each piece goes
 * through the phone's Opus encoder first, which leaves about a twentieth of
 * it; a phone without that encoder sends the sound as it is. The app must
 * hold `RECORD_AUDIO` before one is made.
 */
class PhoneMicRecorder(private val opus: Boolean, private val send: (ByteArray) -> Unit) {
    @Volatile private var running = true
    private val thread = Thread(::record, "phone-mic").apply { start() }

    /** Stops recording and lets go of the microphone. */
    fun stop() {
        running = false
        thread.join(1_000)
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var recorder: AudioRecord? = null
        val encoder = if (opus) opusEncoder() else null
        try {
            val least = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(least, PIECE * 4),
            )
            recorder.startRecording()
            while (running) {
                val frame = ByteArray(1 + PIECE)
                frame[0] = KIND
                var filled = 0
                while (running && filled < PIECE) {
                    val read = recorder.read(frame, 1 + filled, PIECE - filled)
                    if (read <= 0) return
                    filled += read
                }
                when {
                    filled != PIECE -> {}
                    encoder == null -> send(frame)
                    else -> encode(encoder, frame)
                }
            }
        } catch (e: Exception) {
            // No microphone, or another app holds it in a way that shuts this one out.
            Log.w("PhoneMicRecorder", "recording stopped", e)
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }

    /** The phone's Opus encoder, started, or null when it has none (before Android 10) or it will not start. */
    private fun opusEncoder(): MediaCodec? = runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PIECE)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }.onFailure { Log.w("PhoneMicRecorder", "no Opus encoder; sending the sound as it is", it) }.getOrNull()

    /** Gives the encoder the sound in [frame] (behind its first byte) and sends the packets it has ready. */
    private fun encode(encoder: MediaCodec, frame: ByteArray) {
        val input = encoder.dequeueInputBuffer(INPUT_WAIT_US)
        // An encoder with no room is behind: this piece is dropped rather than sent late.
        if (input >= 0) {
            encoder.getInputBuffer(input)?.apply {
                clear()
                put(frame, 1, PIECE)
            }
            encoder.queueInputBuffer(input, 0, PIECE, System.nanoTime() / 1_000, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val output = encoder.dequeueOutputBuffer(info, 0)
            if (output < 0) break
            // The encoder's headers are for files; the PC's decoder needs none.
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val packet = ByteArray(1 + info.size)
                packet[0] = OPUS_KIND
                encoder.getOutputBuffer(output)?.apply {
                    position(info.offset)
                    get(packet, 1, info.size)
                }
                send(packet)
            }
            encoder.releaseOutputBuffer(output, false)
        }
    }

    companion object {
        /** The first byte of a frame of the phone microphone's sound. */
        const val KIND: Byte = 2

        /** The first byte of a frame holding the sound as one Opus packet. */
        const val OPUS_KIND: Byte = 4

        /** Plenty for speech, and a twenty-fourth of the sound as it is. */
        private const val OPUS_BIT_RATE = 32_000
        private const val INPUT_WAIT_US = 10_000L
        const val RATE = 48_000

        /** 20 ms of sound, in bytes. */
        const val PIECE = RATE / 50 * 2
    }
}
