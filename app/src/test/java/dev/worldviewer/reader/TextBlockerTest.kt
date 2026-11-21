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
    fun `long paragraphs prefer a sentence ending over an arbitrary cutoff`() {
        val source = "甲".repeat(10) + "。" + "乙".repeat(10) + "。"
        val blocks = TextBlocker.split(source, maxUtf16Length = 16)

        assertEquals(listOf("甲".repeat(10) + "。", "乙".repeat(10) + "。"), blocks.map { it.text })
        assertEquals(source, blocks.joinToString("") { it.text })
    }

    @Test
    fun `a paragraph break takes priority and a single unbroken paragraph remains bounded`() {
        val paragraphs = "甲".repeat(10) + "\n" + "乙".repeat(10)
        assertEquals("甲".repeat(10) + "\n", TextBlocker.split(paragraphs, 16).first().text)
        assertEquals(listOf(16, 16, 8), TextBlocker.split("甲".repeat(40), 16).map { it.text.length })
    }

    @Test
    fun `empty text has no blocks and invalid limit is rejected`() {
        assertEquals(emptyList<TextBlock>(), TextBlocker.split("", 8))
        assertThrows(IllegalArgumentException::class.java) { TextBlocker.split("text", 0) }
    }
}
