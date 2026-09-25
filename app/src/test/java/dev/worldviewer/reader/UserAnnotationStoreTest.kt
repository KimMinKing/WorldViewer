package dev.worldviewer.reader

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAnnotationStoreTest {
    private val document = "a".repeat(64)

    @Test
    fun `bookmark highlight and note round trip with source anchors`() {
        val store = UserAnnotationStore(Files.createTempDirectory("worldviewer-annotations").toFile())
        val values = listOf(
            annotation(1, AnnotationType.BOOKMARK, 10, 10),
            annotation(2, AnnotationType.HIGHLIGHT, 20, 24),
            annotation(3, AnnotationType.NOTE, 30, 34, "인물 설명"),
        )

        store.save(values)

        assertEquals(values, store.load())
    }

    @Test
    fun `failed replacement preserves prior annotations`() {
        val directory = Files.createTempDirectory("worldviewer-annotation-failure").toFile()
        val store = UserAnnotationStore(directory)
        val original = listOf(annotation(1, AnnotationType.HIGHLIGHT, 20, 24))
        store.save(original)

        assertThrows(TxtImportException::class.java) {
            store.saveWithOutput(listOf(annotation(2, AnnotationType.BOOKMARK, 1, 1))) { delegate ->
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
    fun `invalid type payload and duplicate ids cannot replace data`() {
        val store = UserAnnotationStore(Files.createTempDirectory("worldviewer-annotation-invalid").toFile())
        val original = annotation(1, AnnotationType.HIGHLIGHT, 20, 24)
        store.save(listOf(original))

        assertThrows(TxtImportException::class.java) {
            store.save(listOf(original.copy(type = AnnotationType.NOTE)))
        }
        assertThrows(TxtImportException::class.java) { store.save(listOf(original, original)) }
        assertEquals(listOf(original), store.load())
    }

    @Test
    fun `cleanup removes only annotation partials`() {
        val directory = Files.createTempDirectory("worldviewer-annotation-partial").toFile()
        directory.resolve("annotations-old.partial").writeText("partial")
        directory.resolve("meanings-old.partial").writeText("meaning")

        assertEquals(1, UserAnnotationStore(directory).cleanStalePartials())
        assertFalse(directory.resolve("annotations-old.partial").exists())
        assertTrue(directory.resolve("meanings-old.partial").exists())
    }

    private fun annotation(
        number: Int,
        type: AnnotationType,
        start: Int,
        end: Int,
        note: String? = null,
    ) = UserAnnotation(
        id = number.toString().padStart(8, '0') + "-1111-1111-1111-111111111111",
        type = type,
        documentSha256 = document,
        startUtf16 = start,
        endUtf16 = end,
        quoteBefore = "앞",
        quoteSelected = if (end > start) "원문" else "",
        quoteAfter = "뒤",
        note = note,
        createdAtEpochMillis = number.toLong(),
        updatedAtEpochMillis = number.toLong(),
    )
}
