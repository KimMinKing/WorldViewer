package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedMeaningDisplayTest {
    private val document = "a".repeat(64)

    @Test
    fun `word meanings share one label and a short sentence also has a preview`() {
        val values = listOf(
            meaning(1, "夜雨", "밤비", 10, 12),
            meaning(2, "夜雨", "밤에 내리는 비", 10, 12),
            meaning(3, "江湖夜雨十年灯。", "강호의 밤비", 8, 17),
        )

        val displays = savedMeaningDisplays(values)

        assertEquals(SavedMeaningDisplay(8, 17, "강호의 밤비"), displays[0])
        assertEquals(SavedMeaningDisplay(10, 12, "밤비 +1"), displays[1])
    }

    @Test
    fun `book wide meaning has no source anchor and a long passage keeps a source mark`() {
        val sentence = meaning(1, "大家好。", "안녕하세요", 0, 4)
        val bookWide = sentence.copy(id = "00000002-1111-1111-1111-111111111111",
            scope = MeaningScope.BOOK, rangeStartUtf16 = null, rangeEndUtf16 = null)
        val passage = meaning(3, "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往", "긴 구절", 10, 30)

        val displays = savedMeaningDisplays(listOf(sentence, bookWide, passage))

        assertEquals("안녕하세요", displays[0].label)
        assertNull(displays[1].label)
    }

    @Test
    fun `nearby saved meanings all receive lanes and right edge words stay visible`() {
        val placements = layoutMeaningAnchors(
            listOf(
                MeaningAnchor(0, 10f, "첫 뜻", 45f),
                MeaningAnchor(0, 12f, "나중에 저장한 뜻", 80f),
                MeaningAnchor(0, 98f, "끝 단어", 40f),
                MeaningAnchor(1, 15f, "다음 줄", 45f),
            ),
            left = 0f,
            right = 100f,
        )

        assertEquals(listOf(0, 1, 2, 0), placements.map(MeaningPlacement::lane))
        assertEquals(listOf(10f, 12f, 60f, 15f), placements.map(MeaningPlacement::x))
        assertEquals(4, placements.size)
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
