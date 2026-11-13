package com.dragonemperor9480.yaedock.net

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * Records this phone's microphone for the PC, on its own thread: one channel
 * at 48 000 samples a second, 16 bits each, handed to [send] 20 ms at a time
 * behind the byte that says what the frame is. The app must hold
 * `RECORD_AUDIO` before one is made.
 */
class PhoneMicRecorder(private val send: (ByteArray) -> Unit) {
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
                if (filled == PIECE) send(frame)
            }
        } catch (e: Exception) {
            // No microphone, or another app holds it in a way that shuts this one out.
            Log.w("PhoneMicRecorder", "recording stopped", e)
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    companion object {
        /** The first byte of a frame of the phone microphone's sound. */
        const val KIND: Byte = 2
        const val RATE = 48_000

        /** 20 ms of sound, in bytes. */
        const val PIECE = RATE / 50 * 2
    }
}
