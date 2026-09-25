package dev.worldviewer.backup

import dev.worldviewer.reader.BookCatalogStore
import dev.worldviewer.reader.ImportMetadataStore
import dev.worldviewer.reader.ManualChapterStore
import dev.worldviewer.reader.ReaderStyleStore
import dev.worldviewer.reader.ReadingPositionStore
import dev.worldviewer.reader.UserAnnotationStore
import dev.worldviewer.reader.UserMeaningStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class RestoreConflictPolicy {
    KEEP_EXISTING,
    REPLACE_EXISTING,
    FAIL_ON_CONFLICT,
}

data class RestoreConflict(
    val relativePath: String,
    val existingSha256: String,
    val backupSha256: String,
)

data class RestorePreview(
    val backup: BackupPreview,
    val newPaths: List<String>,
    val unchangedPaths: List<String>,
    val conflicts: List<RestoreConflict>,
)

data class RestoreResult(
    val preview: RestorePreview,
    val appliedPaths: List<String>,
    val skippedPaths: List<String>,
)

@Suppress("UsableSpace")
private fun defaultAvailableBytes(file: File): Long = file.usableSpace

/** Restores verified backup entries with a durable rollback journal. */
class WorldViewerRestore(
    private val filesDirectory: File,
    private val availableBytes: (File) -> Long = ::defaultAvailableBytes,
) {
    private val backupReader = WorldViewerBackup(filesDirectory, availableBytes)

    fun preview(backupFile: File): RestorePreview = synchronized(RESTORE_LOCK) {
        recoverInterruptedLocked()
        compareWithExisting(backupReader.inspect(backupFile))
    }

    fun restore(backupFile: File, policy: RestoreConflictPolicy): RestoreResult =
        restoreWithHook(backupFile, policy) { _, _ -> }

    fun restore(
        backupFile: File,
        policy: RestoreConflictPolicy,
        expectedPreview: RestorePreview,
    ): RestoreResult = restoreWithHook(backupFile, policy, expectedPreview) { _, _ -> }

    /** Rolls back an interrupted commit, or finishes cleanup after a committed restore. */
    fun recoverInterruptedRestore(): Boolean = synchronized(RESTORE_LOCK) {
        recoverInterruptedLocked()
    }

    internal fun restoreWithHook(
        backupFile: File,
        policy: RestoreConflictPolicy,
        expectedPreview: RestorePreview? = null,
        beforeInstall: (relativePath: String, index: Int) -> Unit,
    ): RestoreResult = synchronized(RESTORE_LOCK) {
        recoverInterruptedLocked()
        val initialPreview = compareWithExisting(backupReader.inspect(backupFile))
        if (expectedPreview != null && initialPreview != expectedPreview) {
            throw BackupFormatException("복원 미리보기 이후 백업 또는 기존 데이터가 변경됐습니다. 다시 확인하세요.")
        }
        if (policy == RestoreConflictPolicy.FAIL_ON_CONFLICT && initialPreview.conflicts.isNotEmpty()) {
            throw BackupFormatException("기존 데이터와 충돌하는 백업 항목이 있습니다.")
        }
        val appliedPaths = buildList {
            addAll(initialPreview.newPaths)
            if (policy == RestoreConflictPolicy.REPLACE_EXISTING) {
                addAll(initialPreview.conflicts.map(RestoreConflict::relativePath))
            }
        }.sorted()
        val skippedPaths = buildList {
            addAll(initialPreview.unchangedPaths)
            if (policy == RestoreConflictPolicy.KEEP_EXISTING) {
                addAll(initialPreview.conflicts.map(RestoreConflict::relativePath))
            }
        }.sorted()
        if (appliedPaths.isEmpty()) return@synchronized RestoreResult(initialPreview, emptyList(), skippedPaths)

        val parent = workspaceParent()
        ensureTemporarySpace(parent, initialPreview.backup, appliedPaths.toSet())
        val working = Files.createTempDirectory(parent.toPath(), WORKING_PREFIX).toFile()
        var published = false
        try {
            val staging = File(working, STAGING_DIRECTORY).also(::createDirectory)
            File(working, ROLLBACK_DIRECTORY).also(::createDirectory)
            extractVerified(backupFile, initialPreview.backup, staging, appliedPaths.toSet())
            validateStaged(staging, initialPreview.backup, appliedPaths.toSet())
            if (compareWithExisting(initialPreview.backup) != initialPreview) {
                throw BackupFormatException("미리보기 이후 기존 데이터가 변경되어 복원을 중단했습니다.")
            }

            val journal = appliedPaths.map { path -> JournalEntry(path, checkedTarget(path).exists()) }
            writeJournal(File(working, JOURNAL_FILE), journal)
            val transaction = transactionDirectory()
            if (transaction.exists()) throw BackupFormatException("끝나지 않은 복원 작업이 있습니다.")
            moveAtomically(working, transaction)
            published = true

            try {
                installTransaction(transaction, journal, beforeInstall)
                writeCommitMarker(transaction)
            } catch (error: Exception) {
                try {
                    rollbackTransaction(transaction, journal)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                    throw BackupFormatException(
                        "복원 실패 후 자동 복구도 끝내지 못했습니다. 복구 경로: ${transaction.absolutePath}",
                        error,
                    )
                }
                if (error is BackupFormatException) throw error
                throw BackupFormatException("복원 중 오류가 발생해 기존 데이터를 되돌렸습니다.", error)
            }
            transaction.deleteRecursively()
            RestoreResult(initialPreview, appliedPaths, skippedPaths)
        } finally {
            if (!published) working.deleteRecursively()
        }
    }

    private fun installTransaction(
        transaction: File,
        journal: List<JournalEntry>,
        beforeInstall: (relativePath: String, index: Int) -> Unit,
    ) {
        val staging = File(transaction, STAGING_DIRECTORY)
        val rollback = File(transaction, ROLLBACK_DIRECTORY)
        journal.forEachIndexed { index, entry ->
            beforeInstall(entry.relativePath, index)
            val staged = checkedChild(staging, entry.relativePath)
            val target = checkedTarget(entry.relativePath)
            createDirectory(requireNotNull(target.parentFile))
            if (entry.hadExistingFile) {
                val previous = checkedChild(rollback, entry.relativePath)
                createDirectory(requireNotNull(previous.parentFile))
                moveAtomically(target, previous)
            }
            moveAtomically(staged, target)
        }
    }

    private fun recoverInterruptedLocked(): Boolean {
        val parent = workspaceParent()
        var recovered = false
        parent.listFiles().orEmpty()
            .filter { it.name.startsWith(WORKING_PREFIX) }
            .forEach { stale ->
                if (!stale.deleteRecursively()) {
                    throw BackupFormatException("중단된 복원 준비 파일을 정리할 수 없습니다.")
                }
                recovered = true
            }
        val transaction = transactionDirectory()
        if (!transaction.exists()) return recovered
        if (!transaction.isDirectory) throw BackupFormatException("복원 복구 경로가 폴더가 아닙니다.")
        if (File(transaction, COMMITTED_FILE).isFile) {
            if (!transaction.deleteRecursively()) {
                throw BackupFormatException("완료된 복원 임시 파일을 정리할 수 없습니다.")
            }
            return true
        }
        val journal = readJournal(File(transaction, JOURNAL_FILE))
        rollbackTransaction(transaction, journal)
        return true
    }

    private fun rollbackTransaction(transaction: File, journal: List<JournalEntry>) {
        val rollback = File(transaction, ROLLBACK_DIRECTORY)
        journal.asReversed().forEach { entry ->
            val target = checkedTarget(entry.relativePath)
            val previous = checkedChild(rollback, entry.relativePath)
            if (entry.hadExistingFile) {
                if (previous.exists()) {
                    if (target.exists() && (!target.isFile || !target.delete())) {
                        throw BackupFormatException("실패한 복원 파일을 제거하지 못했습니다: ${entry.relativePath}")
                    }
                    createDirectory(requireNotNull(target.parentFile))
                    moveAtomically(previous, target)
                } else if (!target.isFile) {
                    throw BackupFormatException("기존 파일을 자동 복구할 수 없습니다: ${entry.relativePath}")
                }
            } else if (target.exists() && (!target.isFile || !target.delete())) {
                throw BackupFormatException("실패한 새 복원 파일을 제거하지 못했습니다: ${entry.relativePath}")
            }
        }
        if (!transaction.deleteRecursively()) {
            throw BackupFormatException("복원 임시 파일을 정리할 수 없습니다.")
        }
    }

    private fun compareWithExisting(backup: BackupPreview): RestorePreview {
        val newPaths = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val conflicts = mutableListOf<RestoreConflict>()
        backup.entries.forEach { entry ->
            val target = checkedTarget(entry.relativePath)
            when {
                !target.exists() -> newPaths += entry.relativePath
                !target.isFile -> throw BackupFormatException("복원 대상이 파일이 아닙니다: ${entry.relativePath}")
                else -> {
                    val existingHash = sha256(target)
                    if (existingHash == entry.sha256) {
                        unchanged += entry.relativePath
                    } else {
                        conflicts += RestoreConflict(entry.relativePath, existingHash, entry.sha256)
                    }
                }
            }
        }
        return RestorePreview(backup, newPaths.sorted(), unchanged.sorted(), conflicts.sortedBy { it.relativePath })
    }

    private fun extractVerified(
        backupFile: File,
        preview: BackupPreview,
        staging: File,
        appliedPaths: Set<String>,
    ) {
        try {
            ZipFile(backupFile).use { zip ->
                preview.entries.filter { it.relativePath in appliedPaths }.forEach { metadata ->
                    val entry = zip.getEntry("data/${metadata.relativePath}")
                        ?: throw BackupFormatException("복원할 백업 항목이 없습니다: ${metadata.relativePath}")
                    val target = checkedChild(staging, metadata.relativePath)
                    createDirectory(requireNotNull(target.parentFile))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.getInputStream(entry).buffered().use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                size += read
                                if (size > metadata.sizeBytes) {
                                    throw BackupFormatException("복원 항목 크기가 목록과 다릅니다: ${metadata.relativePath}")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (size != metadata.sizeBytes || digest.hexDigest() != metadata.sha256) {
                        throw BackupFormatException("복원 항목 체크섬이 일치하지 않습니다: ${metadata.relativePath}")
                    }
                }
            }
        } catch (error: Exception) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException("백업 항목을 복원 준비 영역에 풀 수 없습니다.", error)
        }
    }

    private fun validateStaged(staging: File, preview: BackupPreview, appliedPaths: Set<String>) {
        try {
            preview.entries.filter { it.relativePath in appliedPaths }.forEach { entry ->
                when (entry.category) {
                    BackupCategory.ORIGINAL_TEXT -> {
                        val expected = entry.relativePath.substringAfter('/').substringBefore('.')
                        if (entry.sha256 != expected) {
                            throw BackupFormatException("원문 이름과 체크섬이 일치하지 않습니다: ${entry.relativePath}")
                        }
                    }
                    BackupCategory.IMPORT_METADATA ->
                        checkNotNull(ImportMetadataStore(File(staging, "imports")).load())
                    BackupCategory.BOOK_CATALOG ->
                        checkNotNull(BookCatalogStore(File(staging, "library")).load())
                    BackupCategory.READING_POSITION -> {
                        val document = entry.relativePath.substringAfter('/').substringBefore('.')
                        checkNotNull(ReadingPositionStore(File(staging, "reading-positions")).load(document))
                    }
                    BackupCategory.READER_STYLE ->
                        checkNotNull(ReaderStyleStore(File(staging, "reader-styles")).load())
                    BackupCategory.MANUAL_CHAPTER -> {
                        val document = entry.relativePath.substringAfter('/').substringBefore('.')
                        ManualChapterStore(File(staging, "manual-chapters")).load(document)
                    }
                    BackupCategory.USER_MEANING -> UserMeaningStore(File(staging, "user-meanings")).load()
                    BackupCategory.USER_ANNOTATION -> UserAnnotationStore(File(staging, "user-annotations")).load()
                }
            }
            validateMetadataOriginal(staging, appliedPaths)
            validateCatalogOriginals(staging, appliedPaths)
        } catch (error: Exception) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException("백업 내부 데이터 형식이 올바르지 않습니다.", error)
        }
    }

    private fun validateMetadataOriginal(staging: File, appliedPaths: Set<String>) {
        if ("imports/current.meta" !in appliedPaths) return
        val metadata = checkNotNull(ImportMetadataStore(File(staging, "imports")).load())
        validateEffectiveOriginal(staging, appliedPaths, metadata.storedName, metadata.sha256, metadata.byteCount)
    }

    private fun validateCatalogOriginals(staging: File, appliedPaths: Set<String>) {
        if ("library/books.catalog" !in appliedPaths) return
        val catalog = checkNotNull(BookCatalogStore(File(staging, "library")).load())
        catalog.books.forEach { book ->
            validateEffectiveOriginal(staging, appliedPaths, book.storedName, book.documentSha256, book.byteCount)
        }
    }

    private fun validateEffectiveOriginal(
        staging: File,
        appliedPaths: Set<String>,
        storedName: String,
        expectedSha256: String,
        expectedBytes: Long,
    ) {
        val path = "imports/$storedName"
        val original = if (path in appliedPaths) checkedChild(staging, path) else checkedTarget(path)
        if (!original.isFile || original.length() != expectedBytes || sha256(original) != expectedSha256) {
            throw BackupFormatException("책 목록이 참조하는 원문이 없거나 일치하지 않습니다: $storedName")
        }
    }

    private fun writeJournal(file: File, entries: List<JournalEntry>) {
        FileOutputStream(file).use { fileOutput ->
            val output = DataOutputStream(BufferedOutputStream(fileOutput))
            try {
                output.writeInt(JOURNAL_MAGIC)
                output.writeInt(JOURNAL_VERSION)
                output.writeInt(entries.size)
                entries.forEach { entry ->
                    output.writeUTF(entry.relativePath)
                    output.writeBoolean(entry.hadExistingFile)
                }
                output.flush()
                fileOutput.fd.sync()
            } finally {
                output.close()
            }
        }
    }

    private fun readJournal(file: File): List<JournalEntry> {
        try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != JOURNAL_MAGIC || input.readInt() != JOURNAL_VERSION) {
                    throw BackupFormatException("복원 복구 정보 형식을 지원하지 않습니다.")
                }
                val count = input.readInt()
                if (count !in 1..MAX_JOURNAL_ENTRIES) {
                    throw BackupFormatException("복원 복구 항목 수가 올바르지 않습니다.")
                }
                return List(count) {
                    JournalEntry(input.readUTF(), input.readBoolean()).also { entry ->
                        checkedTarget(entry.relativePath)
                    }
                }.also { entries ->
                    if (entries.map(JournalEntry::relativePath).toSet().size != entries.size) {
                        throw BackupFormatException("복원 복구 항목이 중복됐습니다.")
                    }
                }
            }
        } catch (error: Exception) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException("복원 복구 정보를 읽을 수 없습니다.", error)
        }
    }

    private fun writeCommitMarker(transaction: File) {
        FileOutputStream(File(transaction, COMMITTED_FILE)).use { output ->
            output.write(1)
            output.fd.sync()
        }
    }

    private fun workspaceParent(): File = filesDirectory.absoluteFile.parentFile
        ?.also(::createDirectory)
        ?: throw BackupFormatException("복원 작업 공간을 만들 수 없습니다.")

    private fun transactionDirectory(): File = File(workspaceParent(), TRANSACTION_DIRECTORY)

    private fun createDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw BackupFormatException("복원 작업 폴더를 만들 수 없습니다: ${directory.name}")
        }
        if (!directory.isDirectory) throw BackupFormatException("복원 작업 경로가 폴더가 아닙니다: ${directory.name}")
    }

    private fun ensureTemporarySpace(parent: File, preview: BackupPreview, appliedPaths: Set<String>) {
        val contentBytes = preview.entries
            .asSequence()
            .filter { it.relativePath in appliedPaths }
            .sumOf(BackupEntry::sizeBytes)
        val required = Math.addExact(contentBytes, TEMPORARY_SPACE_MARGIN_BYTES)
        if (availableBytes(parent) < required) {
            throw BackupFormatException("복원 준비 공간이 부족합니다. 기기 저장 공간을 확보한 뒤 다시 시도하세요.")
        }
    }

    private fun checkedTarget(relativePath: String): File {
        val root = filesDirectory.canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw BackupFormatException("복원 대상 경로가 안전하지 않습니다: $relativePath")
        }
        return target
    }

    private fun checkedChild(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val child = File(canonicalRoot, relativePath).canonicalFile
        if (!child.path.startsWith(canonicalRoot.path + File.separator)) {
            throw BackupFormatException("복원 임시 경로가 안전하지 않습니다: $relativePath")
        }
        return child
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.hexDigest()
    }

    private fun MessageDigest.hexDigest(): String = digest().joinToString("") { "%02x".format(it) }

    private data class JournalEntry(val relativePath: String, val hadExistingFile: Boolean)

    private companion object {
        val RESTORE_LOCK = Any()
        const val BUFFER_SIZE = 64 * 1024
        const val JOURNAL_MAGIC = 0x5756524A
        const val JOURNAL_VERSION = 1
        const val MAX_JOURNAL_ENTRIES = 500_000
        const val WORKING_PREFIX = "worldviewer-restore-working-"
        const val TRANSACTION_DIRECTORY = "worldviewer-restore-transaction"
        const val STAGING_DIRECTORY = "stage"
        const val ROLLBACK_DIRECTORY = "rollback"
        const val JOURNAL_FILE = "journal.dat"
        const val COMMITTED_FILE = "committed"
        const val TEMPORARY_SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
