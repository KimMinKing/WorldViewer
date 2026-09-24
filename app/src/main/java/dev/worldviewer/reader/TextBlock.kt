package dev.worldviewer.reader

data class TextBlock(
    val id: Long,
    val startUtf16: Int,
    val text: String,
) {
    val endUtf16: Int
        get() = startUtf16 + text.length
}
