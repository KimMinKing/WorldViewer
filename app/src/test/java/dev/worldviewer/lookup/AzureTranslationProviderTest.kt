package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureTranslationProviderTest {
    @Test
    fun `metered response header wins and fallback counts surrogate pairs as two`() {
        assertEquals(3, meteredCharacters("江湖", "3"))
        assertEquals(2, meteredCharacters("𠮷", null))
        assertEquals(2, meteredCharacters("𠮷", "invalid"))
    }

    @Test
    fun `direct translation is non-persistent and credentials reach only transport`() {
        var observedText = ""
        var observedCredentials: AzureTranslatorCredentials? = null
        val credentials = AzureTranslatorCredentials("secret-key", "koreacentral")
        var recorded = -1
        val provider = AzureTranslationProvider(credentials, transport = AzureTranslationTransport { text, supplied, _ ->
            observedText = text
            observedCredentials = supplied
            AzureTranslationResult("강호", 2)
        }, recordUsage = { recorded = it })

        val response = provider.lookup(request("江湖"), LookupCancellation())

        assertEquals("江湖", observedText)
        assertEquals(credentials, observedCredentials)
        assertEquals("강호", response.entries.single().meanings.single())
        assertEquals(LookupResultType.TRANSLATION, response.resultType)
        assertEquals(LookupRetention.DO_NOT_STORE, response.storagePolicy.retention)
        assertEquals("Microsoft Azure Translator", response.attributions.single().displayName)
        assertEquals(2, recorded)
    }

    @Test
    fun `fifty thousand Unicode characters are accepted`() {
        val text = "江".repeat(50_000)
        val provider = AzureTranslationProvider(AzureTranslatorCredentials("key", "region"), AzureTranslationTransport { _, _, _ ->
            AzureTranslationResult("결과", 50_000)
        })

        assertEquals("결과", provider.lookup(request(text), LookupCancellation()).entries.single().meanings.single())
    }

    @Test
    fun `more than fifty thousand Unicode characters are rejected before transport`() {
        var called = false
        val provider = AzureTranslationProvider(AzureTranslatorCredentials("key", "region"), AzureTranslationTransport { _, _, _ ->
            called = true
            AzureTranslationResult("결과", 1)
        })

        val failure = runCatching {
            provider.lookup(request("𠮷".repeat(50_001)), LookupCancellation())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(false, called)
    }

    @Test
    fun `failed transport and pre-cancelled request do not record usage`() {
        var recorded = 0
        val provider = AzureTranslationProvider(
            AzureTranslatorCredentials("key", "region"),
            AzureTranslationTransport { _, _, _ -> throw AzureTranslationException("network unavailable") },
            recordUsage = { recorded += it },
        )
        assertTrue(runCatching { provider.lookup(request("江湖"), LookupCancellation()) }.isFailure)
        assertEquals(0, recorded)
        val cancelled = LookupCancellation().apply { cancel() }
        assertTrue(runCatching { provider.lookup(request("江湖"), cancelled) }.isFailure)
        assertEquals(0, recorded)
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
