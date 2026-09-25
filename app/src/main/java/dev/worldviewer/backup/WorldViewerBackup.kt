package dev.worldviewer.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class BackupCategory {
    ORIGINAL_TEXT,
    IMPORT_METADATA,
    BOOK_CATALOG,
    READING_POSITION,
    READER_STYLE,
    MANUAL_CHAPTER,
    USER_MEANING,
    USER_ANNOTATION,
}

data class BackupEntry(
    val relativePath: String,
    val category: BackupCategory,
    val sizeBytes: Long,
    val sha256: String,
)

data class BackupPreview(
    val formatVersion: Int,
    val createdAtEpochMillis: Long,
    val includesOriginalTexts: Boolean,
    val entries: List<BackupEntry>,
) {
    val totalBytes: Long = entries.sumOf(BackupEntry::sizeBytes)
    val originalTextCount: Int = entries.count { it.category == BackupCategory.ORIGINAL_TEXT }
}

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Creates and verifies a versioned ZIP without including regenerable cache files. */
class WorldViewerBackup(private val filesDirectory: File) {
    fun create(
        destination: File,
        includeOriginalTexts: Boolean,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ): BackupPreview = createWithOutput(
        destination = destination,
        includeOriginalTexts = includeOriginalTexts,
        createdAtEpochMillis = createdAtEpochMillis,
        wrapOutput = { it },
    )

