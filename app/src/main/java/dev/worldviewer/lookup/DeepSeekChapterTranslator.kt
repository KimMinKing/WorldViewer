package dev.worldviewer.lookup

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Explicit, single-chapter trial. Neither source nor response is persisted here. */
class DeepSeekChapterTranslator(private val transport: ((String, String) -> String)? = null) {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }
    data class Section(val source: String, val translation: String)
    data class Result(val sections: List<Section>, val inputTokens: Int, val outputTokens: Int)

    fun translate(apiKey: String, source: String): Result {
        check(!cancelled) { "번역을 취소했습니다." }
        require(apiKey.isNotBlank()) { "DeepSeek API 키가 필요합니다." }
        val parts = sections(source)
        val input = JSONArray().apply { parts.forEach { put(it) } }
        val payload = JSONObject()
            .put("model", "deepseek-flash")
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("max_tokens", 24000)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "Translate each Chinese novel passage into natural Korean. Preserve names and meaning; do not omit passages. Return only a JSON object with a translations array of strings, in the same order and count. Example JSON: {\"translations\":[\"Korean passage 1\",\"Korean passage 2\"]}. No notes."))
                .put(JSONObject().put("role", "user").put("content", input.toString())))
        val body = (transport ?: ::post)(apiKey, payload.toString())
        check(!cancelled) { "번역을 취소했습니다." }
        val json = JSONObject(body)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason") == "stop") { "번역 응답이 끝까지 생성되지 않았습니다. 비용이 청구됐을 수 있습니다." }
        val content = choice.getJSONObject("message").getString("content")
        check(content.isNotBlank()) { "번역 응답이 비어 있습니다. 비용이 청구됐을 수 있습니다. 다시 요청하기 전에 잔액을 확인하세요." }
        val translated = JSONObject(content).getJSONArray("translations")
        check(translated.length() == parts.size) { "번역 구간 수가 원문과 다릅니다. 비용이 청구됐을 수 있습니다." }
        val paired = parts.indices.map { index ->
            Section(parts[index], translated.getString(index).trim().also {
                check(it.isNotEmpty()) { "빈 번역 구간이 있습니다. 비용이 청구됐을 수 있습니다." }
            })
        }
        val usage = json.optJSONObject("usage")
        return Result(paired, usage?.optInt("prompt_tokens") ?: 0, usage?.optInt("completion_tokens") ?: 0)
    }

    private fun post(apiKey: String, payload: String): String {
            val active = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection = active
            try {
                check(!cancelled) { "번역을 취소했습니다." }
                active.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                check(!cancelled) { "번역을 취소했습니다." }
                if (active.responseCode !in 200..299) {
                    throw IllegalStateException("DeepSeek 요청 실패 (HTTP ${active.responseCode}). 키·잔액·네트워크를 확인하세요.")
                }
                return active.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection = null
                active.disconnect()
            }
    }

    companion object {
        const val MAX_CHAPTER_UTF16 = 15_000
        private const val SECTION_UTF16 = 600
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

        fun sections(source: String): List<String> {
            require(source.isNotBlank() && source.length <= MAX_CHAPTER_UTF16) {
                "회차는 1~${MAX_CHAPTER_UTF16}자여야 합니다. 긴 회차는 아직 지원하지 않습니다."
            }
            val result = ArrayList<String>()
            var start = 0
            while (start < source.length) {
                var end = minOf(start + SECTION_UTF16, source.length)
                if (end < source.length) {
                    val breakAt = source.lastIndexOf('\n', end - 1)
                    if (breakAt > start + SECTION_UTF16 / 2) end = breakAt + 1
                    else if (Character.isHighSurrogate(source[end - 1]) && Character.isLowSurrogate(source[end])) end--
                }
                result += source.substring(start, end)
                start = end
            }
            return result
        }
    }
}
