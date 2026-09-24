package dev.worldviewer.reader

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncodingDetectorTest {
    @Test
    fun `valid utf8 is the only automatic candidate`() {
        val candidates = TextEncodingDetector.candidates("大家好。".toByteArray(StandardCharsets.UTF_8))

        assertEquals(listOf("UTF-8"), candidates.map { it.encoding })
        assertTrue(candidates.single().automatic)
    }

    @Test
    fun `bomless utf16 offers the matching byte order with readable preview`() {
        val source = "江湖夜雨十年灯。"

        val little = TextEncodingDetector.candidates(source.toByteArray(StandardCharsets.UTF_16LE))
        val big = TextEncodingDetector.candidates(source.toByteArray(StandardCharsets.UTF_16BE))

        assertTrue(little.any { it.encoding == "UTF-16LE" && source in it.preview })
        assertTrue(big.any { it.encoding == "UTF-16BE" && source in it.preview })
        assertFalse(little.first { it.encoding == "UTF-16LE" }.automatic)
    }

    @Test
    fun `gb18030 and big5 expose readable matching previews for user choice`() {
        val simplified = "简体中文：龙门客栈。"
        val traditional = "繁體中文：龍門客棧。"

        val gb = TextEncodingDetector.candidates(simplified.toByteArray(Charset.forName("GB18030")))
        val big5 = TextEncodingDetector.candidates(traditional.toByteArray(Charset.forName("Big5")))

        assertTrue(gb.any { it.encoding == "GB18030" && simplified in it.preview })
        assertTrue(big5.any { it.encoding == "Big5" && traditional in it.preview })
        assertTrue(gb.none { it.automatic })
        assertTrue(big5.none { it.automatic })
    }
}
