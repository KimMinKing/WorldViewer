package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutoBookCoverTest {
    @Test
    fun `same book always receives the same cover`() {
        val first = AutoBookCover.from("a".repeat(64), "江湖夜雨.txt")

        assertEquals(first, AutoBookCover.from("a".repeat(64), "江湖夜雨.txt"))
        assertEquals("江", first.initial)
    }

    @Test
    fun `content identifiers distribute books across cover colors`() {
        val first = AutoBookCover.from("0".repeat(64), "第一册.txt")
        val second = AutoBookCover.from("f".repeat(64), "第二册.txt")

        assertNotEquals(first.colorArgb, second.colorArgb)
    }

    @Test
    fun `supplementary character remains one cover initial`() {
        val cover = AutoBookCover.from("1".repeat(64), "𠮷野家.txt")

        assertEquals("𠮷", cover.initial)
        assertEquals(2, cover.initial.length)
    }
}
