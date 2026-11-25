package com.dragonemperor9480.yaedock.net

import kotlin.math.abs

/**
 * Holds the PC's sound between the network and the phone's speaker, so the
 * speaker can play at an even pace while sound arrives in uneven bursts.
 *
 * Sound goes in with [push] whenever it arrives, and comes out with [pull]
 * in pieces of [CHUNK_FRAMES], as the speaker needs it. Each sound starts only
 * once [targetMs] of it is waiting, so the next hiccup on the link has that
 * long to pass before the speaker runs short.
 *
 * Nothing ever stops or starts with a hard cut, which is what a crackle is:
 * - When it runs dry, the last of the sound fades out and silence follows.
 * - When sound starts again, it fades in.
 * - When too much has piled up, as after a stall, it skips ahead with a fade.
 *
 * Running dry and then getting sound back soon after means the link stalled
 * mid-sound, so the wait grows ([STEP_UP_MS], up to [MOST_MS]). After
 * [CALM_MS] without that, it shrinks again ([STEP_DOWN_MS], down to [LEAST_MS]).
 * While more than the wait is gathered, near-silent pieces are dropped, which
 * can't be heard, so the delay comes back down even in one long sound.
 *
 * Times are milliseconds from any steady clock, given by the caller.
 */
class SpeakerJitterBuffer(private val rate: Int = 48_000) {
    private val chunk = rate / 100
    private val fade = rate * FADE_MS / 1000
    private val capacity = rate * CAPACITY_MS / 1000

    /** Both channels, left then right, from [head] for [size] frames. */
    private val ring = ShortArray(capacity * CHANNELS)
    private var head = 0
    private var size = 0

    /** Waiting for [targetMs] of sound before playing. */
    private var buffering = true

    /** The next piece played should fade in. */
    private var fadeIn = true

    /** When it last ran dry while playing, until sound comes again. */
    private var dryAt: Long? = null

    private var lastTrouble = 0L
    private var lastShrink = 0L

    /** How much sound is gathered before playing. */
    @Volatile
    var targetMs = START_MS
        private set

    /** The frames that one [pull] fills: 10 ms. */
    val chunkFrames: Int get() = chunk

    /** Frames waiting to be played. */
    @get:Synchronized
    val waiting: Int get() = size

    /** Adds sound: 16-bit little-endian samples, left then right, whole frames only. */
    @Synchronized
    fun push(bytes: ByteArray, offset: Int, length: Int, now: Long) {
        dryAt?.let { dry ->
            if (now - dry <= STALL_MS) grow(now)
        }
        dryAt = null
        val frames = length / (CHANNELS * 2)
        var at = offset
        repeat(frames) {
            if (size == capacity) {
                // Full: the oldest goes. Only a very long stall gets here.
                head = (head + 1) % capacity
                size--
            }
            val slot = ((head + size) % capacity) * CHANNELS
            for (c in 0 until CHANNELS) {
                ring[slot + c] = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()
                at += 2
            }
            size++
        }
    }

    /** Fills [out] with the next [chunkFrames] frames, both channels: sound, or silence while there is none. */
    @Synchronized
    fun pull(out: ShortArray, now: Long) {
        require(out.size >= chunk * CHANNELS)
        if (now - lastTrouble >= CALM_MS && now - lastShrink >= CALM_MS && targetMs > LEAST_MS) {
            targetMs = maxOf(LEAST_MS, targetMs - STEP_DOWN_MS)
            lastShrink = now
        }
        if (buffering) {
            if (size >= frames(targetMs)) {
                buffering = false
                fadeIn = true
            } else {
                out.fill(0, 0, chunk * CHANNELS)
                return
            }
        }
        if (size < chunk) {
            // Running dry: play what is left, fading out, then wait for more.
            val got = size
            take(out, got)
            fadeOut(out, got)
            out.fill(0, got * CHANNELS, chunk * CHANNELS)
            buffering = true
            dryAt = now
            return
        }
        take(out, chunk)
        // Behind by more than the wait needs: drop quiet pieces to catch up.
        while (fadeIn.not() && quiet(out) && size - frames(targetMs) >= chunk + frames(SLACK_MS)) {
            take(out, chunk)
        }
        if (fadeIn) {
            fadeIn(out, chunk)
            fadeIn = false
        }
        val excess = size - frames(targetMs + EXCESS_MS)
        if (excess > 0) {
            // Too far behind, as after a stall: skip back to the target.
            drop(size - frames(targetMs))
            fadeOut(out, chunk)
            fadeIn = true
        } else if (size < chunk) {
            // This was the last whole piece: fade it, rather than cut it, in
            // case nothing comes in time.
            fadeOut(out, chunk)
            fadeIn = true
        }
    }

    private fun grow(now: Long) {
        targetMs = minOf(MOST_MS, targetMs + STEP_UP_MS)
        lastTrouble = now
        lastShrink = now
    }

    private fun frames(ms: Int) = rate * ms / 1000

    private fun quiet(out: ShortArray): Boolean {
        for (i in 0 until chunk * CHANNELS) if (abs(out[i].toInt()) > QUIET) return false
        return true
    }

    private fun take(out: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            val slot = ((head + f) % capacity) * CHANNELS
            for (c in 0 until CHANNELS) out[f * CHANNELS + c] = ring[slot + c]
        }
        drop(frames)
    }

    private fun drop(frames: Int) {
        head = (head + frames) % capacity
        size -= frames
    }

    private fun fadeIn(out: ShortArray, frames: Int) {
        val n = minOf(fade, frames)
        for (f in 0 until n) scale(out, f, f.toFloat() / n)
    }

    private fun fadeOut(out: ShortArray, frames: Int) {
        val n = minOf(fade, frames)
        for (i in 0 until n) scale(out, frames - n + i, (n - 1 - i).toFloat() / n)
    }

    private fun scale(out: ShortArray, frame: Int, gain: Float) {
        for (c in 0 until CHANNELS) {
            val i = frame * CHANNELS + c
            out[i] = (out[i] * gain).toInt().toShort()
        }
    }

    companion object {
        const val CHANNELS = 2

        /** The wait for a new sound to gather at first. */
        const val START_MS = 60

        /** The least and most the wait can become. */
        const val LEAST_MS = 40
        const val MOST_MS = 300

        /** How much the wait grows after a stall, and shrinks after a calm spell. */
        const val STEP_UP_MS = 40
        const val STEP_DOWN_MS = 10

        /** A calm spell: this long without a stall. */
        const val CALM_MS = 20_000L

        /** Sound back within this long of running dry means the link stalled mid-sound. */
        const val STALL_MS = 600L

        /** How far past the wait it may fall behind before skipping ahead. */
        const val EXCESS_MS = 150

        /** How far past the wait it may fall behind before quiet pieces are dropped. */
        const val SLACK_MS = 20

        /** The loudest a sample can be in a piece that counts as quiet: about -46 dBFS. */
        const val QUIET = 160

        /** The length of each fade. */
        const val FADE_MS = 5

        /** The most it holds: a second. */
        const val CAPACITY_MS = 1000
    }
}
