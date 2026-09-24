package dev.worldviewer.reader

data class AutomaticChapter(
    val title: String,
    val utf16Offset: Int,
)

object AutomaticChapterDetector {
    fun detect(blocks: Sequence<TextBlock>, maxEntries: Int = 500): List<AutomaticChapter> {
        require(maxEntries > 0)
        val result = ArrayList<AutomaticChapter>()
        val line = StringBuilder()
        var lineStart = 0
        var sawAny = false
        var previousWasCarriageReturn = false

        fun emit() {
            if (result.size >= maxEntries || line.length > MAX_LINE_UTF16) return
            val raw = line.toString()
            val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
            val title = raw.trim()
            if (CHAPTER.matches(title) || SPECIAL.matches(title)) {
                result += AutomaticChapter(title, lineStart + leading)
            }
        }

        for (block in blocks) {
            if (result.size >= maxEntries) break
            if (!sawAny) {
                lineStart = block.startUtf16
                sawAny = true
            }
            block.text.forEachIndexed { local, character ->
                val global = block.startUtf16 + local
                when (character) {
                    '\r' -> {
                        emit()
                        line.clear()
                        lineStart = global + 1
                        previousWasCarriageReturn = true
                    }
                    '\n' -> {
                        if (!previousWasCarriageReturn) emit()
                        line.clear()
                        lineStart = global + 1
                        previousWasCarriageReturn = false
                    }
                    else -> {
                        line.append(character)
                        previousWasCarriageReturn = false
                    }
                }
            }
        }
        if (result.size < maxEntries && line.isNotEmpty()) emit()
        return result
    }

    private const val MAX_LINE_UTF16 = 64
    private val CHAPTER = Regex(
        "^第[零〇一二三四五六七八九十百千万两0-9]{1,12}[章节卷回部篇集](?:[\\s　:：、.．-]?.{0,40})?$",
    )
    private val SPECIAL = Regex("^(序章|楔子|前言|后记|尾声)(?:[\\s　:：、.．-]?.{0,40})?$")
}