    internal fun createWithOutput(
        destination: File,
        includeOriginalTexts: Boolean,
        createdAtEpochMillis: Long,
        wrapOutput: (OutputStream) -> OutputStream,
    ): BackupPreview {
        require(createdAtEpochMillis >= 0) { "백업 생성 시각은 음수일 수 없습니다." }
        val root = filesDirectory.canonicalFile
        val canonicalDestination = destination.canonicalFile
        if (
            canonicalDestination == root ||
            canonicalDestination.path.startsWith(root.path + File.separator)
        ) {
            throw BackupFormatException("백업 파일은 앱 사용자 데이터 폴더 밖에 저장해야 합니다.")
        }
        val entries = collectEntries(includeOriginalTexts)
        val preview = BackupPreview(VERSION, createdAtEpochMillis, includeOriginalTexts, entries)
        val parent = destination.absoluteFile.parentFile
            ?: throw BackupFormatException("백업 저장 위치가 올바르지 않습니다.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw BackupFormatException("백업 저장 폴더를 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("worldviewer-backup-", ".partial", parent)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val zip = ZipOutputStream(BufferedOutputStream(wrapOutput(fileOutput)))
                try {
                    entries.forEach { metadata ->
                        val source = checkedSource(metadata.relativePath)
                        zip.putNextEntry(ZipEntry(DATA_PREFIX + metadata.relativePath).apply { time = 0L })
                        val digest = MessageDigest.getInstance("SHA-256")
                        var copied = 0L
                        source.inputStream().buffered().use { input ->
                            copied = copyAndDigest(input, zip, digest, metadata.sizeBytes)
                        }
                        zip.closeEntry()
                        if (copied != metadata.sizeBytes || digest.hexDigest() != metadata.sha256) {
                            throw BackupFormatException("백업 중 원본 파일이 변경됐습니다: ${metadata.relativePath}")
                        }
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = 0L })
                    DataOutputStream(zip).writeManifest(preview)
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                    fileOutput.fd.sync()
                } finally {
                    zip.close()
                }
            }
            replaceAtomically(temporary, destination)
            return preview
        } catch (error: Exception) {
            temporary.delete()
            if (error is BackupFormatException) throw error
            throw BackupFormatException("백업 파일을 만들 수 없습니다.", error)
        }
    }

    fun inspect(backupFile: File): BackupPreview {
        if (!backupFile.isFile) throw BackupFormatException("백업 파일이 없습니다.")
        try {
            ZipFile(backupFile).use { zip ->
                val zipEntries = zip.entries().asSequence().toList()
                val names = zipEntries.map(ZipEntry::getName)
                if (names.size != names.toSet().size) throw BackupFormatException("중복된 백업 항목이 있습니다.")
                val manifestZipEntry = zip.getEntry(MANIFEST_ENTRY)
                    ?: throw BackupFormatException("백업 목록이 없습니다.")
                if (manifestZipEntry.size !in 1..MAX_MANIFEST_BYTES) {
                    throw BackupFormatException("백업 목록 크기가 올바르지 않습니다.")
                }
                val preview = zip.getInputStream(manifestZipEntry).buffered().use { input ->
                    DataInputStream(input).let { data ->
                        data.readManifest().also {
                            if (data.read() != -1) throw BackupFormatException("백업 목록 뒤에 알 수 없는 데이터가 있습니다.")
                        }
                    }
                }
                val expectedNames = preview.entries.map { DATA_PREFIX + it.relativePath }.toSet() + MANIFEST_ENTRY
                if (names.toSet() != expectedNames) {
                    throw BackupFormatException("백업 목록에 없는 파일이 포함됐거나 필요한 파일이 없습니다.")
                }
                preview.entries.forEach { metadata ->
                    val entry = zip.getEntry(DATA_PREFIX + metadata.relativePath)
                        ?: throw BackupFormatException("백업 파일이 빠졌습니다: ${metadata.relativePath}")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = zip.getInputStream(entry).buffered().use { input ->
                        copyAndDigest(input, null, digest, metadata.sizeBytes)
                    }
                    if (size != metadata.sizeBytes || digest.hexDigest() != metadata.sha256) {
                        throw BackupFormatException("백업 파일 체크섬이 일치하지 않습니다: ${metadata.relativePath}")
                    }
                }
                return preview
            }
        } catch (error: Exception) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException("백업 파일을 읽을 수 없습니다.", error)
        }
    }

    private fun collectEntries(includeOriginalTexts: Boolean): List<BackupEntry> {
        val candidates = buildList {
            addExact("imports/current.meta", BackupCategory.IMPORT_METADATA)
            addExact("library/books.catalog", BackupCategory.BOOK_CATALOG)
            addExact("reader-styles/reader-styles.bin", BackupCategory.READER_STYLE)
            addExact("user-meanings/user-meanings.dat", BackupCategory.USER_MEANING)
            addExact("user-annotations/user-annotations.dat", BackupCategory.USER_ANNOTATION)
            addMatching("reading-positions", POSITION_FILE, BackupCategory.READING_POSITION)
            addMatching("manual-chapters", CHAPTER_FILE, BackupCategory.MANUAL_CHAPTER)
            if (includeOriginalTexts) {
                addMatching("imports", ORIGINAL_FILE, BackupCategory.ORIGINAL_TEXT)
            }
        }.sortedBy(Candidate::relativePath)
        if (candidates.size > MAX_ENTRIES) throw BackupFormatException("백업 항목이 너무 많습니다.")
        var total = 0L
        return candidates.map { candidate ->
            val source = checkedSource(candidate.relativePath)
            val size = source.length()
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw BackupFormatException("백업 파일 크기가 허용 범위를 벗어났습니다: ${candidate.relativePath}")
            }
            total = Math.addExact(total, size)
            if (total > MAX_TOTAL_BYTES) throw BackupFormatException("백업 전체 크기가 허용 범위를 벗어났습니다.")
            BackupEntry(candidate.relativePath, candidate.category, size, sha256(source))
        }
    }

    private fun MutableList<Candidate>.addExact(relativePath: String, category: BackupCategory) {
        val file = File(filesDirectory, relativePath)
        if (file.isFile) add(Candidate(relativePath, category))
    }

    private fun MutableList<Candidate>.addMatching(
        relativeDirectory: String,
        pattern: Regex,
        category: BackupCategory,
    ) {
        val directory = File(filesDirectory, relativeDirectory)
        directory.listFiles().orEmpty()
            .filter { it.isFile && pattern.matches(it.name) }
            .forEach { add(Candidate("$relativeDirectory/${it.name}", category)) }
    }

    private fun checkedSource(relativePath: String): File {
        validateRelativePath(relativePath)
        val root = filesDirectory.canonicalFile
        val source = File(root, relativePath).canonicalFile
        val rootPrefix = root.path + File.separator
        if (!source.path.startsWith(rootPrefix) || !source.isFile) {
            throw BackupFormatException("백업 원본 경로가 안전하지 않습니다: $relativePath")
        }
        return source
    }

    private fun DataOutputStream.writeManifest(preview: BackupPreview) {
        writeInt(MAGIC)
        writeInt(preview.formatVersion)
        writeLong(preview.createdAtEpochMillis)
        writeBoolean(preview.includesOriginalTexts)
        writeInt(preview.entries.size)
        preview.entries.forEach { entry ->
            writeUTF(entry.relativePath)
            writeUTF(entry.category.name)
            writeLong(entry.sizeBytes)
            writeUTF(entry.sha256)
        }
        flush()
    }

    private fun DataInputStream.readManifest(): BackupPreview {
        if (readInt() != MAGIC) throw BackupFormatException("WorldViewer 백업 파일이 아닙니다.")
        val version = readInt()
        if (version != VERSION) throw BackupFormatException("지원하지 않는 백업 버전입니다: $version")
        val createdAt = readLong()
        if (createdAt < 0) throw BackupFormatException("백업 생성 시각이 올바르지 않습니다.")
        val includesOriginals = readBoolean()
        val count = readInt()
        if (count !in 0..MAX_ENTRIES) throw BackupFormatException("백업 항목 수가 올바르지 않습니다.")
        val entries = List(count) {
            val path = readUTF().also(::validateRelativePath)
            val category = runCatching { BackupCategory.valueOf(readUTF()) }
                .getOrElse { throw BackupFormatException("알 수 없는 백업 항목 종류입니다.") }
            if (expectedCategory(path) != category) {
                throw BackupFormatException("허용되지 않은 백업 경로 또는 종류입니다: $path")
            }
            val size = readLong()
            if (size !in 0..MAX_ENTRY_BYTES) throw BackupFormatException("백업 항목 크기가 올바르지 않습니다.")
            val hash = readUTF()
            if (!SHA256.matches(hash)) throw BackupFormatException("백업 체크섬 형식이 올바르지 않습니다.")
            BackupEntry(path, category, size, hash)
        }
        if (entries.map(BackupEntry::relativePath).toSet().size != entries.size) {
            throw BackupFormatException("백업 목록에 중복 경로가 있습니다.")
        }
        val total = entries.fold(0L) { value, entry -> Math.addExact(value, entry.sizeBytes) }
        if (total > MAX_TOTAL_BYTES) throw BackupFormatException("백업 전체 크기가 허용 범위를 벗어났습니다.")
        if (!includesOriginals && entries.any { it.category == BackupCategory.ORIGINAL_TEXT }) {
            throw BackupFormatException("원문 포함 표시와 백업 내용이 일치하지 않습니다.")
        }
        return BackupPreview(version, createdAt, includesOriginals, entries)
    }

    private fun validateRelativePath(path: String) {
        if (
            path.isBlank() || path.startsWith('/') || path.startsWith('\\') ||
            path.contains("..") || path.contains('\\') || !SAFE_PATH.matches(path)
        ) {
            throw BackupFormatException("백업 경로가 올바르지 않습니다: $path")
        }
    }

    private fun expectedCategory(path: String): BackupCategory? = when {
        path == "imports/current.meta" -> BackupCategory.IMPORT_METADATA
        path == "library/books.catalog" -> BackupCategory.BOOK_CATALOG
        path == "reader-styles/reader-styles.bin" -> BackupCategory.READER_STYLE
        path == "user-meanings/user-meanings.dat" -> BackupCategory.USER_MEANING
        path == "user-annotations/user-annotations.dat" -> BackupCategory.USER_ANNOTATION
        path.startsWith("imports/") && ORIGINAL_FILE.matches(path.substringAfter('/')) ->
            BackupCategory.ORIGINAL_TEXT
        path.startsWith("reading-positions/") && POSITION_FILE.matches(path.substringAfter('/')) ->
            BackupCategory.READING_POSITION
        path.startsWith("manual-chapters/") && CHAPTER_FILE.matches(path.substringAfter('/')) ->
            BackupCategory.MANUAL_CHAPTER
        else -> null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> copyAndDigest(input, null, digest, file.length()) }
        return digest.hexDigest()
    }

    private fun copyAndDigest(
        input: InputStream,
        output: OutputStream?,
        digest: MessageDigest,
        expectedSize: Long,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > expectedSize || total > MAX_ENTRY_BYTES) {
                throw BackupFormatException("백업 항목이 선언된 크기를 초과합니다.")
            }
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
        }
        return total
    }

    private fun MessageDigest.hexDigest(): String = digest().joinToString("") { "%02x".format(it) }

    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class Candidate(val relativePath: String, val category: BackupCategory)

    private companion object {
        const val MAGIC = 0x5756424B
        const val VERSION = 1
        const val MANIFEST_ENTRY = "manifest.dat"
        const val DATA_PREFIX = "data/"
        const val MAX_ENTRIES = 20_000
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
        val SAFE_PATH = Regex("[a-zA-Z0-9._/-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        val ORIGINAL_FILE = Regex("[0-9a-f]{64}\\.original")
        val POSITION_FILE = Regex("[0-9a-f]{64}\\.position")
        val CHAPTER_FILE = Regex("[0-9a-f]{64}\\.chapters")
    }
}
