package dev.worldviewer.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.Layout
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.worldviewer.pinyin.PinyinReading
import dev.worldviewer.selection.SelectionRange

class TextBlockAdapter(
    private val blockSource: TextBlockSource,
    private val renderer: BlockRenderer = BlockRenderer.TEXT_VIEW,
    private var readerStyle: ReaderStyle = ReaderStyle.DEFAULT,
) : RecyclerView.Adapter<TextBlockAdapter.BlockViewHolder>() {
    constructor(
        blocks: List<TextBlock>,
        renderer: BlockRenderer = BlockRenderer.TEXT_VIEW,
        readerStyle: ReaderStyle = ReaderStyle.DEFAULT,
    ) : this(InMemoryTextBlockSource(blocks), renderer, readerStyle)

    private var selection: SelectionRange? = null
    private var highlights: List<SelectionRange> = emptyList()
    private var completedPinyinSelection: SelectionRange? = null
    private var pinyinMode = PinyinDisplayMode.ORIGINAL
    private var pinyinByPosition: Map<Int, List<PinyinReading>> = emptyMap()
    private val pinyinOverrides = mutableMapOf<Int, String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("WrongConstant") // Layout and LineBreaker expose the same API-23 break-strategy value.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockViewHolder {
        if (renderer == BlockRenderer.STATIC_LAYOUT) {
            return BlockViewHolder(
                StaticTextBlockView(parent.context).apply {
                    setReaderStyle(readerStyle)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }
        val density = parent.resources.displayMetrics.density
        val horizontal = (20 * density).toInt()
        val top = (18 * density).toInt()
        val bottom = (10 * density).toInt()
        val textView = PinyinTextView(parent.context).apply {
            applyReaderStyle(readerStyle, density)
            includeFontPadding = true
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setPadding(horizontal, top, horizontal, bottom)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return BlockViewHolder(textView)
    }

    override fun onBindViewHolder(holder: BlockViewHolder, position: Int) {
        holder.applyReaderStyle(readerStyle)
        val block = blockSource.cachedBlock(position)
        if (block == null) {
            holder.bindLoading()
            requestBlockAndNeighbors(position)
            return
        }
        holder.bind(
            block,
            selection,
            highlights,
            completedPinyinSelection,
            pinyinMode,
            pinyinByPosition[position],
            pinyinOverrides,
            readerStyle,
        )
    }

    override fun getItemCount(): Int = blockSource.blockCount

    fun blockAt(position: Int): TextBlock =
        blockSource.cachedBlock(position) ?: error("Block $position is not cached")

    fun blockAtOrNull(position: Int): TextBlock? = blockSource.cachedBlock(position)

    fun cachedBlockAt(globalUtf16: Int): TextBlock? {
        val position = cachedPositionFor(globalUtf16)
        return if (position < 0) null else blockSource.cachedBlock(position)
    }

    fun readRange(startUtf16: Int, endUtf16: Int, onComplete: (Result<String>) -> Unit) {
        blockSource.readRange(startUtf16, endUtf16, onComplete)
    }

    fun requestBlockContaining(globalUtf16: Int, onAvailable: (Int) -> Unit) {
        blockSource.requestBlockContaining(globalUtf16) { position ->
            if (position in 0 until itemCount) notifyItemChanged(position, BLOCK_PAYLOAD)
            onAvailable(position)
        }
    }

    fun characterAt(globalUtf16: Int): String? {
        val block = cachedBlockAt(globalUtf16) ?: return null
        val local = globalUtf16 - block.startUtf16
        if (local !in block.text.indices) return null
        val start = if (
            local > 0 &&
            Character.isLowSurrogate(block.text[local]) &&
            Character.isHighSurrogate(block.text[local - 1])
        ) {
            local - 1
        } else {
            local
        }
        val end = start + Character.charCount(block.text.codePointAt(start))
        return block.text.substring(start, end)
    }

    fun close() = blockSource.close()

    fun hitSamples(view: View): List<HitSample> = when (view) {
        is TextView -> view.layout?.let {
            TextHitProbe.samples(it, view.text.toString(), view.totalPaddingLeft, view.totalPaddingTop)
        }.orEmpty()
        is StaticTextBlockView -> view.hitSamples()
        else -> emptyList()
    }

    fun globalUtf16OffsetFor(position: Int, view: View, x: Float, y: Float): Int? {
        val localOffset = when (view) {
            is TextView -> view.layout?.let {
                TextHitProbe.utf16Offset(
                    it,
                    view.text.toString(),
                    x,
                    y,
                    view.totalPaddingLeft,
                    view.totalPaddingTop,
                )
            }
            is StaticTextBlockView -> view.utf16OffsetFor(x, y)
            else -> null
        } ?: return null
        val block = blockSource.cachedBlock(position) ?: return null
        return block.startUtf16 + localOffset
    }

    fun setSelection(range: SelectionRange?) {
        if (selection == range) return
        selection = range
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    fun setHighlights(ranges: List<SelectionRange>) {
        if (highlights == ranges) return
        highlights = ranges
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    fun setPinyinMode(mode: PinyinDisplayMode) {
        if (pinyinMode == mode) return
        pinyinMode = mode
        notifyItemRangeChanged(0, itemCount, PINYIN_PAYLOAD)
    }

    fun setReaderStyle(style: ReaderStyle) {
        if (readerStyle == style) return
        readerStyle = style
        notifyItemRangeChanged(0, itemCount, STYLE_PAYLOAD)
    }

    fun setCompletedPinyinSelection(range: SelectionRange?) {
        if (completedPinyinSelection == range) return
        completedPinyinSelection = range
        if (pinyinMode == PinyinDisplayMode.SELECTION) {
            notifyItemRangeChanged(0, itemCount, PINYIN_PAYLOAD)
        }
    }

    fun setPinyinReadings(readings: Map<Int, List<PinyinReading>>) {
        if (pinyinByPosition == readings) return
        val changed = pinyinByPosition.keys + readings.keys
        pinyinByPosition = readings
        if (pinyinMode == PinyinDisplayMode.ORIGINAL) return
        changed.filter { it in 0 until itemCount }.forEach { position ->
            notifyItemChanged(position, PINYIN_PAYLOAD)
        }
    }

    fun pronunciationAt(globalUtf16: Int): String? {
        pinyinOverrides[globalUtf16]?.let { return it }
        val position = cachedPositionFor(globalUtf16)
        if (position < 0) return null
        val block = blockSource.cachedBlock(position) ?: return null
        val local = globalUtf16 - block.startUtf16
        return pinyinByPosition[position]
            ?.firstOrNull { local in it.startUtf16 until it.endUtf16 }
            ?.pronunciation
    }

    fun setPinyinOverride(globalUtf16: Int, pronunciation: String?) {
        val normalized = pronunciation?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) pinyinOverrides.remove(globalUtf16) else pinyinOverrides[globalUtf16] = normalized
        val position = cachedPositionFor(globalUtf16)
        if (position >= 0) notifyItemChanged(position, PINYIN_PAYLOAD)
    }

    fun restorePinyinOverrides(overrides: Map<Int, String>) {
        pinyinOverrides.clear()
        pinyinOverrides.putAll(overrides.filterKeys { it >= 0 })
        if (pinyinOverrides.isNotEmpty()) notifyItemRangeChanged(0, itemCount, PINYIN_PAYLOAD)
    }

    fun pinyinOverrides(): Map<Int, String> = pinyinOverrides.toMap()

    private fun requestBlockAndNeighbors(position: Int) {
        val indexed = blockSource as? IndexedTextBlockSource
        if (indexed != null) {
            indexed.prefetch(position) { loaded ->
                if (loaded in 0 until itemCount) notifyItemChanged(loaded, BLOCK_PAYLOAD)
            }
        } else {
            blockSource.requestBlock(position) { loaded ->
                if (loaded in 0 until itemCount) notifyItemChanged(loaded, BLOCK_PAYLOAD)
            }
        }
    }

    private fun cachedPositionFor(globalUtf16: Int): Int =
        blockSource.cachedPositionFor(globalUtf16)

    class BlockViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        fun applyReaderStyle(style: ReaderStyle) {
            when (val view = itemView) {
                is PinyinTextView -> view.applyReaderStyle(style, view.resources.displayMetrics.density)
                is TextView -> {
                    view.setTextColor(style.textColor())
                    view.textSize = style.fontSizeSp.toFloat()
                }
                is StaticTextBlockView -> view.setReaderStyle(style)
            }
        }

        fun bindLoading() {
            when (val view = itemView) {
                is PinyinTextView -> {
                    view.bindRuby(0, null, PinyinDisplayMode.ORIGINAL, null, emptyMap())
                    view.text = "본문 불러오는 중…"
                }
                is TextView -> view.text = "본문 불러오는 중…"
                is StaticTextBlockView -> view.bind(TextBlock(-1, 0, "본문 불러오는 중…"))
            }
        }

        fun bind(
            block: TextBlock,
            selection: SelectionRange?,
            highlights: List<SelectionRange>,
            completedPinyinSelection: SelectionRange?,
            pinyinMode: PinyinDisplayMode,
            readings: List<PinyinReading>?,
            overrides: Map<Int, String>,
            readerStyle: ReaderStyle,
        ) {
            when (val view = itemView) {
                is PinyinTextView -> {
                    view.text = block.displayText(selection, highlights, readerStyle)
                    view.bindRuby(
                        blockStartUtf16 = block.startUtf16,
                        readings = readings,
                        displayMode = pinyinMode,
                        completedSelection = completedPinyinSelection,
                        overrides = overrides,
                    )
                }
                is TextView -> view.text = block.displayText(selection, highlights, readerStyle)
                is StaticTextBlockView -> view.bind(block)
            }
        }

        private fun TextBlock.displayText(
            selection: SelectionRange?,
            highlights: List<SelectionRange>,
            readerStyle: ReaderStyle,
        ): CharSequence = SpannableString(text).apply {
            highlights.forEach { highlight ->
                if (highlight.endUtf16 > startUtf16 && highlight.startUtf16 < endUtf16) {
                    setSpan(
                        BackgroundColorSpan(readerStyle.highlightColor()),
                        (highlight.startUtf16 - startUtf16).coerceAtLeast(0),
                        (highlight.endUtf16 - startUtf16).coerceAtMost(text.length),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            if (selection != null && selection.endUtf16 > startUtf16 && selection.startUtf16 < endUtf16) {
                val localStart = (selection.startUtf16 - startUtf16).coerceAtLeast(0)
                val localEnd = (selection.endUtf16 - startUtf16).coerceAtMost(text.length)
                setSpan(
                    BackgroundColorSpan(readerStyle.selectionColor()),
                    localStart,
                    localEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private companion object {
        const val SELECTION_PAYLOAD = "selection"
        const val PINYIN_PAYLOAD = "pinyin"
        const val BLOCK_PAYLOAD = "block"
        const val STYLE_PAYLOAD = "style"
    }
}

private fun TextView.applyReaderStyle(style: ReaderStyle, density: Float) {
    setTextColor(style.textColor())
    textSize = style.fontSizeSp.toFloat()
    // Keep ruby reserve in original mode too, so toggling pinyin does not move source lines.
    setLineSpacing(10 * density, style.lineSpacingPercent / 100f)
}

internal fun ReaderStyle.textColor(): Int = when (theme) {
    ReaderTheme.LIGHT -> Color.rgb(35, 34, 31)
    ReaderTheme.DARK -> Color.rgb(232, 230, 224)
}

internal fun ReaderStyle.selectionColor(): Int = when (theme) {
    ReaderTheme.LIGHT -> Color.rgb(255, 224, 130)
    ReaderTheme.DARK -> Color.rgb(112, 85, 35)
}

internal fun ReaderStyle.highlightColor(): Int = when (theme) {
    ReaderTheme.LIGHT -> Color.rgb(255, 235, 120)
    ReaderTheme.DARK -> Color.rgb(112, 91, 28)
}

enum class PinyinDisplayMode {
    ORIGINAL,
    ALL,
    SELECTION,
}

enum class BlockRenderer(val launchValue: String) {
    TEXT_VIEW("text"),
    STATIC_LAYOUT("static"),
}
