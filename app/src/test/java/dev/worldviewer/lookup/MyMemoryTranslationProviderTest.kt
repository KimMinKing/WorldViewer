package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class MyMemoryTranslationProviderTest {
    @Test
    fun `successful response is a non-persistent explicit translation`() {
        var requestedUrl: URL? = null
        val provider = MyMemoryTranslationProvider { url, _ ->
            requestedUrl = url
            """{"responseData":{"translatedText":"문주","match":0.99},"responseStatus":200}"""
        }

        val response = provider.lookup(request("掌门"), LookupCancellation())

        assertTrue(checkNotNull(requestedUrl).toString().contains("%E6%8E%8C%E9%97%A8"))
        assertEquals("문주", response.entries.single().meanings.single())
        assertEquals(LookupResultType.TRANSLATION, response.resultType)
        assertEquals(LookupRetention.DO_NOT_STORE, response.storagePolicy.retention)
        assertEquals("MyMemory by Translated", response.attributions.single().displayName)
    }

    @Test
    fun `quota response is a failure instead of a translation`() {
        val provider = MyMemoryTranslationProvider { _, _ ->
            """{"responseData":{},"responseStatus":403,"responseDetails":"DAILY LIMIT EXCEEDED"}"""
        }

        val failure = runCatching { provider.lookup(request("江湖"), LookupCancellation()) }.exceptionOrNull()

        assertTrue(failure is MyMemoryTranslationException)
        assertTrue(checkNotNull(failure?.message).contains("DAILY LIMIT"))
    }

    @Test
    fun `queries larger than the public API limit are rejected before transport`() {
        var called = false
        val provider = MyMemoryTranslationProvider { _, _ -> called = true; "" }

        val failure = runCatching {
            provider.lookup(request("界".repeat(167)), LookupCancellation())
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
