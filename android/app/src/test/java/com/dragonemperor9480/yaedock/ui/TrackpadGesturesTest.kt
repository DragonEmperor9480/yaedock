package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.MouseButton
import com.dragonemperor9480.yaedock.ui.TrackpadGestures.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadGesturesTest {
    // One pixel a dp, so distances read plainly.
    private val pad = TrackpadGestures(density = 1f)

    @Test
    fun aTapClicksOnceNoSecondTouchFollows() {
        pad.down(1, 100f, 100f, 0)
        assertEquals(emptyList<Action>(), pad.up(1, 80))
        // Held back for a moment, in case a drag or a double click follows.
        assertEquals(emptyList<Action>(), pad.tick(200))
        assertEquals(listOf(Action.Click(MouseButton.LEFT)), pad.tick(400))
        assertEquals(emptyList<Action>(), pad.tick(500))
    }

    @Test
    fun aLongPressWithoutMovingDoesNothing() {
        pad.down(1, 100f, 100f, 0)
        assertEquals(emptyList<Action>(), pad.up(1, 600))
        assertEquals(emptyList<Action>(), pad.tick(2000))
    }

    @Test
    fun oneFingerMovesThePointerPastASmallSlop() {
        pad.down(1, 100f, 100f, 0)
        assertEquals(emptyList<Action>(), pad.move(1, 103f, 100f, 16))
        val moved = pad.move(1, 113f, 100f, 32)
        val move = moved.single() as Action.Move
        assertTrue(move.dx > 0f)
        assertEquals(0f, move.dy, 0f)
        // A move is not a tap.
        assertEquals(emptyList<Action>(), pad.up(1, 100))
        assertEquals(emptyList<Action>(), pad.tick(1000))
    }

    @Test
    fun aFlickGoesFurtherThanASlowMove() {
        assertTrue(TrackpadGestures.gain(2f) > TrackpadGestures.gain(0.05f) * 2)
        assertEquals(TrackpadGestures.gain(0f), TrackpadGestures.gain(0.1f), 0f)
    }

    @Test
    fun aTapThenAPressThatMovesDrags() {
        pad.down(1, 100f, 100f, 0)
        pad.up(1, 80)
        pad.down(1, 100f, 100f, 200)
        val start = pad.move(1, 120f, 100f, 216)
        assertEquals(Action.Press(MouseButton.LEFT, true), start.first())
        assertTrue(start.last() is Action.Move)
        assertTrue(pad.dragging)
        assertEquals(listOf(Action.Press(MouseButton.LEFT, false)), pad.up(1, 600))
        assertEquals(emptyList<Action>(), pad.tick(2000))
    }

    @Test
    fun twoTapsDoubleClick() {
        pad.down(1, 100f, 100f, 0)
        pad.up(1, 80)
        pad.down(1, 100f, 100f, 200)
        assertEquals(listOf(Action.Click(MouseButton.LEFT), Action.Click(MouseButton.LEFT)), pad.up(1, 260))
    }

    @Test
    fun aTwoFingerTapRightClicks() {
        pad.down(1, 100f, 100f, 0)
        pad.down(2, 150f, 100f, 10)
        assertEquals(emptyList<Action>(), pad.up(1, 90))
        assertEquals(listOf(Action.Click(MouseButton.RIGHT)), pad.up(2, 100))
        assertEquals(emptyList<Action>(), pad.tick(1000))
    }

    @Test
    fun twoFingersMovingUpScrollDown() {
        pad.down(1, 100f, 300f, 0)
        pad.down(2, 150f, 300f, 0)
        val scrolls = (1..10).flatMap { step ->
            pad.move(1, 100f, 300f - step * 10, step * 16L) + pad.move(2, 150f, 300f - step * 10, step * 16L)
        }
        val down = scrolls.filterIsInstance<Action.Scroll>().sumOf { it.dy.toDouble() }
        // 100 dp up is two and a half notches down; the first few dp go to deciding it is a scroll.
        assertTrue(down in 2.0..2.5)
        assertTrue(scrolls.none { it is Action.Move })
        pad.up(1, 200)
        assertEquals(emptyList<Action>(), pad.up(2, 210))
    }

    @Test
    fun closingMidDragLetsGo() {
        pad.down(1, 100f, 100f, 0)
        pad.up(1, 80)
        pad.down(1, 100f, 100f, 200)
        pad.move(1, 130f, 100f, 216)
        assertEquals(listOf(Action.Press(MouseButton.LEFT, false)), pad.cancel())
        assertEquals(emptyList<Action>(), pad.cancel())
    }

    @Test
    fun movesAddUpToWholePixels() {
        val sum = PointerAccumulator()
        sum.addMove(0.6f, -0.4f)
        assertNull(sum.takeMove())
        sum.addMove(0.6f, -0.8f)
        assertEquals(1 to -1, sum.takeMove())
        // What was left over stays.
        sum.addMove(0.9f, 0f)
        assertEquals(1 to 0, sum.takeMove())
        sum.addScroll(0f, 0.004f)
        assertNull(sum.takeScroll())
        sum.addScroll(0f, 0.5f)
        assertEquals(0f to 0.5f, sum.takeScroll())
    }

    @Test
    fun findsWhatToEraseAndType() {
        assertEquals(0 to "o", textChange("hell", "hello"))
        assertEquals(1 to "", textChange("hello", "hell"))
        // A suggestion rewrites the word being typed.
        assertEquals(2 to "he ", textChange("teh", "the "))
        assertEquals(0 to "", textChange("same", "same"))
        // An emoji is erased as one character.
        assertEquals(1 to "", textChange("hi😀", "hi"))
    }
}
