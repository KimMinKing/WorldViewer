package dev.worldviewer.reader

data class ChapterTranslationRange(val title: String, val startUtf16: Int, val endUtf16: Int) {
    val lengthUtf16: Int get() = endUtf16 - startUtf16
}

object ChapterTranslationRangeSelector {
    fun select(
        chapters: List<Pair<String, Int>>,
        currentUtf16: Int,
        documentLengthUtf16: Int,
        maxChapterUtf16: Int,
    ): ChapterTranslationRange {
        require(documentLengthUtf16 > 0 && currentUtf16 in 0 until documentLengthUtf16)
        require(maxChapterUtf16 > 0)
        check(chapters.isNotEmpty()) {
            "회차 제목을 찾지 못했습니다. EPUB 목차 또는 '제…장/회' 제목이 필요합니다."
        }
        check(chapters.all { it.second in 0 until documentLengthUtf16 } &&
            chapters.zipWithNext().all { (first, second) -> first.second < second.second }) {
            "회차 경계가 올바르지 않아 전송하지 않았습니다."
        }
        val current = chapters.indexOfLast { it.second <= currentUtf16 }
        check(current >= 0) { "현재 위치는 첫 회차 앞입니다. 회차 안으로 이동한 뒤 다시 시도하세요." }
        val start = chapters[current].second
        val end = chapters.getOrNull(current + 1)?.second ?: documentLengthUtf16
        check(end - start <= maxChapterUtf16) {
            "현재 회차는 ${end - start}자입니다. 최대 ${maxChapterUtf16}자까지 지원하며 원문을 잘라 전송하지 않습니다."
        }
        return ChapterTranslationRange(chapters[current].first, start, end)
    }
}
