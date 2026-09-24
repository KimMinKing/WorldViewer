package dev.worldviewer.pinyin

import dev.worldviewer.pinyin.PinyinSourceSegment.Kind.HAN
import dev.worldviewer.pinyin.PinyinSourceSegment.Kind.LITERAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinSourceSegmenterTest {
    @Test
    fun `empty input has no segments`() {
        assertTrue(PinyinSourceSegmenter.segment("").isEmpty())
    }

    @Test
    fun `mixed input preserves exact UTF-16 ranges and supplementary characters`() {
        val source = "A中🙂𠮷，長樂"

        assertEquals(
            listOf(
                PinyinSourceSegment(0, 1, "A", LITERAL),
                PinyinSourceSegment(1, 2, "中", HAN),
                PinyinSourceSegment(2, 4, "🙂", LITERAL),
                PinyinSourceSegment(4, 6, "𠮷", HAN),
                PinyinSourceSegment(6, 7, "，", LITERAL),
                PinyinSourceSegment(7, 9, "長樂", HAN),
            ),
            PinyinSourceSegmenter.segment(source),
        )
    }

    @Test
    fun `segments reconstruct source without gaps or overlap`() {
        val source = "重庆火锅\nA中🙂𠮷，長樂"
        val segments = PinyinSourceSegmenter.segment(source)

        assertEquals(source, segments.joinToString(separator = "") { it.text })
        assertEquals(0, segments.first().startUtf16)
        assertEquals(source.length, segments.last().endUtf16)
        segments.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endUtf16, right.startUtf16)
        }
    }

    @Test
    fun `unpaired surrogate is preserved as literal input`() {
        val source = "中\uD83D文"

        assertEquals(
            listOf(
                PinyinSourceSegment(0, 1, "中", HAN),
                PinyinSourceSegment(1, 2, "\uD83D", LITERAL),
                PinyinSourceSegment(2, 3, "文", HAN),
            ),
            PinyinSourceSegmenter.segment(source),
        )
    }
}
