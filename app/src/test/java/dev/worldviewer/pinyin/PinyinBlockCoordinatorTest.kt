package dev.worldviewer.pinyin

import dev.worldviewer.reader.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class PinyinBlockCoordinatorTest {
    @Test
    fun `only visible and adjacent blocks are converted`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val converted = mutableListOf<Int>()
        var latest = emptyMap<Int, List<PinyinReading>>()
        val coordinator = coordinator(worker, delivery, converted) { latest = it }

        coordinator.requestVisible(2, 3)
        worker.runAll()
        delivery.runAll()

        assertEquals(listOf(1, 2, 3, 4), converted.sorted())
        assertEquals(setOf(1, 2, 3, 4), latest.keys)
    }

    @Test
    fun `stale queued work is skipped after a new viewport request`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val converted = mutableListOf<Int>()
        var latest = emptyMap<Int, List<PinyinReading>>()
        val coordinator = coordinator(worker, delivery, converted) { latest = it }

        coordinator.requestVisible(0, 0)
        coordinator.requestVisible(4, 4)
        worker.runAll()
        delivery.runAll()

        assertEquals(listOf(3, 4, 5), converted.sorted())
        assertEquals(setOf(3, 4, 5), latest.keys)
    }

    @Test
    fun `completed results outside a newer viewport are discarded`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        var latest = emptyMap<Int, List<PinyinReading>>()
        val coordinator = coordinator(worker, delivery, mutableListOf()) { latest = it }

        coordinator.requestVisible(0, 0)
        worker.runAll()
        coordinator.requestVisible(4, 4)
        delivery.runAll()

        assertTrue(latest.isEmpty())
        worker.runAll()
        delivery.runAll()
        assertEquals(setOf(3, 4, 5), latest.keys)
    }

    @Test
    fun `same viewport retries a block after it becomes available`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val blocks = List(3) { index -> TextBlock(index.toLong(), index, index.toString()) }
        var available = false
        var latest = emptyMap<Int, List<PinyinReading>>()
        val coordinator = PinyinBlockCoordinator(
            blockCount = blocks.size,
            blockAt = { position -> blocks[position].takeIf { available || position != 1 } },
            worker = worker,
            deliver = { action -> delivery.execute(Runnable(action)) },
            convert = { block -> listOf(PinyinReading(0, 1, block.text, "p${block.id}")) },
            onUpdate = { latest = it },
        )

        coordinator.requestVisible(0, 0)
        worker.runAll()
        delivery.runAll()
        assertEquals(setOf(0), latest.keys)

        available = true
        coordinator.requestVisible(0, 0)
        worker.runAll()
        delivery.runAll()
        assertEquals(setOf(0, 1), latest.keys)
    }

    private fun coordinator(
        worker: ManualExecutor,
        delivery: ManualExecutor,
        converted: MutableList<Int>,
        onUpdate: (Map<Int, List<PinyinReading>>) -> Unit,
    ): PinyinBlockCoordinator {
        val blocks = List(6) { index -> TextBlock(index.toLong(), index, index.toString()) }
        return PinyinBlockCoordinator(
            blocks = blocks,
            worker = worker,
            deliver = { action -> delivery.execute(Runnable(action)) },
            convert = { block ->
                converted += block.id.toInt()
                listOf(PinyinReading(0, 1, block.text, "p${block.id}"))
            },
            onUpdate = onUpdate,
        )
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
