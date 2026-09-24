package dev.worldviewer.reader

import java.util.Locale

data class AutoBookCover(
    val colorArgb: Int,
    val initial: String,
) {
    companion object {
        private val COLORS = intArrayOf(
            0xff2f6f62.toInt(),
            0xff875f3a.toInt(),
            0xff5f668f.toInt(),
            0xff8b4f62.toInt(),
            0xff47708b.toInt(),
            0xff6d7040.toInt(),
        )

        fun from(bookId: String, displayName: String): AutoBookCover {
            val hash = bookId.take(8).toLongOrNull(16) ?: displayName.hashCode().toLong().and(0xffffffffL)
            val title = displayName.trim().substringBeforeLast('.', displayName.trim()).trim()
            val codePoint = title.codePoints().filter { !Character.isWhitespace(it) }.findFirst().orElse('책'.code)
            val initial = String(Character.toChars(codePoint)).uppercase(Locale.ROOT)
            return AutoBookCover(COLORS[(hash % COLORS.size).toInt()], initial)
        }
    }
}
