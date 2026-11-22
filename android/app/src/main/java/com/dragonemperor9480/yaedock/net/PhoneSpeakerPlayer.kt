package com.dragonemperor9480.yaedock.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.dragonemperor9480.yaedock.protocol.PcSound

/**
 * Plays the PC's sound on this phone, as one of the PC's speakers: two
 * channels at 48 000 samples a second, 16 bits each, as the PC sends them.
 *
 * Writing never waits. What does not fit in the player's buffer, about
 * [MOST_WAITING_MS] of sound, is dropped, so a stall on the link leaves no
 * lasting delay: the sound comes back at once, not late.
 */
class PhoneSpeakerPlayer {
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
            .setBufferSizeInBytes(maxOf(least, BYTES_PER_MS * MOST_WAITING_MS))
            .build()
    }

    @Volatile private var stopped = false

    init {
        track.play()
    }

    /** Plays the sound in [bytes] from [offset]: whole samples for both channels. */
    @Synchronized
    fun play(bytes: ByteArray, offset: Int) {
        if (stopped) return
        val size = (bytes.size - offset).let { it - it % FRAME }
        if (size <= 0) return
        val written = track.write(bytes, offset, size, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) Log.w(TAG, "the player refused sound: $written")
    }

    /** Stops playing and lets go of the player. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
    }

    private companion object {
        const val TAG = "PhoneSpeaker"

        /** Bytes for one sample of both channels. */
        const val FRAME = 4
        const val BYTES_PER_MS = PcSound.RATE / 1000 * FRAME

        /** The most sound kept waiting to be played. */
        const val MOST_WAITING_MS = 150
    }
}
