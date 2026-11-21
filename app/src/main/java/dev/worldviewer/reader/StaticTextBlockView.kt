package dev.worldviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

class StaticTextBlockView(context: Context) : View(context) {
    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 34, 31)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            19f,
            resources.displayMetrics,
        )
    }
    private var text: String = ""
    private var textLayout: StaticLayout? = null
    private var readerStyle = ReaderStyle.DEFAULT

    init {
        val density = resources.displayMetrics.density
        setPadding(
            (20 * density).roundToInt(),
            (8 * density).roundToInt(),
            (20 * density).roundToInt(),
            (8 * density).roundToInt(),
        )
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(block: TextBlock) {
        text = block.text
        contentDescription = text
        textLayout = null
        requestLayout()
        invalidate()
    }

    fun setReaderStyle(style: ReaderStyle) {
        if (readerStyle == style) return
        readerStyle = style
        textPaint.color = style.textColor()
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            style.fontSizeSp.toFloat(),
            resources.displayMetrics,
        )
        textLayout = null
        requestLayout()
        invalidate()
    }

    fun setCustomTypeface(typeface: Typeface?) {
        if (textPaint.typeface === typeface) return
        textPaint.typeface = typeface
        textLayout = null
        requestLayout()
        invalidate()
    }

    fun utf16OffsetFor(x: Float, y: Float): Int {
        val layout = textLayout ?: return 0
        return TextHitProbe.utf16Offset(layout, text, x, y, paddingLeft, paddingTop)
    }

    fun hitSamples(): List<HitSample> = textLayout?.let {
        TextHitProbe.samples(it, text, paddingLeft, paddingTop)
    }.orEmpty()

    @SuppressLint("WrongConstant") // Layout and LineBreaker expose the same API-23 break-strategy value.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        textLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(true)
            .setLineSpacing(
                10f * resources.displayMetrics.density,
                readerStyle.lineSpacingPercent / 100f,
            )
            .build()
        val desiredHeight = paddingTop + requireNotNull(textLayout).height + paddingBottom
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        textLayout?.draw(canvas)
        canvas.restore()
    }
}
