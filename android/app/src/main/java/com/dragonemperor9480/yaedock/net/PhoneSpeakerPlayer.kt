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
 *
 * The PC sends nothing while it is quiet, so each sound after a pause finds
 * the player empty. Played straight away, the next piece running a little
 * late would leave a gap mid-sound, heard as a crackle; so a player that has
 * run dry is first given [CUSHION_MS] of silence to play while sound comes.
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

    /** Samples of both channels handed to the player so far. */
    private var written = 0L

    init {
        track.play()
    }

    /** Plays the sound in [bytes] from [offset]: whole samples for both channels. */
    @Synchronized
    fun play(bytes: ByteArray, offset: Int) {
        if (stopped) return
        val size = (bytes.size - offset).let { it - it % FRAME }
        if (size <= 0) return
        // The head counts played samples in 32 bits, unsigned.
        val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        if (written - played < LOW_FRAMES) {
            written = played
            write(CUSHION, 0, CUSHION.size)
        }
        write(bytes, offset, size)
    }

    private fun write(bytes: ByteArray, offset: Int, size: Int) {
        val took = track.write(bytes, offset, size, AudioTrack.WRITE_NON_BLOCKING)
        if (took < 0) Log.w(TAG, "the player refused sound: $took") else written += took / FRAME
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
        const val MOST_WAITING_MS = 200

        /** Silence played first when the player has run dry. */
        const val CUSHION_MS = 60
        val CUSHION = ByteArray(BYTES_PER_MS * CUSHION_MS)

        /** Below this many samples waiting, the player counts as dry: 5 ms. */
        const val LOW_FRAMES = PcSound.RATE / 200
    }
}
