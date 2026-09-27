package dev.worldviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView
import dev.worldviewer.pinyin.PinyinReading
import dev.worldviewer.selection.SelectionRange

/** Draws ruby after the source layout is complete, so pinyin never changes source line breaks. */
// This intentionally uses the platform TextView; changing it would invalidate renderer measurements.
@SuppressLint("AppCompatCustomView")
class PinyinTextView(context: Context) : TextView(context) {
    private val rubyPaint = Paint()
    private var blockStartUtf16 = 0
    private var readings: List<PinyinReading> = emptyList()
    private var displayMode = PinyinDisplayMode.ORIGINAL
    private var completedSelection: SelectionRange? = null
    private var overrides: Map<Int, String> = emptyMap()

    fun bindRuby(
        blockStartUtf16: Int,
        readings: List<PinyinReading>?,
        displayMode: PinyinDisplayMode,
        completedSelection: SelectionRange?,
        overrides: Map<Int, String>,
    ) {
        this.blockStartUtf16 = blockStartUtf16
        this.readings = readings.orEmpty()
        this.displayMode = displayMode
        this.completedSelection = completedSelection
        this.overrides = overrides
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayMode == PinyinDisplayMode.ORIGINAL) return
        val textLayout = layout ?: return

        readings.forEach { reading ->
            val globalStart = blockStartUtf16 + reading.startUtf16
            val globalEnd = blockStartUtf16 + reading.endUtf16
            val pronunciation = overrides[globalStart] ?: reading.pronunciation ?: return@forEach
            val show = displayMode == PinyinDisplayMode.ALL ||
                completedSelection?.let {
                    it.startUtf16 < globalEnd && it.endUtf16 > globalStart
                } == true
            if (!show || reading.startUtf16 !in 0 until text.length) return@forEach

            val line = textLayout.getLineForOffset(reading.startUtf16)
            val sourceWidth = paint.measureText(text, reading.startUtf16, reading.endUtf16)
            configureRubyPaint(sourceWidth, pronunciation)
            val rubyWidth = rubyPaint.measureText(pronunciation)
            val sourceX = totalPaddingLeft + textLayout.getPrimaryHorizontal(reading.startUtf16)
            val sourceBaseline = totalPaddingTop + textLayout.getLineBaseline(line)
            val rubyBaseline = sourceBaseline + paint.fontMetrics.ascent -
                rubyPaint.fontMetrics.descent - paint.textSize * VERTICAL_GAP_EM
            canvas.drawText(
                pronunciation,
                sourceX + (sourceWidth - rubyWidth) / 2f,
                rubyBaseline,
                rubyPaint,
            )
        }
    }

    private fun configureRubyPaint(sourceWidth: Float, pronunciation: String) {
        rubyPaint.set(paint)
        rubyPaint.textSize = paint.textSize * RUBY_TEXT_SCALE
        val naturalWidth = rubyPaint.measureText(pronunciation)
        val availableWidth = sourceWidth * RUBY_WIDTH_FRACTION
        if (naturalWidth > availableWidth && naturalWidth > 0f) {
            rubyPaint.textScaleX *= availableWidth / naturalWidth
        }
    }

    private companion object {
        const val RUBY_TEXT_SCALE = 0.48f
        const val RUBY_WIDTH_FRACTION = 0.92f
        const val VERTICAL_GAP_EM = 0.08f
    }
}
