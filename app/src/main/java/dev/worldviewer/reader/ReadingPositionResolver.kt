package dev.worldviewer.reader

object ReadingPositionResolver {
    fun resolve(
        currentDocumentSha256: String,
        documentUtf16Length: Int,
        containingBlockPosition: Int,
        availableBlocks: Map<Int, TextBlock>,
        saved: ReadingPosition,
    ): ReadingPosition? {
        if (saved.documentSha256 != currentDocumentSha256) return null
        if (saved.utf16Offset !in 0..documentUtf16Length) return null
        val containing = availableBlocks[containingBlockPosition] ?: return null
        val offsetForBlock = saved.utf16Offset.coerceAtMost((documentUtf16Length - 1).coerceAtLeast(0))
        if (documentUtf16Length > 0 && offsetForBlock !in containing.startUtf16 until containing.endUtf16) {
            return null
        }
        val quoteStart = saved.utf16Offset - saved.quoteBefore.length
        val quoteEnd = saved.utf16Offset + saved.quoteAfter.length
        if (quoteStart < 0 || quoteEnd > documentUtf16Length) return null
        if (textBetween(availableBlocks.values, quoteStart, saved.utf16Offset) != saved.quoteBefore) return null
        if (textBetween(availableBlocks.values, saved.utf16Offset, quoteEnd) != saved.quoteAfter) return null
        return saved.copy(blockIndex = containingBlockPosition)
    }

    private fun textBetween(blocks: Collection<TextBlock>, start: Int, end: Int): String? {
        if (start == end) return ""
        val result = StringBuilder(end - start)
        var expected = start
        for (block in blocks.sortedBy(TextBlock::startUtf16)) {
            if (block.endUtf16 <= expected) continue
            if (block.startUtf16 > expected) return null
            val localStart = (expected - block.startUtf16).coerceAtLeast(0)
            val localEnd = (end - block.startUtf16).coerceIn(localStart, block.text.length)
            result.append(block.text, localStart, localEnd)
            expected = block.startUtf16 + localEnd
            if (expected == end) return result.toString()
        }
        return null
    }
}
