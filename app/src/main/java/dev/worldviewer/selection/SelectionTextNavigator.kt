package dev.worldviewer.selection

import dev.worldviewer.reader.TextBlock

interface SelectionTextNavigator {
    val utf16Length: Int

    fun characterRangeAt(offsetUtf16: Int): SelectionRange

    fun previousCharacterStart(endExclusiveUtf16: Int): Int

    fun nextCharacterEnd(startUtf16: Int): Int

    fun isCharacterBoundary(offsetUtf16: Int): Boolean
}

class StringSelectionTextNavigator(private val text: String) : SelectionTextNavigator {
    init {
        require(text.isNotEmpty())
    }

    override val utf16Length: Int
        get() = text.length

    override fun characterRangeAt(offsetUtf16: Int): SelectionRange {
        var start = offsetUtf16.coerceIn(0, text.length - 1)
        if (
            start > 0 &&
            Character.isLowSurrogate(text[start]) &&
            Character.isHighSurrogate(text[start - 1])
        ) {
            start -= 1
        }
        return SelectionRange(start, start + Character.charCount(text.codePointAt(start)))
    }

    override fun previousCharacterStart(endExclusiveUtf16: Int): Int =
        Character.offsetByCodePoints(text, endExclusiveUtf16, -1)

    override fun nextCharacterEnd(startUtf16: Int): Int =
        Character.offsetByCodePoints(text, startUtf16, 1)

    override fun isCharacterBoundary(offsetUtf16: Int): Boolean =
        offsetUtf16 in 0..text.length && !(
            offsetUtf16 in 1 until text.length &&
                Character.isHighSurrogate(text[offsetUtf16 - 1]) &&
                Character.isLowSurrogate(text[offsetUtf16])
            )
}

class SelectionTextUnavailableException(offsetUtf16: Int) :
    IllegalStateException("Text block for UTF-16 offset $offsetUtf16 is not cached")

/** Resolves character boundaries only from blocks already present in the reader cache. */
class CachedBlockSelectionTextNavigator(
    override val utf16Length: Int,
    private val cachedBlockAt: (Int) -> TextBlock?,
) : SelectionTextNavigator {
    init {
        require(utf16Length > 0)
    }

    override fun characterRangeAt(offsetUtf16: Int): SelectionRange {
        var start = offsetUtf16.coerceIn(0, utf16Length - 1)
        val block = requireBlock(start)
        var local = start - block.startUtf16
        if (
            local > 0 &&
            Character.isLowSurrogate(block.text[local]) &&
            Character.isHighSurrogate(block.text[local - 1])
        ) {
            local -= 1
            start -= 1
        }
        return SelectionRange(start, start + Character.charCount(block.text.codePointAt(local)))
    }

    override fun previousCharacterStart(endExclusiveUtf16: Int): Int {
        require(endExclusiveUtf16 in 1..utf16Length)
        val lastOffset = endExclusiveUtf16 - 1
        val block = requireBlock(lastOffset)
        val local = lastOffset - block.startUtf16
        return if (
            local > 0 &&
            Character.isLowSurrogate(block.text[local]) &&
            Character.isHighSurrogate(block.text[local - 1])
        ) {
            endExclusiveUtf16 - 2
        } else {
            lastOffset
        }
    }

    override fun nextCharacterEnd(startUtf16: Int): Int {
        require(startUtf16 in 0 until utf16Length)
        val block = requireBlock(startUtf16)
        val local = startUtf16 - block.startUtf16
        return startUtf16 + Character.charCount(block.text.codePointAt(local))
    }

    override fun isCharacterBoundary(offsetUtf16: Int): Boolean {
        if (offsetUtf16 !in 0..utf16Length) return false
        if (offsetUtf16 == 0 || offsetUtf16 == utf16Length) return true
        val block = requireBlock(offsetUtf16)
        val local = offsetUtf16 - block.startUtf16
        return !(
            local > 0 &&
                Character.isHighSurrogate(block.text[local - 1]) &&
                Character.isLowSurrogate(block.text[local])
            )
    }

    private fun requireBlock(offsetUtf16: Int): TextBlock =
        cachedBlockAt(offsetUtf16)?.takeIf { offsetUtf16 in it.startUtf16 until it.endUtf16 }
            ?: throw SelectionTextUnavailableException(offsetUtf16)
}
