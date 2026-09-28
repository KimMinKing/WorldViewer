package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedMeaningDisplayTest {
    private val document = "a".repeat(64)

    @Test
    fun `word meanings share one label while a sentence keeps only its source mark`() {
        val values = listOf(
            meaning(1, "夜雨", "밤비", 10, 12),
            meaning(2, "夜雨", "밤에 내리는 비", 10, 12),
            meaning(3, "江湖夜雨十年灯。", "강호의 밤비", 8, 17),
        )

        val displays = savedMeaningDisplays(values)

        assertEquals(SavedMeaningDisplay(8, 17, null), displays[0])
        assertEquals(SavedMeaningDisplay(10, 12, "밤비 +1"), displays[1])
    }

    @Test
    fun `book wide meaning has no source anchor and punctuation selection is not a word`() {
        val sentence = meaning(1, "大家好。", "안녕하세요", 0, 4)
        val bookWide = sentence.copy(id = "00000002-1111-1111-1111-111111111111",
            scope = MeaningScope.BOOK, rangeStartUtf16 = null, rangeEndUtf16 = null)

        val display = savedMeaningDisplays(listOf(sentence, bookWide)).single()

        assertNull(display.label)
    }

    private fun meaning(number: Int, source: String, definition: String, start: Int, end: Int) = UserMeaning(
        id = number.toString().padStart(8, '0') + "-1111-1111-1111-111111111111",
        sourceText = source,
        meaning = definition,
        scope = MeaningScope.RANGE,
        documentSha256 = document,
        rangeStartUtf16 = start,
        rangeEndUtf16 = end,
        createdAtEpochMillis = number.toLong(),
        updatedAtEpochMillis = number.toLong(),
    )
}
