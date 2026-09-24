package dev.worldviewer.reader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPositionStoreTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun `missing position has no saved anchor`() {
        val directory = Files.createTempDirectory("worldviewer-position").toFile()

        assertNull(ReadingPositionStore(directory).load(hashA))
    }

    @Test
    fun `position round trips per document without partial files`() {
        val directory = Files.createTempDirectory("worldviewer-position").toFile()
        val store = ReadingPositionStore(directory)
        val position = ReadingPosition(hashA, 12, 9_876, "앞 문맥🙂", "뒤 문맥")

        store.save(position)

        assertEquals(position, store.load(hashA))
        assertNull(store.load(hashB))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `new position atomically replaces same document anchor`() {
        val directory = Files.createTempDirectory("worldviewer-position-replace").toFile()
        val store = ReadingPositionStore(directory)
        store.save(ReadingPosition(hashA, 1, 100, "一", "二"))
        val latest = ReadingPosition(hashA, 8, 800, "八", "九")

        store.save(latest)

        assertEquals(latest, store.load(hashA))
    }

    @Test
    fun `invalid position cannot replace existing anchor`() {
        val directory = Files.createTempDirectory("worldviewer-position-invalid").toFile()
        val store = ReadingPositionStore(directory)
        val original = ReadingPosition(hashA, 1, 100, "一", "二")
        store.save(original)

        assertThrows(TxtImportException::class.java) {
            store.save(ReadingPosition(hashA, -1, 200, "", ""))
        }

        assertEquals(original, store.load(hashA))
    }

    @Test
    fun `corrupt position is rejected`() {
        val directory = Files.createTempDirectory("worldviewer-position-corrupt").toFile()
        Files.write(File(directory, "$hashA.position").toPath(), byteArrayOf(1, 2, 3))

        assertThrows(Exception::class.java) { ReadingPositionStore(directory).load(hashA) }
    }

    @Test
    fun `startup cleanup removes only abandoned position partials`() {
        val directory = Files.createTempDirectory("worldviewer-position-partials").toFile()
        directory.resolve("position-$hashA-old.partial").writeText("abandoned")
        directory.resolve("other.partial").writeText("unrelated")
        directory.resolve("$hashA.position").writeText("keep")

        val removed = ReadingPositionStore(directory).cleanStalePartials()

        assertEquals(1, removed)
        assertFalse(directory.resolve("position-$hashA-old.partial").exists())
        assertTrue(directory.resolve("other.partial").exists())
        assertTrue(directory.resolve("$hashA.position").exists())
    }
}
