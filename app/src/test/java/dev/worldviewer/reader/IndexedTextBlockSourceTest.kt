package dev.worldviewer.reader

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedTextBlockSourceTest {
    @Test
    fun `requests load off caller and lru keeps only configured blocks`() {
        val directory = Files.createTempDirectory("worldviewer-source").toFile()
        val original = directory.resolve("original").apply { writeText("甲乙丙丁戊己庚辛壬癸") }
        val summary = StreamingTextIndex.build(
            original,
            directory.resolve("cache"),
            "d".repeat(64),
            "UTF-8",
            maxBlockUtf16 = 2,
        )
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "block-io-test") }
        val source = IndexedTextBlockSource(
            StreamingTextIndex.Reader(summary.indexFile, summary.dataFile),
            deliver = { it() },
            cacheCapacity = 3,
            worker = worker,
        )
        val latch = CountDownLatch(4)
        val callbackThreads = mutableListOf<String>()

        repeat(4) { position ->
            source.requestBlock(position) {
                synchronized(callbackThreads) { callbackThreads += Thread.currentThread().name }
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, source.cachedBlockCount())
        assertNull(source.cachedBlock(0))
        assertEquals("丙丁", source.cachedBlock(1)?.text)
        assertEquals(3, source.cachedPositionFor(6))
        assertTrue(callbackThreads.all { it == "block-io-test" })
        source.close()
    }

    @Test
    fun `duplicate requests share one cached result and notify both callers`() {
        val directory = Files.createTempDirectory("worldviewer-source").toFile()
        val original = directory.resolve("original").apply { writeText("大家好") }
        val summary = StreamingTextIndex.build(
            original,
            directory.resolve("cache"),
            "e".repeat(64),
            "UTF-8",
            maxBlockUtf16 = 3,
        )
        val source = IndexedTextBlockSource(
            StreamingTextIndex.Reader(summary.indexFile, summary.dataFile),
            deliver = { it() },
            cacheCapacity = 2,
        )
        val latch = CountDownLatch(2)

        source.requestBlock(0) { latch.countDown() }
        source.requestBlock(0) { latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("大家好", source.cachedBlock(0)?.text)
        assertFalse(source.cachedBlockCount() > 1)
        source.close()
    }

    @Test
    fun `initial block is available before asynchronous requests`() {
        val directory = Files.createTempDirectory("worldviewer-source").toFile()
        val original = directory.resolve("original").apply { writeText("abcdef") }
        val summary = StreamingTextIndex.build(
            original,
            directory.resolve("cache"),
            "f".repeat(64),
            "UTF-8",
            maxBlockUtf16 = 3,
        )
        val indexReader = StreamingTextIndex.Reader(summary.indexFile, summary.dataFile)
        val first = indexReader.blockAt(0)
        val source = IndexedTextBlockSource(
            indexReader,
            deliver = { it() },
            cacheCapacity = 2,
            initialBlocks = mapOf(0 to first),
        )

        assertEquals("abc", source.cachedBlock(0)?.text)
        assertEquals(0, source.cachedPositionFor(1))
        source.close()
    }

    @Test
    fun `requests the disk block containing an uncached utf16 offset`() {
        val directory = Files.createTempDirectory("worldviewer-source-offset").toFile()
        val original = directory.resolve("original").apply { writeText("甲乙丙丁戊己庚辛") }
        val summary = StreamingTextIndex.build(
            original,
            directory.resolve("cache"),
            "1".repeat(64),
            "UTF-8",
            maxBlockUtf16 = 2,
        )
        val source = IndexedTextBlockSource(
            StreamingTextIndex.Reader(summary.indexFile, summary.dataFile),
            deliver = { it() },
            cacheCapacity = 2,
        )
        val latch = CountDownLatch(1)
        var loadedPosition = -1

        source.requestBlockContaining(5) { position ->
            loadedPosition = position
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, loadedPosition)
        assertEquals("戊己", source.cachedBlock(2)?.text)
        source.close()
    }
}
