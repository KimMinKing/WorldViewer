package dev.worldviewer.reader

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCatalogStoreTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun `legacy current document becomes first catalog entry without changing legacy files`() {
        val root = Files.createTempDirectory("worldviewer-catalog-migration").toFile()
        val imports = root.resolve("imports").apply { mkdirs() }
        val original = imports.resolve("$hashA.original").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val metadata = ImportMetadata("$hashA.original", "무협.txt", hashA, 3, "UTF-8")
        val metadataStore = ImportMetadataStore(imports).also { it.save(metadata) }
        val originalBytes = original.readBytes()
        val metadataBytes = imports.resolve(ImportMetadataStore.FILE_NAME).readBytes()

        val catalog = BookCatalogStore(root.resolve("library")).reconcileCurrent(metadata, 100)

        assertEquals(hashA, catalog.currentBookId)
        assertEquals(
            listOf(BookCatalogEntry(hashA, hashA, "$hashA.original", "무협.txt", 3, "UTF-8", 100, 100)),
            catalog.books,
        )
        assertEquals(metadata, metadataStore.load())
        assertTrue(originalBytes.contentEquals(original.readBytes()))
        assertTrue(metadataBytes.contentEquals(imports.resolve(ImportMetadataStore.FILE_NAME).readBytes()))
    }

    @Test
    fun `reconcile preserves books and orders the most recently opened first`() {
        val directory = Files.createTempDirectory("worldviewer-catalog-books").toFile()
        val store = BookCatalogStore(directory)
        val first = ImportMetadata("$hashA.original", "첫째.txt", hashA, 10, "UTF-8")
        val second = ImportMetadata("$hashB.original", "둘째.txt", hashB, 20, "Big5")

        store.reconcileCurrent(first, 100)
        store.reconcileCurrent(second, 200)
        val reopened = store.reconcileCurrent(first.copy(displayName = "첫째-새이름.txt"), 300)

        assertEquals(hashA, reopened.currentBookId)
        assertEquals(listOf(hashA, hashB), reopened.books.map(BookCatalogEntry::bookId))
        assertEquals(100, reopened.books[0].addedAtEpochMillis)
        assertEquals(300, reopened.books[0].lastOpenedAtEpochMillis)
        assertEquals("첫째-새이름.txt", reopened.books[0].displayName)
        assertEquals(reopened, store.load())
    }

    @Test
    fun `failed replacement keeps the prior catalog and removes the partial`() {
        val directory = Files.createTempDirectory("worldviewer-catalog-failure").toFile()
        val store = BookCatalogStore(directory)
        val original = store.reconcileCurrent(
            ImportMetadata("$hashA.original", "원본.txt", hashA, 10, "UTF-8"),
            100,
        )
        val replacement = BookCatalog(
            currentBookId = hashB,
            books = original.books + BookCatalogEntry(
                hashB,
                hashB,
                "$hashB.original",
                "새책.txt",
                20,
                "UTF-16LE BOM",
                200,
                200,
            ),
        )

        assertThrows(TxtImportException::class.java) {
            store.saveWithOutput(replacement) { throw IOException("simulated storage failure") }
        }

        assertEquals(original, store.load())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `next reconcile repairs a catalog left behind by a failed current update`() {
        val directory = Files.createTempDirectory("worldviewer-catalog-recovery").toFile()
        val store = BookCatalogStore(directory)
        val first = ImportMetadata("$hashA.original", "첫째.txt", hashA, 10, "UTF-8")
        val second = ImportMetadata("$hashB.original", "둘째.txt", hashB, 20, "UTF-16BE BOM")
        val original = store.reconcileCurrent(first, 100)
        val interrupted = BookCatalog(
            currentBookId = hashB,
            books = original.books + BookCatalogEntry(
                hashB,
                hashB,
                "$hashB.original",
                "둘째.txt",
                20,
                "UTF-16BE BOM",
                200,
                200,
            ),
        )
        assertThrows(TxtImportException::class.java) {
            store.saveWithOutput(interrupted) { throw IOException("simulated interrupted update") }
        }

        val recovered = store.reconcileCurrent(second, 300)

        assertEquals(hashB, recovered.currentBookId)
        assertEquals(setOf(hashA, hashB), recovered.books.map(BookCatalogEntry::bookId).toSet())
        assertEquals(recovered, store.load())
    }

    @Test
    fun `corrupt catalog is rejected without touching legacy metadata`() {
        val root = Files.createTempDirectory("worldviewer-catalog-corrupt").toFile()
        val imports = root.resolve("imports").apply { mkdirs() }
        val metadata = ImportMetadata("$hashA.original", "보존.txt", hashA, 1, "UTF-8")
        val metadataStore = ImportMetadataStore(imports).also { it.save(metadata) }
        val metadataBytes = imports.resolve(ImportMetadataStore.FILE_NAME).readBytes()
        val directory = root.resolve("library").apply { mkdirs() }
        directory.resolve(BookCatalogStore.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(Exception::class.java) { BookCatalogStore(directory).reconcileCurrent(metadata, 100) }

        assertEquals(metadata, metadataStore.load())
        assertTrue(metadataBytes.contentEquals(imports.resolve(ImportMetadataStore.FILE_NAME).readBytes()))
    }

    @Test
    fun `startup cleanup removes only abandoned catalog partials`() {
        val directory = Files.createTempDirectory("worldviewer-catalog-partials").toFile()
        directory.resolve("catalog-old.partial").writeText("abandoned")
        directory.resolve("current-old.partial").writeText("legacy")
        directory.resolve(BookCatalogStore.FILE_NAME).writeText("keep")

        val removed = BookCatalogStore(directory).cleanStalePartials()

        assertEquals(1, removed)
        assertFalse(directory.resolve("catalog-old.partial").exists())
        assertTrue(directory.resolve("current-old.partial").exists())
        assertNotNull(directory.resolve(BookCatalogStore.FILE_NAME).takeIf { it.exists() })
    }
}
