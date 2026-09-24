package dev.worldviewer.pinyin

data class PinyinReading(
    val startUtf16: Int,
    val endUtf16: Int,
    val sourceText: String,
    val pronunciation: String?,
)

/** Performs CPU-only lookup. Load the table and call this adapter away from the UI input path. */
class UnihanPinyinAdapter(private val table: UnihanPinyinTable) {
    fun convert(source: String): List<PinyinReading> = buildList {
        PinyinSourceSegmenter.segment(source).forEach { segment ->
            if (segment.kind == PinyinSourceSegment.Kind.LITERAL) {
                add(PinyinReading(segment.startUtf16, segment.endUtf16, segment.text, null))
                return@forEach
            }

            var offset = segment.startUtf16
            while (offset < segment.endUtf16) {
                val codePoint = source.codePointAt(offset)
                val length = Character.charCount(codePoint)
                val end = offset + length
                add(
                    PinyinReading(
                        startUtf16 = offset,
                        endUtf16 = end,
                        sourceText = source.substring(offset, end),
                        pronunciation = table.defaultReading(codePoint),
                    ),
                )
                offset = end
            }
        }
    }
}
