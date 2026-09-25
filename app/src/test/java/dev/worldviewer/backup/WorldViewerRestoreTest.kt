package dev.worldviewer.backup

import dev.worldviewer.reader.AnnotationType
import dev.worldviewer.reader.BookCatalog
import dev.worldviewer.reader.BookCatalogEntry
import dev.worldviewer.reader.BookCatalogStore
import dev.worldviewer.reader.ImportMetadata
import dev.worldviewer.reader.ImportMetadataStore
import dev.worldviewer.reader.MeaningScope
import dev.worldviewer.reader.ReadingPosition
import dev.worldviewer.reader.ReadingPositionStore
import dev.worldviewer.reader.UserAnnotation
import dev.worldviewer.reader.UserAnnotationStore
import dev.worldviewer.reader.UserMeaning
import dev.worldviewer.reader.UserMeaningStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class WorldViewerRestoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `new data restores after preview`() {
        val source = dataRoot("source", meaning = "강호")
        val backupFile = temporary.newFile("backup.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 1)
        val target = temporary.newFolder("target")
        val restore = WorldViewerRestore(target)

        val preview = restore.preview(backupFile)
        val result = restore.restore(backupFile, RestoreConflictPolicy.REPLACE_EXISTING)

        assertEquals(2, preview.newPaths.size)
        assertTrue(preview.conflicts.isEmpty())
        assertEquals(preview.newPaths, result.appliedPaths)
        assertEquals("강호", UserMeaningStore(File(target, "user-meanings")).load().single().meaning)
        assertEquals("메모", UserAnnotationStore(File(target, "user-annotations")).load().single().note)
    }

    @Test
    fun `keep existing skips conflicts while replace installs backup`() {
        val source = dataRoot("source-policy", meaning = "백업 뜻")
        val backupFile = temporary.newFile("policy.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 2)
        val target = dataRoot("target-policy", meaning = "현재 뜻")
        val restore = WorldViewerRestore(target)

        val preview = restore.preview(backupFile)
        assertEquals(listOf("user-meanings/user-meanings.dat"), preview.conflicts.map { it.relativePath })
        restore.restore(backupFile, RestoreConflictPolicy.KEEP_EXISTING)
        assertEquals("현재 뜻", UserMeaningStore(File(target, "user-meanings")).load().single().meaning)

        restore.restore(backupFile, RestoreConflictPolicy.REPLACE_EXISTING)
        assertEquals("백업 뜻", UserMeaningStore(File(target, "user-meanings")).load().single().meaning)
    }

    @Test
    fun `fail policy changes nothing when conflicts exist`() {
        val source = dataRoot("source-fail", meaning = "백업")
        val backupFile = temporary.newFile("fail-conflict.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 3)
        val target = dataRoot("target-fail", meaning = "현재")
        val before = snapshot(target)

        assertThrows(BackupFormatException::class.java) {
            WorldViewerRestore(target).restore(backupFile, RestoreConflictPolicy.FAIL_ON_CONFLICT)
        }

        assertSnapshot(before, target)
    }

    @Test
    fun `interrupted replacement rolls back files already installed`() {
        val source = dataRoot("source-rollback", meaning = "백업")
        val backupFile = temporary.newFile("rollback.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 4)
        val target = dataRoot("target-rollback", meaning = "현재")
        File(target, "user-annotations").deleteRecursively()
        dataRootAnnotations(target, note = "현재 메모")
        val before = snapshot(target)

        assertThrows(BackupFormatException::class.java) {
            WorldViewerRestore(target).restoreWithHook(
                backupFile,
                RestoreConflictPolicy.REPLACE_EXISTING,
            ) { _, index ->
                if (index == 1) throw IllegalStateException("fixture interruption")
            }
        }

        assertSnapshot(before, target)
    }

    @Test
    fun `process death during commit is recovered on next startup`() {
        val source = dataRoot("source-process-death", meaning = "백업")
        val backupFile = temporary.newFile("process-death.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 8)
        val target = dataRoot("target-process-death", meaning = "현재")
        File(target, "user-annotations").deleteRecursively()
        dataRootAnnotations(target, note = "현재 메모")
        val before = snapshot(target)

        assertThrows(SimulatedProcessDeath::class.java) {
            WorldViewerRestore(target).restoreWithHook(
                backupFile,
                RestoreConflictPolicy.REPLACE_EXISTING,
            ) { _, index ->
                if (index == 1) throw SimulatedProcessDeath()
            }
        }

        val nextStartup = WorldViewerRestore(target)
        assertTrue(nextStartup.recoverInterruptedRestore())
        assertSnapshot(before, target)
        assertTrue(!nextStartup.recoverInterruptedRestore())
    }

    @Test
    fun `malformed store is rejected before current data changes`() {
        val source = temporary.newFolder("source-malformed")
        File(source, "user-meanings").apply { mkdirs() }
            .resolve("user-meanings.dat").writeBytes(byteArrayOf(1, 2, 3))
        val backupFile = temporary.newFile("malformed.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 5)
        val target = dataRoot("target-malformed", meaning = "보존")
        val before = snapshot(target)

        assertThrows(BackupFormatException::class.java) {
            WorldViewerRestore(target).restore(backupFile, RestoreConflictPolicy.REPLACE_EXISTING)
        }

        assertSnapshot(before, target)
    }

    @Test
    fun `catalog without effective original is rejected`() {
        val source = temporary.newFolder("source-catalog")
        val missingHash = "a".repeat(64)
        BookCatalogStore(File(source, "library")).save(
            BookCatalog(
                currentBookId = missingHash,
                books = listOf(
                    BookCatalogEntry(
                        bookId = missingHash,
                        documentSha256 = missingHash,
                        storedName = "$missingHash.original",
                        displayName = "없는책.txt",
                        byteCount = 10,
                        encoding = "UTF-8",
                        addedAtEpochMillis = 1,
                        lastOpenedAtEpochMillis = 1,
                    ),
                ),
            ),
        )
        val backupFile = temporary.newFile("missing-original.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = false, createdAtEpochMillis = 6)
        val target = temporary.newFolder("target-catalog")

        val error = assertThrows(BackupFormatException::class.java) {
            WorldViewerRestore(target).restore(backupFile, RestoreConflictPolicy.REPLACE_EXISTING)
        }
        assertTrue(error.message.orEmpty().contains("원문"))
        assertTrue(target.walkTopDown().none { it.isFile })
    }

    @Test
    fun `original metadata catalog and reading position round trip exactly`() {
        val source = temporary.newFolder("source-round-trip")
        val originalBytes = "第一章\r\n江湖의 시작".toByteArray(Charsets.UTF_8)
        val documentHash = sha256(originalBytes)
        val storedName = "$documentHash.original"
        File(source, "imports/$storedName").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(originalBytes)
        }
        val metadata = ImportMetadata(
            storedName = storedName,
            displayName = "무협.txt",
            sha256 = documentHash,
            byteCount = originalBytes.size.toLong(),
            encoding = "UTF-8",
        )
        val catalog = BookCatalog(
            currentBookId = documentHash,
            books = listOf(
                BookCatalogEntry(
                    bookId = documentHash,
                    documentSha256 = documentHash,
                    storedName = storedName,
                    displayName = metadata.displayName,
                    byteCount = metadata.byteCount,
                    encoding = metadata.encoding,
                    addedAtEpochMillis = 10,
                    lastOpenedAtEpochMillis = 20,
                ),
            ),
        )
        val position = ReadingPosition(
            documentSha256 = documentHash,
            blockIndex = 0,
            utf16Offset = 4,
            quoteBefore = "第一章",
            quoteAfter = "江湖",
        )
        ImportMetadataStore(File(source, "imports")).save(metadata)
        BookCatalogStore(File(source, "library")).save(catalog)
        ReadingPositionStore(File(source, "reading-positions")).save(position)
        val backupFile = temporary.newFile("round-trip.wvb")
        WorldViewerBackup(source).create(backupFile, includeOriginalTexts = true, createdAtEpochMillis = 7)
        val target = temporary.newFolder("target-round-trip")

        val result = WorldViewerRestore(target).restore(backupFile, RestoreConflictPolicy.REPLACE_EXISTING)

        assertEquals(4, result.appliedPaths.size)
        assertArrayEquals(originalBytes, File(target, "imports/$storedName").readBytes())
        assertEquals(metadata, ImportMetadataStore(File(target, "imports")).load())
        assertEquals(catalog, BookCatalogStore(File(target, "library")).load())
        assertEquals(position, ReadingPositionStore(File(target, "reading-positions")).load(documentHash))
    }

    private fun dataRoot(name: String, meaning: String): File {
        val root = temporary.newFolder(name)
        UserMeaningStore(File(root, "user-meanings")).save(
            listOf(
                UserMeaning(
                    id = "00000000-0000-0000-0000-000000000001",
                    sourceText = "江湖",
                    meaning = meaning,
                    scope = MeaningScope.GLOBAL,
                    documentSha256 = null,
                    rangeStartUtf16 = null,
                    rangeEndUtf16 = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )
        dataRootAnnotations(root, note = "메모")
        return root
    }

    private fun dataRootAnnotations(root: File, note: String) {
        UserAnnotationStore(File(root, "user-annotations")).save(
            listOf(
                UserAnnotation(
                    id = "00000000-0000-0000-0000-000000000002",
                    type = AnnotationType.NOTE,
                    documentSha256 = "b".repeat(64),
                    startUtf16 = 1,
                    endUtf16 = 2,
                    quoteBefore = "",
                    quoteSelected = "江",
                    quoteAfter = "湖",
                    note = note,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )
    }

    private fun snapshot(root: File): Map<String, ByteArray> = root.walkTopDown()
        .filter(File::isFile)
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }

    private fun assertSnapshot(expected: Map<String, ByteArray>, root: File) {
        val actual = snapshot(root)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertArrayEquals(path, bytes, requireNotNull(actual[path])) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class SimulatedProcessDeath : Error()
}
