package dev.worldviewer.lookup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class AzureTranslatorCredentials(val key: String, val region: String) {
    init {
        require(key.isNotBlank()) { "Azure Translator Key1이 필요합니다." }
        require(region.isNotBlank()) { "Azure Translator 지역이 필요합니다." }
    }
}

fun interface AzureTranslationTransport {
    fun translate(
        sourceText: String,
        credentials: AzureTranslatorCredentials,
        cancellation: LookupCancellation,
    ): String
}

/** Direct Chinese-to-Korean translation using the user's Azure Translator F0 resource. */
class AzureTranslationProvider(
    private val credentials: AzureTranslatorCredentials,
    private val transport: AzureTranslationTransport = AzureHttpTranslationTransport,
) : DictionaryLookupProvider {
    override fun lookup(
        request: DictionaryLookupRequest,
        cancellation: LookupCancellation,
    ): DictionaryLookupResponse {
        cancellation.throwIfCancelled()
        val codePoints = request.sourceText.codePointCount(0, request.sourceText.length)
        require(codePoints <= MAX_REQUEST_CHARACTERS) {
            "Azure 온라인 번역은 한 번에 50,000자까지 지원합니다."
        }
        val translated = transport.translate(request.sourceText, credentials, cancellation).trim()
        cancellation.throwIfCancelled()
        return DictionaryLookupResponse(
            providerId = "azure-translator-f0",
            providerVersion = "text-api-v3",
            queryText = request.sourceText,
            entries = if (translated.isBlank()) {
                emptyList()
            } else {
                listOf(DictionaryLookupEntry(request.sourceText, listOf(translated)))
            },
            resultType = LookupResultType.TRANSLATION,
            attributions = listOf(AZURE_ATTRIBUTION),
            storagePolicy = LookupStoragePolicy(LookupRetention.DO_NOT_STORE),
        )
    }

    companion object {
        const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/"
        private const val MAX_REQUEST_CHARACTERS = 50_000
        val AZURE_ATTRIBUTION = LookupAttribution(
            displayName = "Microsoft Azure Translator",
            sourceUrl = "https://azure.microsoft.com/products/ai-services/ai-translator/",
        )
    }
}

private object AzureHttpTranslationTransport : AzureTranslationTransport {
    private const val TRANSLATE_URL =
        "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=ko"
    private const val TIMEOUT_MILLIS = 10_000
    private const val MAX_RESPONSE_BYTES = 1024 * 1024

    override fun translate(
        sourceText: String,
        credentials: AzureTranslatorCredentials,
        cancellation: LookupCancellation,
    ): String {
        val connection = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", credentials.key)
            connection.setRequestProperty("Ocp-Apim-Subscription-Region", credentials.region)
            val requestBody = JSONArray().put(JSONObject().put("Text", sourceText)).toString()
                .toByteArray(StandardCharsets.UTF_8)
            cancellation.throwIfCancelled()
            connection.outputStream.use { it.write(requestBody) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = readLimited(stream, cancellation)
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "Azure Translator 응답 코드 $status" }
                throw AzureTranslationException(detail, status)
            }
            val translations = JSONArray(response)
                .getJSONObject(0)
                .getJSONArray("translations")
            translations.getJSONObject(0).getString("text")
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(
        input: java.io.InputStream?,
        cancellation: LookupCancellation,
    ): String {
        val bytes = ByteArrayOutputStream()
        input?.use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                cancellation.throwIfCancelled()
                val read = stream.read(buffer)
                if (read < 0) break
                if (bytes.size() + read > MAX_RESPONSE_BYTES) {
                    throw AzureTranslationException("Azure Translator 응답이 허용 크기를 넘었습니다.")
                }
                bytes.write(buffer, 0, read)
            }
        }
        return bytes.toString(StandardCharsets.UTF_8.name())
    }
}

class AzureTranslationException(message: String, val httpStatus: Int? = null) : RuntimeException(message)
