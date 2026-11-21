package dev.worldviewer.reader

import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream
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
        val bookStyle = ReaderStyle(24, 150, ReaderTheme.LIGHT,
            textColorArgb = 0xFFFFFFFF.toInt(), backgroundColorArgb = 0xFF102838.toInt(),
            showSavedMeaningMarks = false)
        val preferences = ReaderStylePreferences(defaultStyle, mapOf(bookA to bookStyle),
            volumeKeysTurnPages = true, animatePageTurns = true)

        store.save(preferences)

        assertEquals(preferences, store.load())
        assertEquals(bookStyle, preferences.resolved(bookA))
        assertEquals(defaultStyle, preferences.resolved(bookB))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `version one styles load unchanged and upgrade only when saved`() {
        val directory = Files.createTempDirectory("worldviewer-style-v1").toFile()
        val file = directory.resolve(ReaderStyleStore.FILE_NAME)
        DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(0x57565354)
            output.writeInt(1)
            output.writeInt(21)
            output.writeInt(130)
            output.writeUTF("DARK")
            output.writeInt(1)
            output.writeUTF(bookA)
            output.writeInt(24)
            output.writeInt(150)
            output.writeUTF("LIGHT")
        }
        val before = file.readBytes()
        val store = ReaderStyleStore(directory)
        val legacy = store.load()
        assertEquals(ReaderStyle(21, 130, ReaderTheme.DARK), legacy?.defaultStyle)
        assertEquals(ReaderStyle(24, 150, ReaderTheme.LIGHT), legacy?.bookStyles?.get(bookA))
        assertTrue(before.contentEquals(file.readBytes()))

        store.save(requireNotNull(legacy))
        DataInputStream(file.inputStream()).use { input ->
            assertEquals(0x57565354, input.readInt())
            assertEquals(5, input.readInt())
        }
        assertEquals(legacy, store.load())
    }

    @Test
    fun `version three settings keep volume keys disabled until explicitly enabled`() {
        val directory = Files.createTempDirectory("worldviewer-style-v3").toFile()
        val file = directory.resolve(ReaderStyleStore.FILE_NAME)
        DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(0x57565354)
            output.writeInt(3)
            output.writeInt(19)
            output.writeInt(115)
            output.writeUTF("LIGHT")
            output.writeInt(0)
            output.writeInt(0)
            output.writeBoolean(true)
            output.writeInt(0)
        }
        val before = file.readBytes()
        val store = ReaderStyleStore(directory)

        assertFalse(requireNotNull(store.load()).volumeKeysTurnPages)
        assertTrue(before.contentEquals(file.readBytes()))

        store.save(requireNotNull(store.load()).copy(volumeKeysTurnPages = true))
        assertTrue(requireNotNull(store.load()).volumeKeysTurnPages)
        DataInputStream(file.inputStream()).use { input ->
            assertEquals(0x57565354, input.readInt())
            assertEquals(5, input.readInt())
        }
    }

    @Test
    fun `version four setting loads without animation and upgrades only after save`() {
        val directory = Files.createTempDirectory("worldviewer-style-v4").toFile()
        val file = directory.resolve(ReaderStyleStore.FILE_NAME)
        DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(0x57565354)
            output.writeInt(4)
            output.writeInt(19)
            output.writeInt(115)
            output.writeUTF("LIGHT")
            output.writeInt(0)
            output.writeInt(0)
            output.writeBoolean(true)
            output.writeInt(0)
            output.writeBoolean(true)
        }
        val before = file.readBytes()
        val store = ReaderStyleStore(directory)
        val existing = requireNotNull(store.load())

        assertTrue(existing.volumeKeysTurnPages)
        assertFalse(existing.animatePageTurns)
        assertTrue(before.contentEquals(file.readBytes()))

        store.save(existing.copy(animatePageTurns = true))
        assertTrue(requireNotNull(store.load()).animatePageTurns)
        DataInputStream(file.inputStream()).use { input ->
            assertEquals(0x57565354, input.readInt())
            assertEquals(5, input.readInt())
        }
    }

    @Test
    fun `palette keeps readable text and rejects low contrast combinations`() {
        val readable = ReaderStyle(
            textColorArgb = 0xFFFFFFFF.toInt(),
            backgroundColorArgb = 0xFF102838.toInt(),
        )
        val unreadable = readable.copy(backgroundColorArgb = 0xFFFAF8F3.toInt())
        assertTrue(readable.hasReadableContrast())
        assertFalse(unreadable.hasReadableContrast())
        assertTrue(ReaderStyleColors.contrast(readable.resolvedTextColor(), readable.resolvedBackgroundColor()) >= 7.0)
        assertTrue(ReaderStyle.DEFAULT.hasReadableContrast())
        assertTrue(ReaderStyle(theme = ReaderTheme.DARK).hasReadableContrast())
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
        assertThrows(TxtImportException::class.java) {
            store.save(ReaderStylePreferences(defaultStyle = ReaderStyle(
                textColorArgb = 0xFFFFFFFF.toInt(), backgroundColorArgb = 0xFFFAF8F3.toInt(),
            )))
        }
        assertThrows(TxtImportException::class.java) {
            store.save(ReaderStylePreferences(defaultStyle = ReaderStyle(textColorArgb = 0x8023221F.toInt())))
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
