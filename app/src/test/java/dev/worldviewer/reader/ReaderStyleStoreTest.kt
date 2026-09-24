package dev.worldviewer.reader

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleStoreTest {
    private val bookA = "a".repeat(64)
    private val bookB = "b".repeat(64)

    @Test
    fun `missing file resolves built in defaults`() {
        val store = ReaderStyleStore(Files.createTempDirectory("worldviewer-style-missing").toFile())

        assertNull(store.load())
        assertEquals(ReaderStyle.DEFAULT, ReaderStylePreferences().resolved(bookA))
    }

    @Test
    fun `default and book overrides round trip`() {
        val directory = Files.createTempDirectory("worldviewer-style-roundtrip").toFile()
        val store = ReaderStyleStore(directory)
        val defaultStyle = ReaderStyle(18, 125, ReaderTheme.DARK)
        val bookStyle = ReaderStyle(24, 150, ReaderTheme.LIGHT)
        val preferences = ReaderStylePreferences(defaultStyle, mapOf(bookA to bookStyle))

        store.save(preferences)

        assertEquals(preferences, store.load())
        assertEquals(bookStyle, preferences.resolved(bookA))
        assertEquals(defaultStyle, preferences.resolved(bookB))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `removing book override returns it to the default style`() {
        val dark = ReaderStyle(20, 130, ReaderTheme.DARK)
        val preferences = ReaderStylePreferences(
            defaultStyle = dark,
            bookStyles = mapOf(bookA to ReaderStyle(26, 170, ReaderTheme.LIGHT)),
        )

        val cleared = preferences.copy(bookStyles = preferences.bookStyles - bookA)

        assertEquals(dark, cleared.resolved(bookA))
    }

    @Test
    fun `failed replacement preserves prior style file`() {
        val directory = Files.createTempDirectory("worldviewer-style-failure").toFile()
        val store = ReaderStyleStore(directory)
        val original = ReaderStylePreferences(defaultStyle = ReaderStyle(18, 120, ReaderTheme.LIGHT))
        store.save(original)

        assertThrows(TxtImportException::class.java) {
            store.saveWithOutput(ReaderStylePreferences(defaultStyle = ReaderStyle(30, 180, ReaderTheme.DARK))) {
                throw IOException("simulated storage failure")
            }
        }

        assertEquals(original, store.load())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `invalid values and identifiers cannot replace prior settings`() {
        val directory = Files.createTempDirectory("worldviewer-style-invalid").toFile()
        val store = ReaderStyleStore(directory)
        val original = ReaderStylePreferences()
        store.save(original)

        assertThrows(TxtImportException::class.java) {
            store.save(ReaderStylePreferences(defaultStyle = ReaderStyle(50, 115, ReaderTheme.LIGHT)))
        }
        assertThrows(TxtImportException::class.java) {
            store.save(ReaderStylePreferences(bookStyles = mapOf("outside" to ReaderStyle.DEFAULT)))
        }

        assertEquals(original, store.load())
    }

    @Test
    fun `startup cleanup removes only style partials`() {
        val directory = Files.createTempDirectory("worldviewer-style-partials").toFile()
        directory.resolve("style-old.partial").writeText("abandoned")
        directory.resolve("catalog-old.partial").writeText("catalog")
        directory.resolve(ReaderStyleStore.FILE_NAME).writeText("keep")

        val removed = ReaderStyleStore(directory).cleanStalePartials()

        assertEquals(1, removed)
        assertFalse(directory.resolve("style-old.partial").exists())
        assertTrue(directory.resolve("catalog-old.partial").exists())
        assertTrue(directory.resolve(ReaderStyleStore.FILE_NAME).exists())
    }
}
