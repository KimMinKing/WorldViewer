package dev.worldviewer.selection

import dev.worldviewer.reader.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedBlockSelectionTextNavigatorTest {
    private val blocks = listOf(
        TextBlock(0, 0, "甲𠮷"),
        TextBlock(1, 3, "乙丙"),
    )

    @Test
    fun `navigates surrogate pairs and block boundaries from cached blocks`() {
        val navigator = navigator(blocks)

        assertEquals(SelectionRange(1, 3), navigator.characterRangeAt(2))
        assertEquals(1, navigator.previousCharacterStart(3))
        assertEquals(3, navigator.nextCharacterEnd(1))
        assertFalse(navigator.isCharacterBoundary(2))
        assertTrue(navigator.isCharacterBoundary(3))
        assertEquals(SelectionRange(3, 4), navigator.characterRangeAt(3))
    }

    @Test
    fun `missing block never causes disk work and reports unavailable text`() {
        val navigator = navigator(blocks.take(1))

        assertThrows(SelectionTextUnavailableException::class.java) {
            navigator.characterRangeAt(3)
        }
    }

    private fun navigator(cached: List<TextBlock>) = CachedBlockSelectionTextNavigator(5) { offset ->
        cached.firstOrNull { offset in it.startUtf16 until it.endUtf16 }
    }
}
