package dev.worldviewer.selection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRequestGateTest {
    @Test
    fun `late result for selection A cannot replace selection B`() {
        val gate = SelectionRequestGate()
        gate.selectionChanged()
        val requestA = gate.snapshot()

        gate.selectionChanged()
        val requestB = gate.snapshot()

        assertFalse(gate.isCurrent(requestA))
        assertTrue(gate.isCurrent(requestB))
    }
}
