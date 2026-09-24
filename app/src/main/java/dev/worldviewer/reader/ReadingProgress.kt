package dev.worldviewer.reader

object ReadingProgress {
    const val MAX = 1_000

    fun fromOffset(utf16Offset: Int, utf16Length: Int): Int {
        if (utf16Length <= 1) return 0
        val bounded = utf16Offset.coerceIn(0, utf16Length - 1)
        return (bounded.toLong() * MAX / (utf16Length - 1)).toInt()
    }

    fun toOffset(progress: Int, utf16Length: Int): Int {
        if (utf16Length <= 1) return 0
        val bounded = progress.coerceIn(0, MAX)
        return (bounded.toLong() * (utf16Length - 1) / MAX).toInt()
    }
}
