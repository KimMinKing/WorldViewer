package dev.worldviewer.reader

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMeaningStoreTest {
    private val bookA = "a".repeat(64)
    private val bookB = "b".repeat(64)

    @Test
    fun `all scopes and multiple meanings round trip`() {
        val store = UserMeaningStore(Files.createTempDirectory("worldviewer-meanings").toFile())
        val values = listOf(
            meaning(1, "강호", MeaningScope.GLOBAL),
            meaning(2, "무림", MeaningScope.BOOK, bookA),
            meaning(3, "세상", MeaningScope.RANGE, bookA, 10, 12),
        )

        store.save(values)

        assertEquals(values, store.load())
    }

    @Test
    fun `range then book then global precedence returns all meanings at winning scope`() {
        val global = meaning(1, "global", MeaningScope.GLOBAL)
        val book = meaning(2, "book", MeaningScope.BOOK, bookA)
        val rangeA = meaning(3, "range one", MeaningScope.RANGE, bookA, 10, 12)
        val rangeB = meaning(4, "range two", MeaningScope.RANGE, bookA, 10, 12)
        val index = UserMeaningIndex(listOf(global, rangeB, book, rangeA))

        assertEquals(
            listOf(rangeA, rangeB),
            index.resolve(MeaningQuery("江湖", bookA, 10, 12)),
        )
        assertEquals(
            listOf(book),
            index.resolve(MeaningQuery("江湖", bookA, 20, 22)),
        )
        assertEquals(
            listOf(global),
            index.resolve(MeaningQuery("江湖", bookB, 20, 22)),
        )
    }

    @Test
    fun `failed write preserves prior user meanings`() {
        val directory = Files.createTempDirectory("worldviewer-meaning-failure").toFile()
        val store = UserMeaningStore(directory)
        val original = listOf(meaning(1, "기존", MeaningScope.GLOBAL))
        store.save(original)

        assertThrows(TxtImportException::class.java) {
            store.saveWithOutput(listOf(meaning(2, "새 값", MeaningScope.GLOBAL))) { delegate ->
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException("simulated failure")
                    override fun write(buffer: ByteArray, offset: Int, length: Int) =
                        throw IOException("simulated failure")
                    override fun close() = delegate.close()
                }
            }
        }

        assertEquals(original, store.load())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `invalid scope and duplicate identifiers cannot replace data`() {
        val store = UserMeaningStore(Files.createTempDirectory("worldviewer-meaning-invalid").toFile())
        val original = meaning(1, "기존", MeaningScope.GLOBAL)
        store.save(listOf(original))

        assertThrows(TxtImportException::class.java) {
            store.save(listOf(original.copy(scope = MeaningScope.BOOK)))
        }
        assertThrows(TxtImportException::class.java) {
            store.save(listOf(original, original))
        }
        assertEquals(listOf(original), store.load())
    }

    @Test
    fun `cleanup removes only user meaning partials`() {
        val directory = Files.createTempDirectory("worldviewer-meaning-partial").toFile()
        directory.resolve("meanings-old.partial").writeText("partial")
        directory.resolve("chapters-old.partial").writeText("chapter")
        directory.resolve(UserMeaningStore.FILE_NAME).writeText("keep")

        assertEquals(1, UserMeaningStore(directory).cleanStalePartials())
        assertFalse(directory.resolve("meanings-old.partial").exists())
        assertTrue(directory.resolve("chapters-old.partial").exists())
        assertTrue(directory.resolve(UserMeaningStore.FILE_NAME).exists())
    }

    @Test
    fun `one hundred thousand meanings remain directly addressable`() {
        val values = List(100_000) { index ->
            UserMeaning(
                id = index.toString(16).padStart(8, '0') + "-1111-1111-1111-111111111111",
                sourceText = "词$index",
                meaning = "뜻 $index",
                scope = MeaningScope.GLOBAL,
                documentSha256 = null,
                rangeStartUtf16 = null,
                rangeEndUtf16 = null,
                createdAtEpochMillis = index.toLong(),
                updatedAtEpochMillis = index.toLong(),
            )
        }
        val buildStarted = System.nanoTime()
        val index = UserMeaningIndex(values)
        val buildMillis = (System.nanoTime() - buildStarted) / 1_000_000.0
        val lookupStarted = System.nanoTime()
        repeat(10_000) { number ->
            assertEquals(
                "뜻 ${number * 10}",
                index.resolve(MeaningQuery("词${number * 10}", null, null, null)).single().meaning,
            )
        }
        val lookupMillis = (System.nanoTime() - lookupStarted) / 1_000_000.0
        println("USER_MEANING_100K buildMs=$buildMillis lookup10kMs=$lookupMillis")
    }

    private fun meaning(
        number: Int,
        definition: String,
        scope: MeaningScope,
        document: String? = null,
        start: Int? = null,
        end: Int? = null,
    ) = UserMeaning(
        id = number.toString().padStart(8, '0') + "-1111-1111-1111-111111111111",
        sourceText = "江湖",
        meaning = definition,
        scope = scope,
        documentSha256 = document,
        rangeStartUtf16 = start,
        rangeEndUtf16 = end,
        createdAtEpochMillis = number.toLong(),
        updatedAtEpochMillis = number.toLong(),
    )
}
