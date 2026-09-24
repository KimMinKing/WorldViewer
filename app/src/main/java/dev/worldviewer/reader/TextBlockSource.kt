package dev.worldviewer.reader

import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface TextBlockSource : Closeable {
    val blockCount: Int

    fun cachedBlock(position: Int): TextBlock?

    fun cachedPositionFor(globalUtf16: Int): Int

    fun requestBlock(position: Int, onAvailable: (Int) -> Unit)

    fun requestBlockContaining(globalUtf16: Int, onAvailable: (Int) -> Unit)

    override fun close() = Unit
}

class InMemoryTextBlockSource(private val blocks: List<TextBlock>) : TextBlockSource {
    override val blockCount: Int
        get() = blocks.size

    override fun cachedBlock(position: Int): TextBlock? = blocks.getOrNull(position)

    override fun cachedPositionFor(globalUtf16: Int): Int =
        blocks.binarySearch { block ->
            when {
                globalUtf16 < block.startUtf16 -> 1
                globalUtf16 >= block.endUtf16 -> -1
                else -> 0
            }
        }

    override fun requestBlock(position: Int, onAvailable: (Int) -> Unit) {
        if (position in blocks.indices) onAvailable(position)
    }

    override fun requestBlockContaining(globalUtf16: Int, onAvailable: (Int) -> Unit) {
        val position = cachedPositionFor(globalUtf16)
        if (position >= 0) onAvailable(position)
    }
}

class IndexedTextBlockSource(
    private val reader: StreamingTextIndex.Reader,
    private val deliver: ((() -> Unit) -> Unit),
    private val cacheCapacity: Int = 12,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    initialBlocks: Map<Int, TextBlock> = emptyMap(),
) : TextBlockSource {
    private val lock = Any()
    private val cache = object : LinkedHashMap<Int, TextBlock>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextBlock>?): Boolean =
            size > cacheCapacity
    }
    private val inFlight = mutableMapOf<Int, MutableList<(Int) -> Unit>>()
    private var closed = false

    init {
        require(cacheCapacity > 0)
        require(initialBlocks.size <= cacheCapacity)
        initialBlocks.forEach { (position, block) ->
            require(position in 0 until reader.blockCount)
            cache[position] = block
        }
    }

    override val blockCount: Int
        get() = reader.blockCount

    override fun cachedBlock(position: Int): TextBlock? = synchronized(lock) { cache[position] }

    override fun cachedPositionFor(globalUtf16: Int): Int = synchronized(lock) {
        cache.entries.firstOrNull { (_, block) -> globalUtf16 in block.startUtf16 until block.endUtf16 }?.key ?: -1
    }

    override fun requestBlock(position: Int, onAvailable: (Int) -> Unit) {
        if (position !in 0 until blockCount) return
        var cached = false
        var shouldLoad = false
        synchronized(lock) {
            if (closed) return
            cached = cache[position] != null
            if (!cached) {
                val callbacks = inFlight[position]
                if (callbacks == null) {
                    inFlight[position] = mutableListOf(onAvailable)
                    shouldLoad = true
                } else {
                    callbacks += onAvailable
                }
            }
        }
        if (cached) {
            deliver { if (!isClosed()) onAvailable(position) }
            return
        }
        if (!shouldLoad) return
        worker.execute {
            val loaded = runCatching { reader.blockAt(position) }.getOrNull()
            val callbacks = synchronized(lock) {
                if (loaded != null && !closed) cache[position] = loaded
                inFlight.remove(position).orEmpty()
            }
            if (loaded != null && callbacks.isNotEmpty()) {
                deliver {
                    if (!isClosed()) callbacks.forEach { it(position) }
                }
            }
        }
    }

    override fun requestBlockContaining(globalUtf16: Int, onAvailable: (Int) -> Unit) {
        if (globalUtf16 !in 0 until reader.utf16Length) return
        val cachedPosition = cachedPositionFor(globalUtf16)
        if (cachedPosition >= 0) {
            deliver { if (!isClosed()) onAvailable(cachedPosition) }
            return
        }
        worker.execute {
            val loaded = runCatching {
                val position = reader.positionForUtf16(globalUtf16)
                val block = synchronized(lock) { cache[position] } ?: reader.blockAt(position)
                position to block
            }.getOrNull() ?: return@execute
            val available = synchronized(lock) {
                if (closed) false else {
                    cache[loaded.first] = loaded.second
                    true
                }
            }
            if (available) {
                deliver { if (!isClosed()) onAvailable(loaded.first) }
            }
        }
    }

    fun prefetch(centerPosition: Int, radius: Int = 1, onAvailable: (Int) -> Unit = {}) {
        require(radius >= 0)
        val start = maxOf(0, centerPosition - radius)
        val end = minOf(blockCount - 1, centerPosition + radius)
        if (start > end) return
        for (position in start..end) requestBlock(position, onAvailable)
    }

    internal fun cachedBlockCount(): Int = synchronized(lock) { cache.size }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                cache.clear()
                inFlight.clear()
                true
            }
        }
        if (!shouldClose) return
        worker.execute { reader.close() }
        worker.shutdown()
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }
}
