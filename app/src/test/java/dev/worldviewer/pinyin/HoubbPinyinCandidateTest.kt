package dev.worldviewer.pinyin

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum
import com.github.houbb.pinyin.util.PinyinHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records the observed behavior of an evaluation-only pinyin candidate.
 *
 * These tests do not approve the candidate for shipping. They keep both the useful baseline and
 * known failures visible while the production engine and its data source remain undecided.
 */
class HoubbPinyinCandidateTest {
    @Test
    fun `tone marks and common simplified polyphones match the golden fixture`() {
        val fixture = linkedMapOf(
            "我爱中文" to "wǒ ài zhōng wén",
            "重庆火锅" to "chóng qìng huǒ guō",
            "音乐" to "yīn yuè",
            "快乐" to "kuài lè",
            "重量" to "zhòng liàng",
            "重复" to "chóng fù",
            "银行" to "yín háng",
            "行走" to "xíng zǒu",
            "长大" to "zhǎng dà",
            "长江" to "cháng jiāng",
        )

        fixture.forEach { (hanzi, expected) ->
            assertEquals(hanzi, expected, convert(hanzi))
        }
    }

    @Test
    fun `context and traditional failures stay explicit`() {
        val failures = listOf(
            CandidateFailure("银行行长", "yín háng háng zhǎng", "yín háng xíng zhǎng"),
            CandidateFailure("長樂", "cháng lè", "zhǎng lè"),
            CandidateFailure("單于", "chán yú", "dān yú"),
        )

        failures.forEach { failure ->
            val actual = convert(failure.hanzi)
            assertEquals(failure.hanzi, failure.observed, actual)
            assertNotEquals(failure.hanzi, failure.expected, actual)
        }
    }

    @Test
    fun `raw conversion does not preserve supplementary Unicode characters`() {
        val actual = convert("A中🙂𠮷")

        assertTrue(actual.startsWith("A zhōng "))
        assertFalse(actual.contains("🙂"))
        assertFalse(actual.contains("𠮷"))
    }

    private fun convert(text: String): String =
        PinyinHelper.toPinyin(text, PinyinStyleEnum.DEFAULT)

    private data class CandidateFailure(
        val hanzi: String,
        val expected: String,
        val observed: String,
    )
}
