package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class DictionaryLookupCoordinatorTest {
    @Test
    fun `provider runs only after an explicit request`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        var calls = 0
        val delivered = mutableListOf<DictionaryLookupResponse>()
        val coordinator = DictionaryLookupCoordinator(worker, delivery::deliver) { _, result ->
            delivered += result.getOrThrow()
        }
        val provider = DictionaryLookupProvider { request, _ ->
            calls++
            response(request.sourceText)
        }

        assertEquals(0, calls)
        coordinator.request(request("江湖", 1), provider)
        assertEquals(0, calls)

        worker.runAll()
        assertEquals(1, calls)
        assertTrue(delivered.isEmpty())
        delivery.runAll()
        assertEquals(listOf(response("江湖")), delivered)
    }

    @Test
    fun `late response A cannot replace selection B`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val delivered = mutableListOf<String>()
        val coordinator = DictionaryLookupCoordinator(worker, delivery::deliver) { request, result ->
            result.getOrThrow()
            delivered += request.sourceText
        }
        val provider = DictionaryLookupProvider { request, _ -> response(request.sourceText) }

        coordinator.request(request("甲", 1), provider)
        worker.runNext()
        coordinator.request(request("乙", 2), provider)
        worker.runNext()
        delivery.runAll()

        assertEquals(listOf("乙"), delivered)
    }

    @Test
    fun `superseded work observes cancellation before provider call`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val called = mutableListOf<String>()
        val coordinator = DictionaryLookupCoordinator(worker, delivery::deliver) { _, _ -> }
        val provider = DictionaryLookupProvider { request, cancellation ->
            assertFalse(cancellation.isCancelled)
            called += request.sourceText
            response(request.sourceText)
        }

        coordinator.request(request("旧", 1), provider)
        coordinator.request(request("新", 2), provider)
        worker.runAll()
        delivery.runAll()

        assertEquals(listOf("新"), called)
    }

    @Test
    fun `no entries is a successful response`() {
        val response = response("无结果")
        assertTrue(response.entries.isEmpty())
        assertEquals(LookupRetention.DO_NOT_STORE, response.storagePolicy.retention)
    }

    @Test
    fun `response query must match the request`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        var failure: Throwable? = null
        val coordinator = DictionaryLookupCoordinator(worker, delivery::deliver) { _, result ->
            failure = result.exceptionOrNull()
        }

        coordinator.request(request("请求", 1), DictionaryLookupProvider { _, _ -> response("其他") })
        worker.runAll()
        delivery.runAll()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `cache expiry is required only for expiring cache`() {
        assertThrows(IllegalArgumentException::class.java) {
            LookupStoragePolicy(LookupRetention.CACHE_UNTIL_EXPIRY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LookupStoragePolicy(LookupRetention.DO_NOT_STORE, expiresAtEpochMillis = 10)
        }
    }

    @Test
    fun `timeout cancels work and is delivered once`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val scheduler = ManualTimeoutScheduler()
        val results = mutableListOf<Result<DictionaryLookupResponse>>()
        val coordinator = DictionaryLookupCoordinator(
            worker = worker,
            deliver = delivery::deliver,
            timeoutPolicy = LookupTimeoutPolicy(1_500, scheduler),
            onResult = { _, result -> results += result },
        )
        var providerCalls = 0
        coordinator.request(request("超时", 1), DictionaryLookupProvider { request, _ ->
            providerCalls++
            response(request.sourceText)
        })

        scheduler.runAll()
        delivery.runAll()
        worker.runAll()
        delivery.runAll()

        assertEquals(0, providerCalls)
        assertEquals(1, results.size)
        val error = results.single().exceptionOrNull()
        assertTrue(error is LookupTimeoutException)
        assertEquals(1_500, (error as LookupTimeoutException).timeoutMillis)
    }

    @Test
    fun `completed lookup cancels its timeout`() {
        val worker = ManualExecutor()
        val delivery = ManualExecutor()
        val scheduler = ManualTimeoutScheduler()
        val delivered = mutableListOf<String>()
        val coordinator = DictionaryLookupCoordinator(
            worker = worker,
            deliver = delivery::deliver,
            timeoutPolicy = LookupTimeoutPolicy(1_500, scheduler),
            onResult = { request, result ->
                result.getOrThrow()
                delivered += request.sourceText
            },
        )

        coordinator.request(request("及时", 1), DictionaryLookupProvider { request, _ ->
            response(request.sourceText)
        })
        worker.runAll()
        scheduler.runAll()
        delivery.runAll()

        assertEquals(listOf("及时"), delivered)
    }

    private fun request(source: String, selectionVersion: Long) = DictionaryLookupRequest(
        sourceText = source,
        documentId = "book-id",
        documentVersion = "sha256",
        rangeStartUtf16 = 10,
        rangeEndUtf16 = 10 + source.length,
        selectionVersion = selectionVersion,
    )

    private fun response(query: String) = DictionaryLookupResponse(
        providerId = "fixture",
        providerVersion = "1",
        queryText = query,
        entries = emptyList(),
        resultType = LookupResultType.REVERSE_DICTIONARY,
        attribution = LookupAttribution("테스트 공급원"),
        storagePolicy = LookupStoragePolicy(LookupRetention.DO_NOT_STORE),
    )

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun deliver(action: () -> Unit) {
            execute(Runnable(action))
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class ManualTimeoutScheduler : LookupTimeoutScheduler {
        private val tasks = ArrayDeque<ScheduledTask>()

        override fun schedule(delayMillis: Long, action: () -> Unit): LookupTimeoutHandle {
            val task = ScheduledTask(action)
            tasks += task
            return LookupTimeoutHandle { task.cancelled = true }
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().let { if (!it.cancelled) it.action() }
            }
        }

        private data class ScheduledTask(
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
