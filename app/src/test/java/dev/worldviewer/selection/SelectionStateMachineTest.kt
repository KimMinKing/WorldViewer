package dev.worldviewer.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectionStateMachineTest {
    @Test
    fun `hold confirms one Chinese character and emits one haptic`() {
        val machine = SelectionStateMachine("大家好", 350)

        machine.press(offsetUtf16 = 1, eventTimeMillis = 1_000)
        assertTrue(machine.advanceTime(1_349).effects.isEmpty())
        val confirmed = machine.advanceTime(1_350)
        val repeated = machine.advanceTime(1_500)

        assertEquals(SelectionState.Confirmed(SelectionRange(1, 2), SelectionRange(1, 2)), confirmed.state)
        assertEquals(setOf(SelectionEffect.CONFIRM_HAPTIC), confirmed.effects)
        assertTrue(repeated.effects.isEmpty())
        assertEquals(SelectionState.Completed(SelectionRange(1, 2)), machine.release().state)
    }

    @Test
    fun `movement beyond slop before confirmation yields to scrolling`() {
        val machine = SelectionStateMachine("大家好", 350)
        machine.press(offsetUtf16 = 0, eventTimeMillis = 0)

        val transition = machine.move(offsetUtf16 = 2, exceededTouchSlop = true)

        assertEquals(SelectionState.Idle, transition.state)
        assertEquals(setOf(SelectionEffect.YIELD_TO_SCROLL), transition.effects)
        assertTrue(machine.advanceTime(500).effects.isEmpty())
    }

    @Test
    fun `drag expands in either direction and buttons can shrink the range`() {
        val machine = SelectionStateMachine("大家好", 350)
        machine.press(offsetUtf16 = 1, eventTimeMillis = 0)
        machine.advanceTime(350)

        assertEquals(
            SelectionState.Confirmed(SelectionRange(1, 2), SelectionRange(0, 2)),
            machine.move(offsetUtf16 = 0, exceededTouchSlop = true).state,
        )
        assertEquals(
            SelectionState.Confirmed(SelectionRange(1, 2), SelectionRange(1, 3)),
            machine.move(offsetUtf16 = 2, exceededTouchSlop = true).state,
        )
        machine.release()
        machine.beginAdjustment(SelectionEdge.START)
        assertEquals(
            SelectionState.Adjusting(SelectionRange(1, 3), SelectionEdge.START),
            machine.move(offsetUtf16 = 1, exceededTouchSlop = false).state,
        )
    }

    @Test
    fun `surrogate pair is never split`() {
        val machine = SelectionStateMachine("甲𠮷乙", 350)

        machine.press(offsetUtf16 = 2, eventTimeMillis = 0)
        val confirmed = machine.advanceTime(350)

        assertEquals(
            SelectionState.Confirmed(SelectionRange(1, 3), SelectionRange(1, 3)),
            confirmed.state,
        )
        assertFalse((confirmed.state as SelectionState.Confirmed).range.endUtf16 == 2)
    }

    @Test
    fun `cancel clears confirmed and adjusting selections`() {
        val machine = SelectionStateMachine("大家好", 350)
        machine.press(offsetUtf16 = 0, eventTimeMillis = 0)
        machine.advanceTime(350)
        machine.move(offsetUtf16 = 2, exceededTouchSlop = true)
        machine.release()
        machine.beginAdjustment(SelectionEdge.END)

        assertEquals(SelectionState.Idle, machine.cancel().state)
    }

    @Test
    fun `completed range restores but a surrogate split is rejected`() {
        val machine = SelectionStateMachine("甲𠮷乙", 350)

        assertEquals(
            SelectionState.Completed(SelectionRange(1, 3)),
            machine.restore(SelectionRange(1, 3)).state,
        )
        assertThrows(IllegalArgumentException::class.java) {
            machine.restore(SelectionRange(1, 2))
        }
    }

    @Test
    fun `state machine can navigate offsets without owning the document string`() {
        val navigator = object : SelectionTextNavigator {
            override val utf16Length = 100
            override fun characterRangeAt(offsetUtf16: Int) = SelectionRange(offsetUtf16, offsetUtf16 + 1)
            override fun previousCharacterStart(endExclusiveUtf16: Int) = endExclusiveUtf16 - 1
            override fun nextCharacterEnd(startUtf16: Int) = startUtf16 + 1
            override fun isCharacterBoundary(offsetUtf16: Int) = offsetUtf16 in 0..utf16Length
        }
        val machine = SelectionStateMachine(navigator, 350)

        machine.press(offsetUtf16 = 50, eventTimeMillis = 0)
        val confirmed = machine.advanceTime(350)

        assertEquals(
            SelectionState.Confirmed(SelectionRange(50, 51), SelectionRange(50, 51)),
            confirmed.state,
        )
        assertEquals(SelectionState.Completed(SelectionRange(80, 81)), machine.restore(SelectionRange(80, 81)).state)
    }
}
