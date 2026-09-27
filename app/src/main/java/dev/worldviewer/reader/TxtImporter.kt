package dev.worldviewer.reader

import java.io.File
import java.io.InputStream
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class ImportedText(
    val storedFile: File,
    val sha256: String,
    val byteCount: Long,
    val encoding: String,
)

data class StoredTextInfo(
    val storedFile: File,
    val sha256: String,
    val byteCount: Long,
    val encoding: String,
)

class StagedTextImport internal constructor(
    internal val temporaryFile: File,
    val sha256: String,
    val byteCount: Long,
    val candidates: List<EncodingCandidate>,
) : Closeable {
    private var committed = false

    internal fun markCommitted() {
        committed = true
    }

    override fun close() {
        if (!committed) temporaryFile.delete()
    }
}

open class TxtImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TxtImportCancelledException : TxtImportException("TXT 가져오기를 취소했습니다.")

enum class ImportProgressPhase {
    COPYING,
    VALIDATING,
}

data class ImportProgress(
    val phase: ImportProgressPhase,
    val processedBytes: Long,
    val totalBytes: Long?,
)

class ImportCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    internal fun throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw TxtImportCancelledException()
        }
    }
}

object TxtImporter {
    fun import(
        input: InputStream,
        destinationDirectory: File,
        maxBytes: Long = Long.MAX_VALUE,
    ): ImportedText {
        return stage(input, destinationDirectory, maxBytes).use { staged ->
            val candidate = staged.candidates.singleOrNull()?.takeIf { it.automatic }
                ?: throw TxtImportException("문자 인코딩을 미리보기에서 선택해야 합니다.")
            commit(staged, candidate.encoding)
        }
    }

    fun stage(
        input: InputStream,
        destinationDirectory: File,
        maxBytes: Long = Long.MAX_VALUE,
        totalBytes: Long? = null,
        cancellation: ImportCancellation = ImportCancellation(),
        onProgress: (ImportProgress) -> Unit = {},
    ): StagedTextImport = stageWithOutput(
        input,
        destinationDirectory,
        maxBytes,
        totalBytes,
        cancellation,
        onProgress,
    ) { file -> file.outputStream().buffered() }

    internal fun stageWithOutput(
        input: InputStream,
        destinationDirectory: File,
        maxBytes: Long = Long.MAX_VALUE,
        totalBytes: Long? = null,
        cancellation: ImportCancellation = ImportCancellation(),
        onProgress: (ImportProgress) -> Unit = {},
        openOutput: (File) -> OutputStream,
    ): StagedTextImport {
        require(maxBytes > 0)
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw TxtImportException("가져오기 저장 공간을 만들 수 없습니다.")
        }

        val temporary = File.createTempFile("incoming-", ".partial", destinationDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val sample = ByteArrayOutputStream(TextEncodingDetector.SAMPLE_BYTES)
            var byteCount = 0L
            var lastReported = -PROGRESS_INTERVAL_BYTES
            openOutput(temporary).use { output ->
                input.use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > maxBytes - byteCount) {
                            throw TxtImportException("선택한 TXT가 현재 허용 크기를 초과했습니다.")
                        }
                        byteCount += read
                        if (sample.size() < TextEncodingDetector.SAMPLE_BYTES) {
                            val copied = minOf(read, TextEncodingDetector.SAMPLE_BYTES - sample.size())
                            sample.write(buffer, 0, copied)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        if (byteCount - lastReported >= PROGRESS_INTERVAL_BYTES) {
                            onProgress(ImportProgress(ImportProgressPhase.COPYING, byteCount, totalBytes))
                            lastReported = byteCount
                        }
                    }
                }
            }
            cancellation.throwIfCancelled()
            onProgress(ImportProgress(ImportProgressPhase.COPYING, byteCount, totalBytes ?: byteCount))

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val candidates = TextEncodingDetector.candidates(sample.toByteArray())
            if (candidates.isEmpty()) {
                throw TxtImportException("지원하는 문자 인코딩 후보를 찾지 못했습니다.")
            }
            return StagedTextImport(temporary, hash, byteCount, candidates)
        } catch (error: TxtImportException) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            throw TxtImportException("TXT를 가져오지 못했습니다.", error)
        }
    }

    fun commit(
        staged: StagedTextImport,
        encoding: String,
        cancellation: ImportCancellation = ImportCancellation(),
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportedText {
        if (staged.candidates.none { it.encoding == encoding }) {
            staged.close()
            throw TxtImportException("선택한 문자 인코딩이 후보에 없습니다.")
        }
        try {
            onProgress(ImportProgress(ImportProgressPhase.VALIDATING, 0L, staged.byteCount))
            cancellation.throwIfCancelled()
            StrictTextReader.open(staged.temporaryFile, encoding).use { reader ->
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    cancellation.throwIfCancelled()
                    val read = reader.read(buffer)
                    if (read < 0) break
                }
            }
            cancellation.throwIfCancelled()
            onProgress(ImportProgress(ImportProgressPhase.VALIDATING, staged.byteCount, staged.byteCount))
            val stored = File(staged.temporaryFile.parentFile, "${staged.sha256}.original")
            if (stored.exists()) {
                staged.temporaryFile.delete()
            } else if (!staged.temporaryFile.renameTo(stored)) {
                throw TxtImportException("원본 파일을 안전하게 보존하지 못했습니다.")
            }
            staged.markCommitted()
            return ImportedText(stored, staged.sha256, staged.byteCount, encoding)
        } catch (error: TxtImportException) {
            staged.close()
            throw error
        } catch (error: CharacterCodingException) {
            staged.close()
            throw TxtImportException("선택한 문자 인코딩으로 파일 전체를 읽지 못했습니다.", error)
        } catch (error: Exception) {
            staged.close()
            throw TxtImportException("TXT를 보존하지 못했습니다.", error)
        }
    }

    fun cleanStalePartials(destinationDirectory: File): Int {
        if (!destinationDirectory.isDirectory) return 0
        return destinationDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile && file.name.startsWith("incoming-") && file.name.endsWith(".partial")
            }
            .count { it.delete() }
    }

    fun inspectStored(
        storedFile: File,
        expectedEncoding: String? = null,
        maxBytes: Long = Long.MAX_VALUE,
    ): StoredTextInfo {
        require(maxBytes > 0)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val prefix = ByteArray(UTF8_BOM.size)
            var prefixLength = 0
            var byteCount = 0L
            storedFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read.toLong() > maxBytes - byteCount) {
                        throw TxtImportException("보존된 TXT가 현재 허용 크기를 초과했습니다.")
                    }
                    byteCount += read
                    if (prefixLength < prefix.size) {
                        val copied = minOf(read, prefix.size - prefixLength)
                        buffer.copyInto(prefix, prefixLength, 0, copied)
                        prefixLength += copied
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return StoredTextInfo(
                storedFile = storedFile,
                sha256 = digest.digest().toHex(),
                byteCount = byteCount,
                encoding = expectedEncoding ?: detectEncoding(prefix.copyOf(prefixLength)),
            )
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("보존된 TXT 원본을 검증하지 못했습니다.", error)
        }
    }

    private fun detectEncoding(prefix: ByteArray): String = when {
        prefix.startsWith(UTF8_BOM) -> "UTF-8 BOM"
        prefix.startsWith(UTF16_LE_BOM) -> "UTF-16LE BOM"
        prefix.startsWith(UTF16_BE_BOM) -> "UTF-16BE BOM"
        else -> "UTF-8"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
}
