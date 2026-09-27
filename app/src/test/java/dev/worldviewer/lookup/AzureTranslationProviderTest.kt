package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureTranslationProviderTest {
    @Test
    fun `direct translation is non-persistent and credentials reach only transport`() {
        var observedText = ""
        var observedCredentials: AzureTranslatorCredentials? = null
        val credentials = AzureTranslatorCredentials("secret-key", "koreacentral")
        val provider = AzureTranslationProvider(credentials) { text, supplied, _ ->
            observedText = text
            observedCredentials = supplied
            "강호"
        }

        val response = provider.lookup(request("江湖"), LookupCancellation())

        assertEquals("江湖", observedText)
        assertEquals(credentials, observedCredentials)
        assertEquals("강호", response.entries.single().meanings.single())
        assertEquals(LookupResultType.TRANSLATION, response.resultType)
        assertEquals(LookupRetention.DO_NOT_STORE, response.storagePolicy.retention)
        assertEquals("Microsoft Azure Translator", response.attributions.single().displayName)
    }

    @Test
    fun `fifty thousand Unicode characters are accepted`() {
        val text = "江".repeat(50_000)
        val provider = AzureTranslationProvider(AzureTranslatorCredentials("key", "region")) { _, _, _ -> "결과" }

        assertEquals("결과", provider.lookup(request(text), LookupCancellation()).entries.single().meanings.single())
    }

    @Test
    fun `more than fifty thousand Unicode characters are rejected before transport`() {
        var called = false
        val provider = AzureTranslationProvider(AzureTranslatorCredentials("key", "region")) { _, _, _ ->
            called = true
            "결과"
        }

        val failure = runCatching {
            provider.lookup(request("𠮷".repeat(50_001)), LookupCancellation())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(false, called)
    }

    private fun request(text: String) = DictionaryLookupRequest(
        sourceText = text,
        documentId = "document",
        documentVersion = "document",
        rangeStartUtf16 = 0,
        rangeEndUtf16 = text.length,
        selectionVersion = 1,
    )
}
