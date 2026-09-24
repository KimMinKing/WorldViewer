package dev.worldviewer.reader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportMetadataStoreTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun `missing metadata has no recent document`() {
        val directory = Files.createTempDirectory("worldviewer-metadata").toFile()

        assertNull(ImportMetadataStore(directory).load())
    }

    @Test
    fun `saved metadata round trips and leaves no partial file`() {
        val directory = Files.createTempDirectory("worldviewer-metadata").toFile()
        val store = ImportMetadataStore(directory)
        val metadata = ImportMetadata("$hashA.original", "武侠.txt", hashA, 123, "UTF-8")

        store.save(metadata)

        assertEquals(metadata, store.load())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `new save replaces recent pointer`() {
        val directory = Files.createTempDirectory("worldviewer-metadata").toFile()
        val store = ImportMetadataStore(directory)
        store.save(ImportMetadata("$hashA.original", "첫째.txt", hashA, 1, "UTF-8"))
        val latest = ImportMetadata("$hashB.original", "둘째.txt", hashB, 2, "UTF-16LE BOM")

        store.save(latest)

        assertEquals(latest, store.load())
    }

    @Test
    fun `invalid identifier cannot replace recent pointer`() {
        val directory = Files.createTempDirectory("worldviewer-metadata").toFile()
        val store = ImportMetadataStore(directory)
        val original = ImportMetadata("$hashA.original", "원본.txt", hashA, 1, "UTF-8")
        store.save(original)

        assertThrows(TxtImportException::class.java) {
            store.save(ImportMetadata("../outside", "실패.txt", hashB, 2, "UTF-8"))
        }
        assertEquals(original, store.load())
    }

    @Test
    fun `corrupt metadata is rejected`() {
        val directory = Files.createTempDirectory("worldviewer-metadata").toFile()
        Files.write(File(directory, ImportMetadataStore.FILE_NAME).toPath(), byteArrayOf(1, 2, 3))

        assertThrows(Exception::class.java) { ImportMetadataStore(directory).load() }
    }

    @Test
    fun `metadata accepts document larger than former eight mebibyte limit`() {
        val directory = Files.createTempDirectory("worldviewer-large-metadata").toFile()
        val store = ImportMetadataStore(directory)
        val metadata = ImportMetadata(
            "$hashA.original",
            "긴소설.txt",
            hashA,
            50L * 1024L * 1024L,
            "UTF-8",
        )

        store.save(metadata)

        assertEquals(metadata, store.load())
    }

    @Test
    fun `metadata preserves user confirmed legacy encoding`() {
        val directory = Files.createTempDirectory("worldviewer-legacy-metadata").toFile()
        val store = ImportMetadataStore(directory)
        val metadata = ImportMetadata("$hashA.original", "번체.txt", hashA, 42, "Big5")

        store.save(metadata)

        assertEquals(metadata, store.load())
    }

    @Test
    fun `startup cleanup removes only abandoned metadata partials`() {
        val directory = Files.createTempDirectory("worldviewer-metadata-partials").toFile()
        directory.resolve("current-old.partial").writeText("abandoned")
        directory.resolve("incoming-old.partial").writeText("import")
        directory.resolve(ImportMetadataStore.FILE_NAME).writeText("keep")

        val removed = ImportMetadataStore(directory).cleanStalePartials()

        assertEquals(1, removed)
        assertFalse(directory.resolve("current-old.partial").exists())
        org.junit.Assert.assertTrue(directory.resolve("incoming-old.partial").exists())
        org.junit.Assert.assertTrue(directory.resolve(ImportMetadataStore.FILE_NAME).exists())
    }
}
