package dev.worldviewer.reader

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtImporterTest {
    @Test
    fun `preserves utf8 source bytes and reports stable hash`() {
        val directory = Files.createTempDirectory("worldviewer-import").toFile()
        val source = "第一章\n\n大家好🙂".toByteArray(StandardCharsets.UTF_8)

        val imported = TxtImporter.import(ByteArrayInputStream(source), directory)

        assertEquals("UTF-8", imported.encoding)
        assertEquals(source.size.toLong(), imported.byteCount)
        assertEquals(64, imported.sha256.length)
        assertArrayEquals(source, imported.storedFile.readBytes())
        assertTrue(imported.storedFile.name.endsWith(".original"))
    }

    @Test
    fun `decodes utf16 bom without adding bom to text`() {
        val directory = Files.createTempDirectory("worldviewer-import").toFile()
        val body = "江湖".toByteArray(StandardCharsets.UTF_16LE)
        val source = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body

        val imported = TxtImporter.import(ByteArrayInputStream(source), directory)

        assertEquals("UTF-16LE BOM", imported.encoding)
        assertArrayEquals(source, imported.storedFile.readBytes())
    }

    @Test
    fun `invalid utf8 leaves no partial or original file`() {
        val directory = Files.createTempDirectory("worldviewer-import").toFile()

        assertThrows(TxtImportException::class.java) {
            TxtImporter.import(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), directory)
        }

        assertFalse(directory.listFiles().orEmpty().any())
    }

    @Test
    fun `size failure leaves no partial or original file`() {
        val directory = Files.createTempDirectory("worldviewer-import").toFile()

        assertThrows(TxtImportException::class.java) {
            TxtImporter.import(ByteArrayInputStream(ByteArray(9)), directory, maxBytes = 8)
        }

        assertFalse(directory.listFiles().orEmpty().any())
    }

    @Test
    fun `same bytes reuse content addressed original`() {
        val directory = Files.createTempDirectory("worldviewer-import").toFile()
        val source = "相同".toByteArray(StandardCharsets.UTF_8)

        val first = TxtImporter.import(ByteArrayInputStream(source), directory)
        val second = TxtImporter.import(ByteArrayInputStream(source), directory)

        assertEquals(first.storedFile, second.storedFile)
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `stored file inspection streams identity and detects bom without decoding text`() {
        val directory = Files.createTempDirectory("worldviewer-inspect").toFile()
        val body = "江湖".toByteArray(StandardCharsets.UTF_16BE)
        val source = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body
        val stored = directory.resolve("stored.original").apply { writeBytes(source) }

        val inspected = TxtImporter.inspectStored(stored)

        assertEquals(stored, inspected.storedFile)
        assertEquals(source.size.toLong(), inspected.byteCount)
        assertEquals("UTF-16BE BOM", inspected.encoding)
        assertEquals(64, inspected.sha256.length)
    }

    @Test
    fun `stored file inspection enforces limit without changing original`() {
        val directory = Files.createTempDirectory("worldviewer-inspect-limit").toFile()
        val stored = directory.resolve("stored.original").apply { writeBytes(ByteArray(9)) }

        assertThrows(TxtImportException::class.java) {
            TxtImporter.inspectStored(stored, maxBytes = 8)
        }

        assertEquals(9L, stored.length())
    }

    @Test
    fun `streaming import accepts source larger than former eight mebibyte limit`() {
        val directory = Files.createTempDirectory("worldviewer-large-import").toFile()
        val size = 9 * 1024 * 1024
        val source = ByteArray(size) { 'A'.code.toByte() }

        val imported = TxtImporter.import(ByteArrayInputStream(source), directory)

        assertEquals(size.toLong(), imported.byteCount)
        assertEquals(size.toLong(), imported.storedFile.length())
        assertEquals("UTF-8", imported.encoding)
    }

    @Test
    fun `selected gb18030 candidate commits original and builds readable index`() {
        val directory = Files.createTempDirectory("worldviewer-gb18030").toFile()
        val source = "简体中文：龙门客栈。"
        val bytes = source.toByteArray(Charset.forName("GB18030"))
        val staged = TxtImporter.stage(ByteArrayInputStream(bytes), directory.resolve("imports"))

        assertTrue(staged.candidates.any { it.encoding == "GB18030" && source in it.preview })
        val imported = TxtImporter.commit(staged, "GB18030")
        val summary = StreamingTextIndex.build(
            imported.storedFile,
            directory.resolve("index"),
            imported.sha256,
            imported.encoding,
        )
        val reconstructed = StreamingTextIndex.Reader(summary.indexFile, summary.dataFile).use { reader ->
            (0 until reader.blockCount).joinToString("") { reader.blockAt(it).text }
        }

        assertEquals(source, reconstructed)
        assertArrayEquals(bytes, imported.storedFile.readBytes())
    }

    @Test
    fun `closing an unconfirmed staged import removes only its partial file`() {
        val directory = Files.createTempDirectory("worldviewer-staged-cancel").toFile()
        val imports = directory.resolve("imports")
        val source = "繁體中文：龍門客棧。".toByteArray(Charset.forName("Big5"))
        val staged = TxtImporter.stage(ByteArrayInputStream(source), imports)

        assertTrue(imports.listFiles().orEmpty().single().name.endsWith(".partial"))
        staged.close()

        assertFalse(imports.listFiles().orEmpty().any())
    }

    @Test
    fun `copy progress can cancel import and removes its partial file`() {
        val imports = Files.createTempDirectory("worldviewer-copy-cancel").toFile()
        val source = ByteArray(1024 * 1024) { 'A'.code.toByte() }
        val cancellation = ImportCancellation()
        val progress = mutableListOf<ImportProgress>()

        assertThrows(TxtImportCancelledException::class.java) {
            TxtImporter.stage(
                input = ByteArrayInputStream(source),
                destinationDirectory = imports,
                totalBytes = source.size.toLong(),
                cancellation = cancellation,
                onProgress = {
                    progress += it
                    cancellation.cancel()
                },
            )
        }

        assertTrue(progress.any { it.phase == ImportProgressPhase.COPYING && it.processedBytes > 0L })
        assertFalse(imports.listFiles().orEmpty().any())
    }

    @Test
    fun `validation progress can cancel commit and removes its partial file`() {
        val imports = Files.createTempDirectory("worldviewer-validation-cancel").toFile()
        val staged = TxtImporter.stage(
            ByteArrayInputStream(ByteArray(1024 * 1024) { 'A'.code.toByte() }),
            imports,
        )
        val cancellation = ImportCancellation()

        assertThrows(TxtImportCancelledException::class.java) {
            TxtImporter.commit(
                staged = staged,
                encoding = "UTF-8",
                cancellation = cancellation,
                onProgress = { progress ->
                    if (progress.phase == ImportProgressPhase.VALIDATING) cancellation.cancel()
                },
            )
        }

        assertFalse(imports.listFiles().orEmpty().any())
    }

    @Test
    fun `copy and validation report their phase boundaries`() {
        val imports = Files.createTempDirectory("worldviewer-import-progress").toFile()
        val source = ByteArray(512 * 1024) { 'A'.code.toByte() }
        val progress = mutableListOf<ImportProgress>()

        val staged = TxtImporter.stage(
            input = ByteArrayInputStream(source),
            destinationDirectory = imports,
            totalBytes = source.size.toLong(),
            onProgress = { progress += it },
        )
        TxtImporter.commit(staged, "UTF-8", onProgress = { progress += it })

        assertTrue(progress.any {
            it.phase == ImportProgressPhase.COPYING && it.processedBytes == source.size.toLong()
        })
        assertTrue(progress.any {
            it.phase == ImportProgressPhase.VALIDATING && it.processedBytes == 0L
        })
        assertTrue(progress.any {
            it.phase == ImportProgressPhase.VALIDATING && it.processedBytes == source.size.toLong()
        })
    }

    @Test
    fun `storage write failure removes partial and preserves existing original`() {
        val imports = Files.createTempDirectory("worldviewer-storage-failure").toFile()
        val existing = imports.resolve("existing.original").apply { writeText("keep") }
        val source = ByteArray(1024) { 'A'.code.toByte() }

        assertThrows(TxtImportException::class.java) {
            TxtImporter.stageWithOutput(
                input = ByteArrayInputStream(source),
                destinationDirectory = imports,
                openOutput = { file ->
                    val delegate = file.outputStream()
                    object : OutputStream() {
                        private var written = 0

                        override fun write(value: Int) {
                            if (written >= 32) throw IOException("simulated storage full")
                            delegate.write(value)
                            written++
                        }

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            val allowed = minOf(length, 32 - written)
                            if (allowed > 0) {
                                delegate.write(bytes, offset, allowed)
                                written += allowed
                            }
                            throw IOException("simulated storage full")
                        }

                        override fun close() = delegate.close()
                    }
                },
            )
        }

        assertEquals("keep", existing.readText())
        assertEquals(listOf("existing.original"), imports.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `startup cleanup removes only abandoned import partials`() {
        val imports = Files.createTempDirectory("worldviewer-stale-partials").toFile()
        imports.resolve("incoming-old.partial").writeText("abandoned")
        imports.resolve("other.partial").writeText("unrelated")
        imports.resolve("kept.original").writeText("original")

        val removed = TxtImporter.cleanStalePartials(imports)

        assertEquals(1, removed)
        assertFalse(imports.resolve("incoming-old.partial").exists())
        assertTrue(imports.resolve("other.partial").exists())
        assertTrue(imports.resolve("kept.original").exists())
    }
}
