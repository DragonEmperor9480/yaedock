package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.MouseButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Turns touches on the trackpad into what the PC's mouse should do, as a
 * laptop trackpad does: one finger moves the pointer, a tap clicks, two
 * fingers scroll, a two-finger tap right-clicks, and a tap then a press that
 * moves drags. Plain logic fed with times in milliseconds and positions in
 * pixels, so it can be tested without a screen.
 */
class TrackpadGestures(private val density: Float) {
    sealed interface Action {
        /** Moves the pointer by PC pixels; fractions add up. */
        data class Move(val dx: Float, val dy: Float) : Action

        /** Scrolls by notches, down and right. */
        data class Scroll(val dx: Float, val dy: Float) : Action

        /** A press and a release. */
        data class Click(val button: MouseButton) : Action

        /** A press or a release on its own, for a drag. */
        data class Press(val button: MouseButton, val down: Boolean) : Action
    }

    private enum class Mode { UNDECIDED, MOVE, DRAG, SCROLL }

    private val fingers = mutableMapOf<Long, Pair<Float, Float>>()
    private var mode = Mode.UNDECIDED
    private var started = 0L
    private var lastMove = 0L
    private var travel = 0f
    private var mostFingers = 0

    /** When a tap's click goes, unless a second touch turns it into a drag or a double click. */
    private var clickAt: Long? = null

    /** This touch came just after a tap: moving it drags, lifting it double-clicks. */
    private var afterTap = false

    /** Whether the left button is held for a drag, so it can be let go if the page closes. */
    val dragging get() = mode == Mode.DRAG

    fun down(id: Long, x: Float, y: Float, time: Long): List<Action> {
        if (fingers.isEmpty()) {
            started = time
            lastMove = time
            travel = 0f
            mostFingers = 0
            mode = Mode.UNDECIDED
            afterTap = clickAt?.let { time <= it } == true
            clickAt = null
        }
        fingers[id] = x to y
        mostFingers = maxOf(mostFingers, fingers.size)
        // A second finger on a moving pointer turns the move into a scroll.
        if (fingers.size == 2 && mode == Mode.MOVE) mode = Mode.SCROLL
        return emptyList()
    }

    fun move(id: Long, x: Float, y: Float, time: Long): List<Action> {
        val (lastX, lastY) = fingers[id] ?: return emptyList()
        fingers[id] = x to y
        val dx = (x - lastX) / density
        val dy = (y - lastY) / density
        if (fingers.size >= 2 || mode == Mode.SCROLL) {
            if (mode == Mode.DRAG) return emptyList()
            // Each finger's move counts for its share, which adds up to the fingers' middle.
            val share = fingers.size.coerceAtLeast(1)
            travel += hypot(dx, dy) / share
            if (mode == Mode.UNDECIDED && travel > SLOP_DP) mode = Mode.SCROLL
            if (mode != Mode.SCROLL || fingers.size < 2) return emptyList()
            // Content follows the fingers: moving them up scrolls down.
            return listOf(Action.Scroll(-dx / share / NOTCH_DP, -dy / share / NOTCH_DP))
        }
        travel += hypot(dx, dy)
        val actions = mutableListOf<Action>()
        if (mode == Mode.UNDECIDED) {
            if (travel <= SLOP_DP) return emptyList()
            mode = if (afterTap) Mode.DRAG else Mode.MOVE
            if (mode == Mode.DRAG) actions += Action.Press(MouseButton.LEFT, true)
        }
        val elapsed = (time - lastMove).coerceAtLeast(1)
        lastMove = time
        val gain = gain(hypot(dx, dy) / elapsed)
        actions += Action.Move(dx * gain, dy * gain)
        return actions
    }

    fun up(id: Long, time: Long): List<Action> {
        if (fingers.remove(id) == null || fingers.isNotEmpty()) return emptyList()
        val quick = time - started < TAP_MS
        return when {
            mode == Mode.DRAG -> listOf(Action.Press(MouseButton.LEFT, false))
            mode != Mode.UNDECIDED || !quick -> emptyList()
            mostFingers >= 2 -> listOf(Action.Click(MouseButton.RIGHT))
            afterTap -> listOf(Action.Click(MouseButton.LEFT), Action.Click(MouseButton.LEFT))
            else -> {
                clickAt = time + DOUBLE_TAP_MS
                emptyList()
            }
        }.also { if (fingers.isEmpty()) mode = Mode.UNDECIDED }
    }

    /** Sends a tap's click once no second touch came for it. */
    fun tick(time: Long): List<Action> {
        val at = clickAt ?: return emptyList()
        if (time < at || fingers.isNotEmpty()) return emptyList()
        clickAt = null
        return listOf(Action.Click(MouseButton.LEFT))
    }

    /** Lets go of the touch, as when the page closes mid-drag; returns what to release. */
    fun cancel(): List<Action> {
        val release = if (mode == Mode.DRAG) listOf(Action.Press(MouseButton.LEFT, false)) else emptyList()
        fingers.clear()
        mode = Mode.UNDECIDED
        clickAt = null
        return release
    }

    companion object {
        /** How far a finger moves before a touch stops being a tap, in dp. */
        const val SLOP_DP = 6f

        /** The longest touch that still counts as a tap. */
        const val TAP_MS = 250L

        /** How long after a tap a second touch makes a drag or a double click. */
        const val DOUBLE_TAP_MS = 250L

        /** How far two fingers move to scroll one notch, in dp. */
        const val NOTCH_DP = 40f

        /**
         * PC pixels per dp moved: steady at slow speeds, for aiming, and up to
         * three times that when flicked, to cross the screen. [speed] is in dp a millisecond.
         */
        fun gain(speed: Float): Float {
            val fast = ((speed - 0.15f) / 1.2f).coerceIn(0f, 1f)
            return 1.4f * (1f + 2f * fast)
        }
    }
}

/**
 * Adds up pointer moves and scrolls between frames, and gives back what to
 * send: whole pixels, keeping the fractions for the next frame so slow moves
 * still arrive.
 */
class PointerAccumulator {
    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    fun addMove(dx: Float, dy: Float) {
        moveX += dx
        moveY += dy
    }

    fun addScroll(dx: Float, dy: Float) {
        scrollX += dx
        scrollY += dy
    }

    /** Whole pixels to move now, or null when there are none. */
    fun takeMove(): Pair<Int, Int>? {
        val x = moveX.toInt()
        val y = moveY.toInt()
        if (x == 0 && y == 0) return null
        moveX -= x
        moveY -= y
        return x to y
    }

    /** Notches to scroll now, in steps of a hundredth, or null when too little has built up. */
    fun takeScroll(): Pair<Float, Float>? {
        val x = (scrollX * 100).roundToInt() / 100f
        val y = (scrollY * 100).roundToInt() / 100f
        if (abs(x) < 0.01f && abs(y) < 0.01f) return null
        scrollX -= x
        scrollY -= y
        return x to y
    }
}

/**
 * What changed between two versions of the typed text: how many characters to
 * erase from the end, then what to type. Keyboards that suggest words rewrite
 * the word being typed, so the change may both erase and type.
 */
fun textChange(old: String, new: String): Pair<Int, String> {
    var same = 0
    val shortest = minOf(old.length, new.length)
    while (same < shortest && old[same] == new[same]) same++
    // Never split a pair of surrogates, such as an emoji.
    if (same > 0 && same < old.length && Character.isLowSurrogate(old[same])) same--
    return old.codePointCount(same, old.length) to new.substring(same)
}
