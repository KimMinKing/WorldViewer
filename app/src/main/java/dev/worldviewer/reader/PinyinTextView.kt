package dev.worldviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView
import dev.worldviewer.pinyin.PinyinReading
import dev.worldviewer.selection.SelectionRange

/** Draws ruby after the source layout is complete, so pinyin never changes source line breaks. */
// This intentionally uses the platform TextView; changing it would invalidate renderer measurements.
@SuppressLint("AppCompatCustomView")
class PinyinTextView(context: Context) : TextView(context) {
    private val rubyPaint = Paint()
    private val meaningPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var blockStartUtf16 = 0
    private var readings: List<PinyinReading> = emptyList()
    private var displayMode = PinyinDisplayMode.ORIGINAL
    private var completedSelection: SelectionRange? = null
    private var overrides: Map<Int, String> = emptyMap()
    private var savedMeanings: List<SavedMeaningDisplay> = emptyList()
    private var showSavedMeanings = false

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

    fun bindMeanings(blockStartUtf16: Int, meanings: List<SavedMeaningDisplay>, show: Boolean) {
        this.blockStartUtf16 = blockStartUtf16
        savedMeanings = meanings
        showSavedMeanings = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val textLayout = layout ?: return

        if (showSavedMeanings) drawSavedMeanings(canvas, textLayout)
        if (displayMode == PinyinDisplayMode.ORIGINAL) return

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

    private fun drawSavedMeanings(canvas: Canvas, textLayout: android.text.Layout) {
        meaningPaint.set(paint)
        meaningPaint.isAntiAlias = true
        meaningPaint.textSize = maxOf(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics),
            paint.textSize * 0.60f,
        )
        meaningPaint.alpha = 190
        val gap = paint.textSize * 0.08f
        val laneHeight = paint.textSize * 0.9f
        val anchors = savedMeanings.mapNotNull { saved ->
            val label = saved.label ?: return@mapNotNull null
            val start = saved.startUtf16 - blockStartUtf16
            if (start !in 0 until text.length) return@mapNotNull null
            val line = textLayout.getLineForOffset(start)
            MeaningAnchor(line, totalPaddingLeft + textLayout.getPrimaryHorizontal(start),
                label, meaningPaint.measureText(label))
        }
        val rightLimit = totalPaddingLeft + textLayout.width.toFloat()
        layoutMeaningAnchors(anchors, totalPaddingLeft.toFloat(), rightLimit).forEach { placement ->
            val visible = TextUtils.ellipsize(placement.label, meaningPaint, rightLimit - placement.x,
                TextUtils.TruncateAt.END)
            val sourceBaseline = totalPaddingTop + textLayout.getLineBaseline(placement.line)
            val meaningBaseline = sourceBaseline + paint.fontMetrics.descent + gap -
                meaningPaint.fontMetrics.ascent + placement.lane * laneHeight
            canvas.drawText(visible.toString(), placement.x, meaningBaseline, meaningPaint)
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
