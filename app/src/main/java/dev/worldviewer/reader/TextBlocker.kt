package dev.worldviewer.reader

import java.text.BreakIterator
import java.util.Locale

object TextBlocker {
    fun split(text: String, maxUtf16Length: Int): List<TextBlock> {
        require(maxUtf16Length > 0) { "maxUtf16Length must be positive" }
        if (text.isEmpty()) return emptyList()

        val blocks = ArrayList<TextBlock>((text.length / maxUtf16Length) + 1)
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var blockStart = 0
        var lastBoundary = blockStart
        var lastParagraphBoundary = 0
        var lastSentenceBoundary = 0
        var boundary = boundaries.next()

        while (boundary != BreakIterator.DONE) {
            if (boundary - blockStart > maxUtf16Length && lastBoundary > blockStart) {
                val preferredFrom = blockStart + maxUtf16Length / 2
                val split = lastParagraphBoundary.takeIf { it > blockStart && it >= preferredFrom }
                    ?: lastSentenceBoundary.takeIf { it > blockStart && it >= preferredFrom }
                    ?: lastBoundary
                blocks += text.block(blocks.size, blockStart, split)
                blockStart = split
                lastBoundary = split
                lastParagraphBoundary = 0
                lastSentenceBoundary = 0
                boundary = boundaries.following(split)
                continue
            }
            lastBoundary = boundary
            if (boundary - blockStart > maxUtf16Length) {
                blocks += text.block(blocks.size, blockStart, boundary)
                blockStart = boundary
                lastBoundary = boundary
                lastParagraphBoundary = 0
                lastSentenceBoundary = 0
            } else {
                when (text[boundary - 1]) {
                    '\n', '\r' -> lastParagraphBoundary = boundary
                    '。', '！', '？', '.', '!', '?' -> lastSentenceBoundary = boundary
                }
            }
            boundary = boundaries.next()
        }

        if (blockStart < text.length) {
            blocks += text.block(blocks.size, blockStart, text.length)
        }
        return blocks
    }

    private fun String.block(id: Int, start: Int, end: Int) = TextBlock(
        id = id.toLong(),
        startUtf16 = start,
        text = substring(start, end),
    )
}
