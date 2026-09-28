package dev.worldviewer.reader

enum class ReaderTapAction { PREVIOUS_PAGE, TOGGLE_CONTROLS, NEXT_PAGE }

object ReaderTapZones {
    fun action(y: Float, height: Int): ReaderTapAction {
        if (height <= 0) return ReaderTapAction.TOGGLE_CONTROLS
        val fraction = (y / height).coerceIn(0f, 1f)
        return when {
            fraction < 0.4f -> ReaderTapAction.PREVIOUS_PAGE
            fraction > 0.6f -> ReaderTapAction.NEXT_PAGE
            else -> ReaderTapAction.TOGGLE_CONTROLS
        }
    }
}
