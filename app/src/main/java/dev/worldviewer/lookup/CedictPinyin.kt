package dev.worldviewer.lookup

object CedictPinyin {
    private val numberedSyllable = Regex("([A-Za-zÜüVv]+(?::[A-Za-z]?)?)([1-5])")
    private val marks = mapOf(
        'a' to "āáǎàa",
        'e' to "ēéěèe",
        'i' to "īíǐìi",
        'o' to "ōóǒòo",
        'u' to "ūúǔùu",
        'ü' to "ǖǘǚǜü",
    )

    fun toToneMarks(value: String): String = numberedSyllable.replace(value) { match ->
        markSyllable(match.groupValues[1], match.groupValues[2].single().digitToInt())
    }

    private fun markSyllable(raw: String, tone: Int): String {
        val syllable = raw.replace("u:", "ü", ignoreCase = true).replace('v', 'ü').replace('V', 'Ü')
        if (tone == 5) return syllable
        val lower = syllable.lowercase()
        val markAt = when {
            'a' in lower -> lower.indexOf('a')
            'e' in lower -> lower.indexOf('e')
            "ou" in lower -> lower.indexOf('o')
            else -> lower.indexOfLast { it in "aeiouü" }
        }
        if (markAt < 0) return syllable
        val vowel = lower[markAt]
        val marked = checkNotNull(marks[vowel])[tone - 1]
        val replacement = if (syllable[markAt].isUpperCase()) marked.uppercaseChar() else marked
        return syllable.replaceRange(markAt, markAt + 1, replacement.toString())
    }
}
