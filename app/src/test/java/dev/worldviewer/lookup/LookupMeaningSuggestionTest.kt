package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Test

class LookupMeaningSuggestionTest {
    @Test
    fun `displayed meanings are trimmed deduplicated and kept in order`() {
        val response = DictionaryLookupResponse(
            providerId = "test",
            providerVersion = "1",
            queryText = "江",
            entries = listOf(
                DictionaryLookupEntry("江", listOf(" 강 ", "장강")),
                DictionaryLookupEntry("江", listOf("강", "너무 긴 뜻")),
            ),
            resultType = LookupResultType.TRANSLATED_DICTIONARY,
            attributions = listOf(LookupAttribution("test")),
            storagePolicy = LookupStoragePolicy(LookupRetention.DO_NOT_STORE),
        )

        assertEquals(listOf("강", "장강"), response.savableMeanings(maximumUtf16 = 2))
    }
}
