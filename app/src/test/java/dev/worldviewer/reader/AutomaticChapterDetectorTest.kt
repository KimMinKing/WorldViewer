package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticChapterDetectorTest {
    @Test
    fun `detects common Chinese chapter lines with source offsets`() {
        val text = "前言\r\n正文\n  第一章 初入江湖\n内容\n第十二回：夜雨"

        val chapters = AutomaticChapterDetector.detect(sequenceOf(TextBlock(0, 0, text)))

        assertEquals(listOf("前言", "第一章 初入江湖", "第十二回：夜雨"), chapters.map { it.title })
        assertEquals(listOf(0, 9, 21), chapters.map { it.utf16Offset })
    }

    @Test
    fun `line split across indexed blocks is detected once`() {
        val blocks = sequenceOf(
            TextBlock(0, 0, "正文\n第十"),
            TextBlock(1, 5, "章 重逢\n内容"),
        )

        val chapters = AutomaticChapterDetector.detect(blocks)

        assertEquals(listOf(AutomaticChapter("第十章 重逢", 3)), chapters)
    }

    @Test
    fun `ordinary prose and overly long lines are ignored`() {
        val text = "这是第一章里面的普通句子。\n第一个人走来。\n第一章" + "很".repeat(80)

        assertEquals(emptyList<AutomaticChapter>(), AutomaticChapterDetector.detect(sequenceOf(TextBlock(0, 0, text))))
    }

    @Test
    fun `entry count is capped`() {
        val text = (1..20).joinToString("\n") { "第${it}章" }

        val chapters = AutomaticChapterDetector.detect(sequenceOf(TextBlock(0, 0, text)), maxEntries = 3)

        assertEquals(3, chapters.size)
    }
}
