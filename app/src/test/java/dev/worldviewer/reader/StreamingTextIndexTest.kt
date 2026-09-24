package dev.worldviewer.reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextIndexTest {
    private val contentId = "c".repeat(64)

    @Test
    fun `streamed blocks reconstruct source without splitting surrogate or combining sequence`() {
        val directory = Files.createTempDirectory("worldviewer-index").toFile()
        val source = "第一章\n\nA𠮷e\u0301🙂大家好\r\n끝"
        val original = directory.resolve("original").apply {
            writeBytes(source.toByteArray(StandardCharsets.UTF_8))
        }

        val summary = StreamingTextIndex.build(original, directory.resolve("cache"), contentId, "UTF-8", 5)
        val reconstructed = StreamingTextIndex.Reader(summary.indexFile, summary.dataFile).use { reader ->
            assertEquals(source.length, reader.utf16Length)
            (0 until reader.blockCount).joinToString("") { position ->
                reader.blockAt(position).also { block ->
                    assertFalse(block.text.first().isLowSurrogate())
                    assertFalse(block.text.last().isHighSurrogate())
                    if (position > 0) assertFalse(block.text.first() == '\u0301')
                }.text
            }
        }

        assertEquals(source, reconstructed)
        assertArrayEquals(source.toByteArray(StandardCharsets.UTF_8), original.readBytes())
        assertFalse(summary.indexFile.parentFile!!.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `utf16 bom is removed only from decoded block data`() {
        val directory = Files.createTempDirectory("worldviewer-index").toFile()
        val body = "江湖\n夜雨".toByteArray(StandardCharsets.UTF_16BE)
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body
        val original = directory.resolve("original").apply { writeBytes(bytes) }

        val summary = StreamingTextIndex.build(original, directory.resolve("cache"), contentId, "UTF-16BE BOM", 4)
        val reconstructed = StreamingTextIndex.Reader(summary.indexFile, summary.dataFile).use { reader ->
            (0 until reader.blockCount).joinToString("") { reader.blockAt(it).text }
        }

        assertEquals("江湖\n夜雨", reconstructed)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun `finds block containing a global utf16 offset`() {
        val directory = Files.createTempDirectory("worldviewer-index-position").toFile()
        val source = "甲乙丙丁戊己庚辛"
        val original = directory.resolve("original").apply { writeText(source) }
        val summary = StreamingTextIndex.build(
            original,
            directory.resolve("cache"),
            contentId,
            "UTF-8",
            maxBlockUtf16 = 2,
        )

        StreamingTextIndex.Reader(summary.indexFile, summary.dataFile).use { reader ->
            assertEquals(0, reader.positionForUtf16(0))
            assertEquals(1, reader.positionForUtf16(2))
            assertEquals(3, reader.positionForUtf16(source.length - 1))
        }
    }

    @Test
    fun `ten mebibyte source stays in bounded blocks`() {
        val directory = Files.createTempDirectory("worldviewer-index").toFile()
        val original = directory.resolve("original")
        val unit = "大家好。江湖夜雨。\n".toByteArray(StandardCharsets.UTF_8)
        val chunk = ByteArray(unit.size * 1024) { unit[it % unit.size] }
        val target = (10L * 1024L * 1024L / unit.size) * unit.size
        original.outputStream().buffered().use { output ->
            var written = 0L
            while (written < target) {
                val count = minOf(chunk.size.toLong(), target - written).toInt()
                output.write(chunk, 0, count)
                written += count
            }
        }

        val summary = StreamingTextIndex.build(original, directory.resolve("cache"), contentId, "UTF-8")

        assertTrue(summary.blockCount > 1_000)
        StreamingTextIndex.Reader(summary.indexFile, summary.dataFile).use { reader ->
            assertEquals(summary.blockCount, reader.blockCount)
            assertTrue((0 until reader.blockCount).all { reader.blockAt(it).text.length <= 768 })
        }
    }

    @Test
    fun `wrong bom cleans partial outputs`() {
        val directory = Files.createTempDirectory("worldviewer-index").toFile()
        val original = directory.resolve("original").apply { writeText("not utf16") }
        val cache = directory.resolve("cache")

        assertThrows(TxtImportException::class.java) {
            StreamingTextIndex.build(original, cache, contentId, "UTF-16LE BOM")
        }

        assertFalse(cache.listFiles().orEmpty().any())
    }
}
