package dev.worldviewer.lookup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekChapterTranslatorTest {
    @Test fun `chapter sections preserve all characters and reject oversized source`() {
        val source = "第一章\n" + "第一段。\n".repeat(180) + "末尾😀"
        val sections = DeepSeekChapterTranslator.sections(source)
        assertEquals(source, sections.joinToString(""))
        assertTrue(sections.size > 1)
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekChapterTranslator.sections("中".repeat(DeepSeekChapterTranslator.MAX_CHAPTER_UTF16 + 1))
        }
    }

    @Test fun `request sends one chapter with disabled thinking and pairs all returned sections`() {
        var authorization = ""
        var request = JSONObject()
        val translator = DeepSeekChapterTranslator { key, payload ->
            authorization = key
            request = JSONObject(payload)
            """{"choices":[{"finish_reason":"stop","message":{"content":"{\"translations\":[\"안녕\"]}"}}],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""
        }
            val result = translator.translate("test-secret", "你好")
            assertEquals("test-secret", authorization)
            assertEquals("deepseek-flash", request.getString("model"))
            assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
            assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("Example JSON:"))
            assertEquals("你好", result.sections.single().source)
            assertEquals("안녕", result.sections.single().translation)
            assertEquals(12, result.inputTokens)
            assertEquals(3, result.outputTokens)
    }

    @Test fun `truncated or missing translations are rejected`() {
        val truncated = DeepSeekChapterTranslator { _, _ ->
            """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}"""
        }
        assertThrows(IllegalStateException::class.java) { truncated.translate("key", "你好") }
        val missing = DeepSeekChapterTranslator { _, _ ->
            """{"choices":[{"finish_reason":"stop","message":{"content":"{\"translations\":[]}"}}]}"""
        }
        assertThrows(IllegalStateException::class.java) { missing.translate("key", "你好") }
        val blank = DeepSeekChapterTranslator { _, _ ->
            """{"choices":[{"finish_reason":"stop","message":{"content":"   "}}]}"""
        }
        assertTrue(assertThrows(IllegalStateException::class.java) { blank.translate("key", "你好") }
            .message!!.contains("응답이 비어"))
    }

    @Test fun `cancellation prevents queued work and discards late response`() {
        var calls = 0
        val queued = DeepSeekChapterTranslator { _, _ -> calls++; "" }
        queued.cancel()
        assertThrows(IllegalStateException::class.java) { queued.translate("key", "你好") }
        assertEquals(0, calls)

        lateinit var active: DeepSeekChapterTranslator
        active = DeepSeekChapterTranslator { _, _ ->
            active.cancel()
            """{"choices":[{"finish_reason":"stop","message":{"content":"{\"translations\":[\"안녕\"]}"}}]}"""
        }
        assertThrows(IllegalStateException::class.java) { active.translate("key", "你好") }
    }
}
