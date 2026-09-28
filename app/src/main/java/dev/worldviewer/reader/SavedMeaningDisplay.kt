package dev.worldviewer.reader

data class SavedMeaningDisplay(
    val startUtf16: Int,
    val endUtf16: Int,
    val label: String?,
)

data class MeaningAnchor(val line: Int, val sourceX: Float, val label: String, val width: Float)

data class MeaningPlacement(val line: Int, val x: Float, val lane: Int, val label: String)

/** Give nearby meanings separate lines and keep a word at the right edge visible. */
fun layoutMeaningAnchors(anchors: List<MeaningAnchor>, left: Float, right: Float): List<MeaningPlacement> {
    val lineLanes = mutableMapOf<Int, Int>()
    return anchors.map { anchor ->
        val lane = lineLanes.getOrDefault(anchor.line, 0)
        lineLanes[anchor.line] = lane + 1
        val visibleWidth = anchor.width.coerceIn(0f, (right - left).coerceAtLeast(0f))
        MeaningPlacement(
            line = anchor.line,
            x = anchor.sourceX.coerceIn(left, right - visibleWidth),
            lane = lane,
            label = anchor.label,
        )
    }
}

private val MEANING_WHITESPACE = Regex("\\s+")

/** Keep every saved range marked, and preview short selections between source lines. */
fun savedMeaningDisplays(meanings: List<UserMeaning>): List<SavedMeaningDisplay> =
    meanings.asSequence()
        .filter { it.scope == MeaningScope.RANGE }
        .groupBy { requireNotNull(it.rangeStartUtf16) to requireNotNull(it.rangeEndUtf16) }
        .map { (range, values) ->
            val first = values.minWith(compareBy<UserMeaning> { it.createdAtEpochMillis }.thenBy { it.id })
            val source = first.sourceText
            val inlineSelection = source.codePointCount(0, source.length) in 1..16 &&
                '\n' !in source && '\r' !in source
            val label = if (inlineSelection) {
                val distinctMeanings = values.map(UserMeaning::meaning).distinct()
                first.meaning.replace(MEANING_WHITESPACE, " ").trim() +
                    if (distinctMeanings.size > 1) " +${distinctMeanings.size - 1}" else ""
            } else null
            SavedMeaningDisplay(range.first, range.second, label)
        }
        .sortedWith(compareBy(SavedMeaningDisplay::startUtf16).thenBy(SavedMeaningDisplay::endUtf16))
