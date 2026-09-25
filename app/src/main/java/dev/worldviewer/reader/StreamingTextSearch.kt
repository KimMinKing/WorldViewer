package dev.worldviewer.reader

import java.util.concurrent.atomic.AtomicBoolean

data class TextSearchResult(
    val utf16Offset: Int,
    val preview: String,
)

data class TextSearchProgress(
    val matches: List<TextSearchResult>,
    val scannedBlocks: Int,
)

data class TextSearchSummary(
    val resultCount: Int,
    val scannedBlocks: Int,
    val cancelled: Boolean,
    val truncated: Boolean,
)

class TextSearchCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

object StreamingTextSearch {
    const val MAX_QUERY_UTF16 = 100
    const val MAX_RESULTS = 200
    private const val BATCH_SIZE = 20
    private const val PREVIEW_BEFORE_UTF16 = 24
    private const val PREVIEW_AFTER_UTF16 = 40

    fun search(
        blocks: Sequence<TextBlock>,
        query: String,
        cancellation: TextSearchCancellation = TextSearchCancellation(),
        maximumResults: Int = MAX_RESULTS,
        onProgress: (TextSearchProgress) -> Unit = {},
    ): TextSearchSummary {
        require(query.isNotBlank())
        require(query.length <= MAX_QUERY_UTF16)
        require(maximumResults > 0)
        val results = ArrayList<TextSearchResult>()
        val pending = ArrayList<TextSearchResult>(BATCH_SIZE)
        var scannedBlocks = 0
        var tail = ""
        var truncated = false

        for (block in blocks) {
            if (cancellation.isCancelled()) break
            val window = tail + block.text
            val windowStart = block.startUtf16 - tail.length
            var index = window.indexOf(query)
            while (index >= 0) {
                if (cancellation.isCancelled()) break
                val globalStart = windowStart + index
                // A match contained entirely in the carry was emitted with the previous block.
                if (globalStart + query.length > block.startUtf16) {
                    val result = TextSearchResult(
                        utf16Offset = globalStart,
                        preview = preview(window, index, query.length),
                    )
                    results += result
                    pending += result
                    if (pending.size == BATCH_SIZE) {
                        onProgress(TextSearchProgress(pending.toList(), scannedBlocks + 1))
                        pending.clear()
                    }
                    if (results.size == maximumResults) {
                        truncated = true
                        break
                    }
                }
                index = window.indexOf(query, index + 1)
            }
            if (pending.isNotEmpty()) {
                onProgress(TextSearchProgress(pending.toList(), scannedBlocks + 1))
                pending.clear()
            }
            scannedBlocks++
            if (truncated || cancellation.isCancelled()) break
            tail = window.takeLast((query.length - 1).coerceAtLeast(0))
        }
        if (pending.isNotEmpty()) onProgress(TextSearchProgress(pending.toList(), scannedBlocks))
        return TextSearchSummary(
            resultCount = results.size,
            scannedBlocks = scannedBlocks,
            cancelled = cancellation.isCancelled(),
            truncated = truncated,
        )
    }

    private fun preview(window: String, matchStart: Int, queryLength: Int): String {
        var start = (matchStart - PREVIEW_BEFORE_UTF16).coerceAtLeast(0)
        var end = (matchStart + queryLength + PREVIEW_AFTER_UTF16).coerceAtMost(window.length)
        if (start > 0 && Character.isLowSurrogate(window[start])) start++
        if (end in 1 until window.length && Character.isHighSurrogate(window[end - 1])) end--
        return window.substring(start, end).replace(Regex("\\s+"), " ").trim()
    }
}
