package dev.worldviewer.lookup

/** Korean meanings a user explicitly chose to keep from a displayed lookup result. */
fun DictionaryLookupResponse.savableMeanings(maximumUtf16: Int): List<String> {
    require(maximumUtf16 > 0)
    return entries.asSequence()
        .flatMap { it.meanings.asSequence() }
        .map(String::trim)
        .filter { it.isNotEmpty() && it.length <= maximumUtf16 }
        .distinct()
        .toList()
}
