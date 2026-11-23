package com.dragonemperor9480.yaedock.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.dragonemperor9480.yaedock.protocol.PcSound

/**
 * Plays the PC's sound on this phone, as one of the PC's speakers: two
 * channels at 48 000 samples a second, 16 bits each, as the PC sends them.
 *
 * Sound from the link goes into a [SpeakerJitterBuffer], and a thread of its
 * own feeds the speaker from it at the speaker's own pace, with silence when
 * there is no sound. So the speaker never runs short in the middle of a piece
 * and never gets more than it can hold: the buffer takes up the link's
 * unevenness, and fades over any gap rather than cutting.
 */
class PhoneSpeakerPlayer {
    private val buffer = SpeakerJitterBuffer(PcSound.RATE)

    private val track: AudioTrack = run {
        val format = AudioFormat.Builder()
            .setSampleRate(PcSound.RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val least = AudioTrack.getMinBufferSize(PcSound.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(least, BYTES_PER_MS * TRACK_MS))
            .build()
            .also {
                // Use only a little of it: the jitter buffer does the waiting,
                // and every millisecond here is heard as delay.
                it.bufferSizeInFrames = PcSound.RATE / 1000 * TRACK_MS
            }
    }

    @Volatile private var stopped = false

    private val feeder = Thread({ feed() }, "phone-speaker").apply { start() }

    /** Takes the sound in [bytes] from [offset]: whole samples for both channels. */
    fun play(bytes: ByteArray, offset: Int) {
        if (stopped) return
        val size = (bytes.size - offset).let { it - it % FRAME }
        if (size > 0) buffer.push(bytes, offset, size, SystemClock.elapsedRealtime())
    }

    /** Stops playing and lets go of the player. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { feeder.join(500) }
        track.release()
    }

    private fun feed() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val piece = ShortArray(buffer.chunkFrames * SpeakerJitterBuffer.CHANNELS)
        runCatching { track.play() }.onFailure {
            Log.w(TAG, "the player would not start", it)
            return
        }
        while (!stopped) {
            buffer.pull(piece, SystemClock.elapsedRealtime())
            // Waits while the speaker is full, which paces this loop.
            val written = track.write(piece, 0, piece.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                if (!stopped) Log.w(TAG, "the player refused sound: $written")
                break
            }
        }
    }

    private companion object {
        const val TAG = "PhoneSpeaker"

        /** Bytes for one sample of both channels. */
        const val FRAME = 4
        const val BYTES_PER_MS = PcSound.RATE / 1000 * FRAME

        /** How much the speaker itself holds: three of the pieces it is fed. */
        const val TRACK_MS = 30
    }
}
