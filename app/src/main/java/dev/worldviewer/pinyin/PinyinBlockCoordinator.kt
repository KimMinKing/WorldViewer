package dev.worldviewer.pinyin

import dev.worldviewer.reader.TextBlock
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Keeps pinyin work limited to visible blocks and one neighboring block on either side. */
class PinyinBlockCoordinator(
    private val blockCount: Int,
    private val blockAt: (Int) -> TextBlock?,
    private val worker: Executor,
    private val deliver: ((() -> Unit) -> Unit),
    private val convert: (TextBlock) -> List<PinyinReading>,
    private val onUpdate: (Map<Int, List<PinyinReading>>) -> Unit,
) : AutoCloseable {
    constructor(
        blocks: List<TextBlock>,
        worker: Executor,
        deliver: ((() -> Unit) -> Unit),
        convert: (TextBlock) -> List<PinyinReading>,
        onUpdate: (Map<Int, List<PinyinReading>>) -> Unit,
    ) : this(
        blockCount = blocks.size,
        blockAt = blocks::getOrNull,
        worker = worker,
        deliver = deliver,
        convert = convert,
        onUpdate = onUpdate,
    )

    private val generation = AtomicLong()
    private val cache = mutableMapOf<Int, List<PinyinReading>>()

    @Volatile
    private var activePositions: Set<Int> = emptySet()

    fun requestVisible(firstPosition: Int, lastPosition: Int) {
        if (blockCount == 0 || firstPosition !in 0 until blockCount || lastPosition < firstPosition) {
            clear()
            return
        }
        val requested = ((firstPosition - 1).coerceAtLeast(0)..(lastPosition + 1).coerceAtMost(blockCount - 1))
            .toSet()
        if (requested == activePositions && requested.all(cache::containsKey)) return
        val requestGeneration = generation.incrementAndGet()
        activePositions = requested
        val previousKeys = cache.keys.toSet()
        cache.keys.retainAll(requested)
        if (cache.keys != previousKeys) {
            val retained = cache.toMap()
            deliver {
                if (generation.get() == requestGeneration) onUpdate(retained)
            }
        }

        val missing = requested.filterNot(cache::containsKey)
        if (missing.isEmpty()) return
        worker.execute {
            val converted = mutableMapOf<Int, List<PinyinReading>>()
            for (position in missing) {
                if (generation.get() != requestGeneration || position !in activePositions) return@execute
                val block = blockAt(position) ?: continue
                converted[position] = convert(block)
            }
            deliver {
                if (generation.get() != requestGeneration) return@deliver
                cache.putAll(converted.filterKeys(activePositions::contains))
                onUpdate(cache.toMap())
            }
        }
    }

    fun clear() {
        generation.incrementAndGet()
        activePositions = emptySet()
        cache.clear()
        onUpdate(emptyMap())
    }

    override fun close() {
        generation.incrementAndGet()
        activePositions = emptySet()
        cache.clear()
    }
}
