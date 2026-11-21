package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChapterTranslationRangeTest {
    @Test fun `selects current chapter without including next chapter`() {
        val chapters = listOf("第一章" to 100, "第二章" to 600, "第三章" to 1_100)
        val range = ChapterTranslationRangeSelector.select(chapters, 850, 1_600, 15_000)
        assertEquals("第二章", range.title)
        assertEquals(600, range.startUtf16)
        assertEquals(1_100, range.endUtf16)
        assertEquals(500, range.lengthUtf16)
    }

    @Test fun `missing heading and preface are rejected before reading or sending`() {
        assertThrows(IllegalStateException::class.java) {
            ChapterTranslationRangeSelector.select(emptyList(), 50, 1_000, 15_000)
        }
        assertThrows(IllegalStateException::class.java) {
            ChapterTranslationRangeSelector.select(listOf("第一章" to 100), 50, 1_000, 15_000)
        }
    }

    @Test fun `oversized or corrupt chapter range is rejected rather than truncated`() {
        assertThrows(IllegalStateException::class.java) {
            ChapterTranslationRangeSelector.select(listOf("第一章" to 0), 500, 16_001, 15_000)
        }
        assertThrows(IllegalStateException::class.java) {
            ChapterTranslationRangeSelector.select(listOf("第二章" to 500, "第一章" to 100), 600, 1_000, 15_000)
        }
    }
}
