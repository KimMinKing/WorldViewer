package dev.worldviewer.lookup

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class CcCedictEntry(
    val traditional: String,
    val simplified: String,
    val pinyin: String,
    val meanings: List<String>,
)

/** Read-only, exact-headword access to WorldViewer's generated CC-CEDICT index. */
class CcCedictDictionary private constructor(
    private val file: RandomAccessFile,
    val providerVersion: String,
    val sourceSha256: String,
    private val entryCount: Int,
    private val keyCount: Int,
    private val entryOffsets: IntArray,
    private val keyOffsets: IntArray,
    private val entryDataStart: Long,
    private val keyDataStart: Long,
) : Closeable {
    @Synchronized
    fun lookup(headword: String, maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES): List<CcCedictEntry> {
        require(headword.isNotBlank()) { "조회 표제어가 필요합니다." }
        require(maximumEntries > 0) { "최대 결과 수는 0보다 커야 합니다." }
        val query = headword.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = keyCount - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            file.seek(keyDataStart + keyOffsets[middle])
            val storedKey = readBytes(MAX_HEADWORD_BYTES)
            when (compareUnsigned(storedKey, query)) {
                0 -> {
                    val matches = file.readInt()
                    require(matches in 1..MAX_MATCHES_PER_HEADWORD) { "사전 표제어 결과 수가 손상됐습니다." }
                    // Read every ID before seeking into entry data. readEntry changes the shared
                    // RandomAccessFile position, so interleaving the two reads corrupts ID 2+.
                    val entryIds = IntArray(matches) {
                        file.readInt().also { entryId ->
                            require(entryId in 0 until entryCount) { "사전 항목 번호가 손상됐습니다." }
                        }
                    }
                    return entryIds.take(maximumEntries).map(::readEntry)
                }
                in Int.MIN_VALUE until 0 -> low = middle + 1
                else -> high = middle - 1
            }
        }
        return emptyList()
    }

    private fun readEntry(entryId: Int): CcCedictEntry {
        file.seek(entryDataStart + entryOffsets[entryId])
        val traditional = readString(MAX_HEADWORD_BYTES)
        val simplified = readString(MAX_HEADWORD_BYTES)
        val pinyin = readString(MAX_PINYIN_BYTES)
        val meaningCount = file.readInt()
        require(meaningCount in 1..MAX_MEANINGS_PER_ENTRY) { "사전 뜻 수가 손상됐습니다." }
        val meanings = List(meaningCount) { readString(MAX_MEANING_BYTES) }
        return CcCedictEntry(traditional, simplified, pinyin, meanings)
    }

    private fun readString(maximumBytes: Int): String = readBytes(maximumBytes).toString(Charsets.UTF_8)

    private fun readBytes(maximumBytes: Int): ByteArray {
        val size = file.readInt()
        require(size in 0..maximumBytes) { "사전 문자열 길이가 손상됐습니다." }
        return ByteArray(size).also(file::readFully)
    }

    override fun close() = file.close()

    companion object {
        const val ASSET_PATH = "dictionary/cc_cedict_20260926.bin"
        const val GENERATED_SHA256 = "087162436701952ffe9915f08b43bbfb3897039dad9c87aa5921adaf93576dac"
        private val MAGIC = "WVCCEDIC".toByteArray(Charsets.US_ASCII)
        private const val FORMAT_VERSION = 2
        private const val DEFAULT_MAXIMUM_ENTRIES = 20
        private const val MAX_HEADWORD_BYTES = 4_096
        private const val MAX_PINYIN_BYTES = 32_768
        private const val MAX_MEANING_BYTES = 1_048_576
        private const val MAX_MEANINGS_PER_ENTRY = 1_024
        private const val MAX_MATCHES_PER_HEADWORD = 10_000

        fun open(path: File): CcCedictDictionary {
            val file = RandomAccessFile(path, "r")
            try {
                val magic = ByteArray(MAGIC.size).also(file::readFully)
                require(magic.contentEquals(MAGIC)) { "CC-CEDICT 색인 표식이 올바르지 않습니다." }
                require(file.readInt() == FORMAT_VERSION) { "지원하지 않는 CC-CEDICT 색인 버전입니다." }
                val providerVersion = readHeaderString(file)
                val sourceSha256 = readHeaderString(file)
                val entryCount = file.readInt()
                val keyCount = file.readInt()
                require(entryCount > 0 && keyCount > 0) { "CC-CEDICT 색인이 비어 있습니다." }
                val entryOffsets = readOffsets(file, entryCount + 1)
                val keyOffsets = readOffsets(file, keyCount + 1)
                val entryDataStart = file.filePointer
                val keyDataStart = entryDataStart + entryOffsets.last()
                require(keyDataStart + keyOffsets.last() == file.length()) {
                    "CC-CEDICT 색인 길이가 일치하지 않습니다."
                }
                return CcCedictDictionary(
                    file,
                    providerVersion,
                    sourceSha256,
                    entryCount,
                    keyCount,
                    entryOffsets,
                    keyOffsets,
                    entryDataStart,
                    keyDataStart,
                )
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }

        /** Copies the packaged, reproducible index without exposing it to backup. */
        fun install(openAsset: () -> InputStream, target: File): File {
            if (target.isFile && sha256(target) == GENERATED_SHA256) return target
            target.parentFile?.mkdirs()
            val temporary = File.createTempFile("cc-cedict-", ".partial", target.parentFile)
            try {
                openAsset().use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                require(sha256(temporary) == GENERATED_SHA256) { "포함된 CC-CEDICT 색인 검증에 실패했습니다." }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return target
            } finally {
                temporary.delete()
            }
        }

        private fun readHeaderString(file: RandomAccessFile): String {
            val size = file.readInt()
            require(size in 1..4_096) { "CC-CEDICT 머리말 문자열이 손상됐습니다." }
            return ByteArray(size).also(file::readFully).toString(Charsets.UTF_8)
        }

        private fun readOffsets(file: RandomAccessFile, count: Int): IntArray {
            require(count in 2..1_000_000) { "CC-CEDICT 색인 항목 수가 올바르지 않습니다." }
            return IntArray(count) { file.readInt() }.also { offsets ->
                require(offsets.first() == 0) { "CC-CEDICT 색인 시작 위치가 올바르지 않습니다." }
                for (index in 1 until offsets.size) {
                    require(offsets[index] >= offsets[index - 1]) { "CC-CEDICT 색인 위치가 손상됐습니다." }
                }
            }
        }

        private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
            val common = minOf(left.size, right.size)
            for (index in 0 until common) {
                val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return left.size - right.size
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
