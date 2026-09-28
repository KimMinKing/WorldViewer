package dev.worldviewer.reader

data class SavedMeaningDisplay(
    val startUtf16: Int,
    val endUtf16: Int,
    val label: String?,
)

private val MEANING_WHITESPACE = Regex("\\s+")

/** Keep every saved range marked, but put only short Han-word meanings between source lines. */
fun savedMeaningDisplays(meanings: List<UserMeaning>): List<SavedMeaningDisplay> =
    meanings.asSequence()
        .filter { it.scope == MeaningScope.RANGE }
        .groupBy { requireNotNull(it.rangeStartUtf16) to requireNotNull(it.rangeEndUtf16) }
        .map { (range, values) ->
            val first = values.minWith(compareBy<UserMeaning> { it.createdAtEpochMillis }.thenBy { it.id })
            val source = first.sourceText
            val compactWord = source.codePointCount(0, source.length) in 1..6 &&
                source.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
            val label = if (compactWord) {
                val distinctMeanings = values.map(UserMeaning::meaning).distinct()
                first.meaning.replace(MEANING_WHITESPACE, " ").trim() +
                    if (distinctMeanings.size > 1) " +${distinctMeanings.size - 1}" else ""
            } else null
            SavedMeaningDisplay(range.first, range.second, label)
        }
        .sortedWith(compareBy(SavedMeaningDisplay::startUtf16).thenBy(SavedMeaningDisplay::endUtf16))
