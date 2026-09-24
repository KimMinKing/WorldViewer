package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TextBlockerTest {
    @Test
    fun `blocks reconstruct the exact source with contiguous UTF-16 anchors`() {
        val source = "大家好。𠮷野 e\u0301 👩‍💻 English\n".repeat(40)
        val blocks = TextBlocker.split(source, maxUtf16Length = 17)

        assertEquals(source, blocks.joinToString(separator = "") { it.text })
        blocks.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endUtf16, next.startUtf16)
        }
        assertEquals(source.length, blocks.last().endUtf16)
    }

    @Test
    fun `a surrogate pair is never divided even when limit is one UTF-16 unit`() {
        val blocks = TextBlocker.split("A𠮷B", maxUtf16Length = 1)

        assertEquals(listOf("A", "𠮷", "B"), blocks.map { it.text })
        blocks.forEach { block ->
            assertFalse(block.text.last().isHighSurrogate())
            assertFalse(block.text.first().isLowSurrogate())
        }
    }

    @Test
    fun `a combining sequence stays in one block`() {
        val blocks = TextBlocker.split("Ae\u0301B", maxUtf16Length = 1)

        assertEquals(listOf("A", "e\u0301", "B"), blocks.map { it.text })
    }

    @Test
    fun `empty text has no blocks and invalid limit is rejected`() {
        assertEquals(emptyList<TextBlock>(), TextBlocker.split("", 8))
        assertThrows(IllegalArgumentException::class.java) { TextBlocker.split("text", 0) }
    }
}
