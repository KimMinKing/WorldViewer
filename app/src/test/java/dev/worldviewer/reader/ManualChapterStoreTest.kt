package dev.worldviewer.reader

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualChapterStoreTest {
    private val bookA = "a".repeat(64)
    private val bookB = "b".repeat(64)
    private val first = ManualChapter(
        id = "11111111-1111-1111-1111-111111111111",
        title = "第一章 시작",
        utf16Offset = 47,
        quoteBefore = "앞 문장",
        quoteAfter = "뒤 문장",
    )

    @Test
    fun `chapters round trip in source order and stay separate by document`() {
        val store = ManualChapterStore(Files.createTempDirectory("worldviewer-manual-toc").toFile())
        val later = first.copy(id = "22222222-2222-2222-2222-222222222222", title = "뒤", utf16Offset = 900)

        store.save(bookA, listOf(later, first))

        assertEquals(listOf(first, later), store.load(bookA))
        assertEquals(emptyList<ManualChapter>(), store.load(bookB))
    }

    @Test
    fun `renaming and deleting keep the original anchor`() {
        val store = ManualChapterStore(Files.createTempDirectory("worldviewer-manual-edit").toFile())
        store.save(bookA, listOf(first))

        val renamed = store.load(bookA).single().copy(title = "새 제목")
        store.save(bookA, listOf(renamed))
        assertEquals(first.copy(title = "새 제목"), store.load(bookA).single())

        store.save(bookA, emptyList())
        assertTrue(store.load(bookA).isEmpty())
    }

    @Test
    fun `failed replacement preserves prior chapters`() {
        val directory = Files.createTempDirectory("worldviewer-manual-failure").toFile()
        val store = ManualChapterStore(directory)
        store.save(bookA, listOf(first))

        assertThrows(TxtImportException::class.java) {
            store.saveWithOutput(bookA, listOf(first.copy(title = "손실되면 안 됨"))) { delegate ->
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException("simulated storage failure")
                    override fun write(buffer: ByteArray, offset: Int, length: Int) =
                        throw IOException("simulated storage failure")
                    override fun close() = delegate.close()
                }
            }
        }

        assertEquals(listOf(first), store.load(bookA))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `invalid chapter cannot replace prior chapters`() {
        val store = ManualChapterStore(Files.createTempDirectory("worldviewer-manual-invalid").toFile())
        store.save(bookA, listOf(first))

        assertThrows(TxtImportException::class.java) {
            store.save(bookA, listOf(first.copy(title = " ")))
        }
        assertThrows(TxtImportException::class.java) {
            store.save(bookA, listOf(first, first))
        }

        assertEquals(listOf(first), store.load(bookA))
    }

    @Test
    fun `startup cleanup removes only manual chapter partials`() {
        val directory = Files.createTempDirectory("worldviewer-manual-partials").toFile()
        directory.resolve("chapters-old.partial").writeText("abandoned")
        directory.resolve("position-old.partial").writeText("position")
        directory.resolve("${bookA}.chapters").writeText("keep")

        val removed = ManualChapterStore(directory).cleanStalePartials()

        assertEquals(1, removed)
        assertFalse(directory.resolve("chapters-old.partial").exists())
        assertTrue(directory.resolve("position-old.partial").exists())
        assertTrue(directory.resolve("${bookA}.chapters").exists())
    }
}
