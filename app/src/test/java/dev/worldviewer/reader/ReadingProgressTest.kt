package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun `maps document endpoints to progress endpoints`() {
        assertEquals(0, ReadingProgress.fromOffset(0, 10_001))
        assertEquals(ReadingProgress.MAX, ReadingProgress.fromOffset(10_000, 10_001))
        assertEquals(0, ReadingProgress.toOffset(0, 10_001))
        assertEquals(10_000, ReadingProgress.toOffset(ReadingProgress.MAX, 10_001))
    }

    @Test
    fun `mapping clamps values and handles empty documents`() {
        assertEquals(0, ReadingProgress.fromOffset(-10, 100))
        assertEquals(ReadingProgress.MAX, ReadingProgress.fromOffset(1_000, 100))
        assertEquals(0, ReadingProgress.toOffset(-1, 100))
        assertEquals(99, ReadingProgress.toOffset(2_000, 100))
        assertEquals(0, ReadingProgress.fromOffset(10, 0))
        assertEquals(0, ReadingProgress.toOffset(500, 1))
    }

    @Test
    fun `mapping large documents does not overflow`() {
        assertEquals(500, ReadingProgress.fromOffset(Int.MAX_VALUE / 2, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE - 1, ReadingProgress.toOffset(ReadingProgress.MAX, Int.MAX_VALUE))
    }
}
