package dev.worldviewer.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WorldViewerBackupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `backup includes user data and excludes cache`() {
        val files = fixtureFiles()
        val backupFile = temporary.newFile("full.wvb")
        val backup = WorldViewerBackup(files)

        val created = backup.create(backupFile, includeOriginalTexts = true, createdAtEpochMillis = 1234)
        val inspected = backup.inspect(backupFile)

        assertEquals(created, inspected)
        assertEquals(1234, inspected.createdAtEpochMillis)
        assertEquals(1, inspected.originalTextCount)
        assertEquals(8, inspected.entries.size)
        assertFalse(inspected.entries.any { it.relativePath.contains("cache") })
        assertFalse(inspected.entries.any { it.relativePath == "profileInstalled" })
        assertTrue(inspected.entries.any { it.category == BackupCategory.USER_ANNOTATION })
    }

    @Test
    fun `backup can omit original texts`() {
        val files = fixtureFiles()
        val backupFile = temporary.newFile("without-originals.wvb")

        val preview = WorldViewerBackup(files).create(
            backupFile,
            includeOriginalTexts = false,
            createdAtEpochMillis = 2,
        )

        assertFalse(preview.includesOriginalTexts)
        assertEquals(0, preview.originalTextCount)
        assertEquals(7, preview.entries.size)
        assertFalse(preview.entries.any { it.relativePath.endsWith(".original") })
    }

    @Test
    fun `corrupted payload is rejected`() {
        val files = fixtureFiles()
        val original = temporary.newFile("original.wvb")
        val corrupted = temporary.newFile("corrupted.wvb")
        val backup = WorldViewerBackup(files)
        backup.create(original, includeOriginalTexts = true, createdAtEpochMillis = 3)
        rewriteZip(original, corrupted) { name, bytes ->
            if (name.endsWith(".position")) byteArrayOf(9, 9, 9) else bytes
        }

        val error = assertThrows(BackupFormatException::class.java) { backup.inspect(corrupted) }
        assertTrue(error.message.orEmpty().contains("체크섬"))
    }

    @Test
    fun `unknown extra entry is rejected`() {
        val files = fixtureFiles()
        val original = temporary.newFile("normal.wvb")
        val extra = temporary.newFile("extra.wvb")
        val backup = WorldViewerBackup(files)
        backup.create(original, includeOriginalTexts = false, createdAtEpochMillis = 4)
        ZipFile(original).use { input ->
            ZipOutputStream(extra.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("data/cache/foreign.bin"))
                output.write(byteArrayOf(1))
                output.closeEntry()
            }
        }

        assertThrows(BackupFormatException::class.java) { backup.inspect(extra) }
    }

    @Test
    fun `unsupported manifest version is rejected`() {
        val files = fixtureFiles()
        val original = temporary.newFile("version-one.wvb")
        val future = temporary.newFile("future.wvb")
        val backup = WorldViewerBackup(files)
        backup.create(original, includeOriginalTexts = false, createdAtEpochMillis = 4)
        rewriteZip(original, future) { name, bytes ->
            if (name == "manifest.dat") bytes.clone().apply {
                this[4] = 0
                this[5] = 0
                this[6] = 0
                this[7] = 2
            } else bytes
        }

        val error = assertThrows(BackupFormatException::class.java) { backup.inspect(future) }
        assertTrue(error.message.orEmpty().contains("지원하지 않는 백업 버전"))
    }

    @Test
    fun `failed creation preserves the previous backup`() {
        val files = fixtureFiles()
        val destination = temporary.newFile("existing.wvb")
        val previous = "previous-backup".toByteArray()
        destination.writeBytes(previous)
        val backup = WorldViewerBackup(files)

        assertThrows(BackupFormatException::class.java) {
            backup.createWithOutput(
                destination = destination,
                includeOriginalTexts = true,
                createdAtEpochMillis = 5,
                wrapOutput = { FailingOutputStream(it, failAfterBytes = 30) },
            )
        }

        assertArrayEquals(previous, destination.readBytes())
        assertTrue(requireNotNull(destination.parentFile).listFiles().orEmpty().none { it.name.endsWith(".partial") })
    }

    @Test
    fun `backup destination cannot overwrite app user data`() {
        val files = fixtureFiles()
        val destination = java.io.File(files, "user-meanings/user-meanings.dat")
        val previous = destination.readBytes()

        assertThrows(BackupFormatException::class.java) {
            WorldViewerBackup(files).create(
                destination,
                includeOriginalTexts = true,
                createdAtEpochMillis = 6,
            )
        }

        assertArrayEquals(previous, destination.readBytes())
    }

    private fun fixtureFiles(): java.io.File {
        val root = temporary.newFolder("files-${System.nanoTime()}")
        val hash = "a".repeat(64)
        write(root, "imports/$hash.original", "第一章\n江湖")
        write(root, "imports/current.meta", "metadata")
        write(root, "library/books.catalog", "catalog")
        write(root, "reading-positions/$hash.position", "position")
        write(root, "reader-styles/reader-styles.bin", "style")
        write(root, "manual-chapters/$hash.chapters", "chapters")
        write(root, "user-meanings/user-meanings.dat", "meanings")
        write(root, "user-annotations/user-annotations.dat", "annotations")
        write(root, "cache/text-index/$hash.index", "regenerable")
        write(root, "profileInstalled", "generated")
        return root
    }

    private fun write(root: java.io.File, path: String, text: String) {
        java.io.File(root, path).apply {
            requireNotNull(parentFile).mkdirs()
            writeText(text)
        }
    }

    private fun rewriteZip(
        source: java.io.File,
        destination: java.io.File,
        transform: (String, ByteArray) -> ByteArray,
    ) {
        ZipFile(source).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    output.write(transform(entry.name, bytes))
                    output.closeEntry()
                }
            }
        }
    }

    private class FailingOutputStream(
        output: OutputStream,
        private val failAfterBytes: Int,
    ) : FilterOutputStream(output) {
        private var written = 0

        override fun write(value: Int) {
            failIfNeeded(1)
            out.write(value)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            failIfNeeded(length)
            out.write(buffer, offset, length)
            written += length
        }

        private fun failIfNeeded(nextBytes: Int) {
            if (written + nextBytes > failAfterBytes) throw IOException("fixture failure")
        }
    }
}
