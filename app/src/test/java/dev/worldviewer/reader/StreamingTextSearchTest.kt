package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextSearchTest {
    @Test
    fun `finds exact matches without joining the document`() {
        val blocks = sequenceOf(
            TextBlock(0, 0, "大家好。江湖"),
            TextBlock(1, 7, "夜雨。江湖再见。"),
        )
        val batches = mutableListOf<TextSearchProgress>()

        val summary = StreamingTextSearch.search(blocks, "江湖", onProgress = batches::add)

        assertEquals(listOf(4, 10), batches.flatMap { it.matches }.map { it.utf16Offset })
        assertEquals(2, summary.resultCount)
        assertEquals(2, summary.scannedBlocks)
        assertFalse(summary.cancelled)
        assertFalse(summary.truncated)
    }

    @Test
    fun `finds a match split across adjacent blocks once`() {
        val blocks = sequenceOf(
            TextBlock(0, 0, "山雨欲"),
            TextBlock(1, 3, "来风满楼"),
        )
        val results = mutableListOf<TextSearchResult>()

        StreamingTextSearch.search(blocks, "山雨欲来") { results += it.matches }

        assertEquals(listOf(0), results.map { it.utf16Offset })
    }

    @Test
    fun `cancellation stops before reading another block`() {
        val cancellation = TextSearchCancellation()
        var produced = 0
        val blocks = sequence {
            repeat(10) { index ->
                produced++
                yield(TextBlock(index.toLong(), index * 4, "江湖。\n"))
            }
        }

        val summary = StreamingTextSearch.search(blocks, "江湖", cancellation) { progress ->
            if (progress.matches.isNotEmpty()) cancellation.cancel()
        }

        assertTrue(summary.cancelled)
        assertTrue(produced < 10)
    }

    @Test
    fun `maximum results bounds work and marks truncation`() {
        val batches = mutableListOf<TextSearchProgress>()

        val summary = StreamingTextSearch.search(
            blocks = sequenceOf(TextBlock(0, 0, "人人人人人")),
            query = "人",
            maximumResults = 3,
            onProgress = batches::add,
        )

        assertEquals(3, summary.resultCount)
        assertEquals(3, batches.flatMap { it.matches }.size)
        assertTrue(summary.truncated)
    }
}
