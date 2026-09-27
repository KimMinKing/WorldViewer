package dev.worldviewer.lookup

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Explicit, non-persistent Chinese-to-Korean translation through MyMemory's public GET API. */
class MyMemoryTranslationProvider(
    private val fetch: (URL, LookupCancellation) -> String = ::fetchJson,
) : DictionaryLookupProvider {
    override fun lookup(
        request: DictionaryLookupRequest,
        cancellation: LookupCancellation,
    ): DictionaryLookupResponse {
        cancellation.throwIfCancelled()
        val queryBytes = request.sourceText.toByteArray(StandardCharsets.UTF_8)
        require(queryBytes.size <= MAX_QUERY_BYTES) {
            "온라인 번역은 한 번에 UTF-8 500바이트까지 조회할 수 있습니다."
        }
        val encoded = URLEncoder.encode(request.sourceText, StandardCharsets.UTF_8.name())
        val body = fetch(URL("$ENDPOINT?q=$encoded&langpair=zh-CN%7Cko-KR"), cancellation)
        cancellation.throwIfCancelled()
        val root = JSONObject(body)
        val status = root.optInt("responseStatus", 0)
        if (status != HttpURLConnection.HTTP_OK) {
            val detail = root.optString("responseDetails").ifBlank { "응답 코드 $status" }
            throw MyMemoryTranslationException(detail)
        }
        val translated = root.optJSONObject("responseData")
            ?.optString("translatedText")
            ?.trim()
            .orEmpty()
        val entries = if (translated.isBlank()) {
            emptyList()
        } else {
            listOf(DictionaryLookupEntry(headword = request.sourceText, meanings = listOf(translated)))
        }
        return DictionaryLookupResponse(
            providerId = "mymemory-public",
            providerVersion = "get-api-2026-09-27",
            queryText = request.sourceText,
            entries = entries,
            resultType = LookupResultType.TRANSLATION,
            attributions = listOf(MYMEMORY_ATTRIBUTION),
            storagePolicy = LookupStoragePolicy(LookupRetention.DO_NOT_STORE),
        )
    }

    companion object {
        const val TERMS_URL = "https://mymemory.translated.net/terms-and-conditions"
        private const val ENDPOINT = "https://api.mymemory.translated.net/get"
        private const val MAX_QUERY_BYTES = 500
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val TIMEOUT_MILLIS = 8_000
        val MYMEMORY_ATTRIBUTION = LookupAttribution(
            displayName = "MyMemory by Translated",
            sourceUrl = "https://mymemory.translated.net/",
        )

        private fun fetchJson(url: URL, cancellation: LookupCancellation): String {
            val connection = url.openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WorldViewer/0.1")
                cancellation.throwIfCancelled()
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (bytes.size() + read > MAX_RESPONSE_BYTES) {
                            throw MyMemoryTranslationException("온라인 번역 응답이 허용 크기를 넘었습니다.")
                        }
                        bytes.write(buffer, 0, read)
                    }
                }
                if (status !in 200..299) {
                    throw MyMemoryTranslationException("온라인 번역 서버 응답 코드 $status")
                }
                bytes.toString(StandardCharsets.UTF_8.name())
            } finally {
                connection.disconnect()
            }
        }
    }
}

class MyMemoryTranslationException(message: String) : RuntimeException(message)
