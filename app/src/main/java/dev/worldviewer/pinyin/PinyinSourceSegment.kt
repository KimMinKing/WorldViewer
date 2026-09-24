package dev.worldviewer.pinyin

/** A source-preserving input slice for a future pinyin engine. Offsets are UTF-16 and end-exclusive. */
data class PinyinSourceSegment(
    val startUtf16: Int,
    val endUtf16: Int,
    val text: String,
    val kind: Kind,
) {
    init {
        require(startUtf16 >= 0)
        require(endUtf16 > startUtf16)
        require(text.length == endUtf16 - startUtf16)
    }

    enum class Kind {
        HAN,
        LITERAL,
    }
}

/**
 * Splits text without changing it or breaking surrogate pairs.
 *
 * HAN segments are candidates for conversion. An engine adapter must return unsupported code
 * points unchanged; LITERAL segments must bypass the engine completely.
 */
object PinyinSourceSegmenter {
    fun segment(source: String): List<PinyinSourceSegment> {
        if (source.isEmpty()) return emptyList()

        val result = mutableListOf<PinyinSourceSegment>()
        var segmentStart = 0
        var offset = 0
        var segmentKind = kindOf(source.codePointAt(offset))

        while (offset < source.length) {
            val codePoint = source.codePointAt(offset)
            val kind = kindOf(codePoint)
            if (kind != segmentKind) {
                result += source.segment(segmentStart, offset, segmentKind)
                segmentStart = offset
                segmentKind = kind
            }
            offset += Character.charCount(codePoint)
        }
        result += source.segment(segmentStart, source.length, segmentKind)
        return result
    }

    private fun String.segment(start: Int, end: Int, kind: PinyinSourceSegment.Kind) =
        PinyinSourceSegment(start, end, substring(start, end), kind)

    private fun kindOf(codePoint: Int): PinyinSourceSegment.Kind =
        if (isHan(codePoint)) PinyinSourceSegment.Kind.HAN else PinyinSourceSegment.Kind.LITERAL

    private fun isHan(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F ||
            codePoint in 0x30000..0x323AF
}
