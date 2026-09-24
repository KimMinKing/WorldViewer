package dev.worldviewer.reader

import android.text.Layout

data class HitSample(
    val line: Int,
    val horizontalFraction: Float,
    val utf16Offset: Int,
)

object TextHitProbe {
    fun utf16Offset(
        layout: Layout,
        text: String,
        x: Float,
        y: Float,
        paddingLeft: Int,
        paddingTop: Int,
    ): Int {
        val localX = (x - paddingLeft).coerceIn(0f, layout.width.toFloat())
        val localY = (y - paddingTop).toInt().coerceIn(0, layout.height)
        val line = layout.getLineForVertical(localY)
        val rawOffset = layout.getOffsetForHorizontal(line, localX).coerceIn(0, text.length)
        return rawOffset.withoutSplitSurrogate(text)
    }

    fun samples(
        layout: Layout,
        text: String,
        paddingLeft: Int,
        paddingTop: Int,
    ): List<HitSample> = buildList {
        val lastLine = minOf(layout.lineCount - 1, 2)
        if (lastLine < 0) return@buildList
        for (line in 0..lastLine) {
            val y = paddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            for (fraction in SAMPLE_FRACTIONS) {
                val x = paddingLeft + layout.width * fraction
                add(
                    HitSample(
                        line = line,
                        horizontalFraction = fraction,
                        utf16Offset = utf16Offset(layout, text, x, y, paddingLeft, paddingTop),
                    ),
                )
            }
        }
    }

    private fun Int.withoutSplitSurrogate(text: String): Int =
        if (
            this in 1 until text.length &&
            Character.isHighSurrogate(text[this - 1]) &&
            Character.isLowSurrogate(text[this])
        ) {
            this - 1
        } else {
            this
        }

    private val SAMPLE_FRACTIONS = floatArrayOf(0.25f, 0.5f, 0.75f)
}
