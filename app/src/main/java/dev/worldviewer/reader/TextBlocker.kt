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
        var boundary = boundaries.next()

        while (boundary != BreakIterator.DONE) {
            if (boundary - blockStart > maxUtf16Length && lastBoundary > blockStart) {
                blocks += text.block(blocks.size, blockStart, lastBoundary)
                blockStart = lastBoundary
                continue
            }
            lastBoundary = boundary
            if (boundary - blockStart > maxUtf16Length) {
                blocks += text.block(blocks.size, blockStart, boundary)
                blockStart = boundary
                lastBoundary = boundary
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
