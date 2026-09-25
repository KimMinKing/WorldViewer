package dev.worldviewer.reader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class MeaningScope {
    RANGE,
    BOOK,
    GLOBAL,
}

data class UserMeaning(
    val id: String,
    val sourceText: String,
    val meaning: String,
    val scope: MeaningScope,
    val documentSha256: String?,
    val rangeStartUtf16: Int?,
    val rangeEndUtf16: Int?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class MeaningQuery(
    val sourceText: String,
    val documentSha256: String?,
    val rangeStartUtf16: Int?,
    val rangeEndUtf16: Int?,
)

class UserMeaningIndex(meanings: List<UserMeaning>) {
    private data class BookKey(val source: String, val document: String)
    private data class RangeKey(val source: String, val document: String, val start: Int, val end: Int)

    private val global = meanings.filter { it.scope == MeaningScope.GLOBAL }.groupBy(UserMeaning::sourceText)
    private val book = meanings.filter { it.scope == MeaningScope.BOOK }.groupBy {
        BookKey(it.sourceText, requireNotNull(it.documentSha256))
    }
    private val range = meanings.filter { it.scope == MeaningScope.RANGE }.groupBy {
        RangeKey(
            it.sourceText,
            requireNotNull(it.documentSha256),
            requireNotNull(it.rangeStartUtf16),
            requireNotNull(it.rangeEndUtf16),
        )
    }

    fun resolve(query: MeaningQuery): List<UserMeaning> {
        if (query.documentSha256 != null && query.rangeStartUtf16 != null && query.rangeEndUtf16 != null) {
            range[RangeKey(query.sourceText, query.documentSha256, query.rangeStartUtf16, query.rangeEndUtf16)]
                ?.let { if (it.isNotEmpty()) return ordered(it) }
        }
        if (query.documentSha256 != null) {
            book[BookKey(query.sourceText, query.documentSha256)]
                ?.let { if (it.isNotEmpty()) return ordered(it) }
        }
        return ordered(global[query.sourceText].orEmpty())
    }

    private fun ordered(values: List<UserMeaning>): List<UserMeaning> =
        values.sortedWith(compareBy<UserMeaning> { it.createdAtEpochMillis }.thenBy { it.id })
}

class UserMeaningStore(private val directory: File) {
    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.startsWith("meanings-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    fun load(): List<UserMeaning> {
        val target = File(directory, FILE_NAME)
        if (!target.isFile) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
                if (input.readInt() != MAGIC) throw TxtImportException("사용자 뜻 정보가 손상됐습니다.")
                if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 사용자 뜻 정보입니다.")
                val count = input.readInt()
                if (count !in 0..MAX_MEANINGS) throw TxtImportException("사용자 뜻 개수가 올바르지 않습니다.")
                List(count) {
                    UserMeaning(
                        id = input.readUTF(),
                        sourceText = input.readUTF(),
                        meaning = input.readUTF(),
                        scope = runCatching { MeaningScope.valueOf(input.readUTF()) }
                            .getOrElse { throw TxtImportException("사용자 뜻 범위가 올바르지 않습니다.") },
                        documentSha256 = input.readBoolean().let { if (it) input.readUTF() else null },
                        rangeStartUtf16 = input.readBoolean().let { if (it) input.readInt() else null },
                        rangeEndUtf16 = input.readBoolean().let { if (it) input.readInt() else null },
                        createdAtEpochMillis = input.readLong(),
                        updatedAtEpochMillis = input.readLong(),
                    ).also(::validateMeaning)
                }.also(::validateAll)
            }
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("사용자 뜻을 읽을 수 없습니다.", error)
        }
    }

    fun save(meanings: List<UserMeaning>) {
        saveWithOutput(meanings) { it }
    }

    internal fun saveWithOutput(meanings: List<UserMeaning>, wrap: (OutputStream) -> OutputStream) {
        validateAll(meanings)
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("사용자 뜻 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("meanings-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(wrap(fileOutput)))
                try {
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(meanings.size)
                    meanings.forEach { value ->
                        output.writeUTF(value.id)
                        output.writeUTF(value.sourceText)
                        output.writeUTF(value.meaning)
                        output.writeUTF(value.scope.name)
                        output.writeBoolean(value.documentSha256 != null)
                        value.documentSha256?.let(output::writeUTF)
                        output.writeBoolean(value.rangeStartUtf16 != null)
                        value.rangeStartUtf16?.let(output::writeInt)
                        output.writeBoolean(value.rangeEndUtf16 != null)
                        value.rangeEndUtf16?.let(output::writeInt)
                        output.writeLong(value.createdAtEpochMillis)
                        output.writeLong(value.updatedAtEpochMillis)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                } finally {
                    output.close()
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            if (error is TxtImportException) throw error
            throw TxtImportException("사용자 뜻을 저장할 수 없습니다.", error)
        } finally {
            temporary.delete()
        }
    }

    private fun validateAll(meanings: List<UserMeaning>) {
        if (meanings.size > MAX_MEANINGS) throw TxtImportException("사용자 뜻이 너무 많습니다.")
        meanings.forEach(::validateMeaning)
        if (meanings.map(UserMeaning::id).toSet().size != meanings.size) {
            throw TxtImportException("사용자 뜻 식별자가 중복됐습니다.")
        }
    }

    private fun validateMeaning(value: UserMeaning) {
        if (!ID.matches(value.id)) throw TxtImportException("사용자 뜻 식별자가 올바르지 않습니다.")
        if (value.sourceText.isBlank() || value.sourceText.length > MAX_SOURCE_UTF16) {
            throw TxtImportException("사용자 뜻의 원문이 올바르지 않습니다.")
        }
        if (value.meaning.isBlank() || value.meaning.length > MAX_MEANING_UTF16) {
            throw TxtImportException("사용자 뜻풀이가 올바르지 않습니다.")
        }
        if (value.createdAtEpochMillis < 0 || value.updatedAtEpochMillis < value.createdAtEpochMillis) {
            throw TxtImportException("사용자 뜻 시간이 올바르지 않습니다.")
        }
        when (value.scope) {
            MeaningScope.GLOBAL -> if (
                value.documentSha256 != null || value.rangeStartUtf16 != null || value.rangeEndUtf16 != null
            ) throw TxtImportException("전체 뜻 범위가 올바르지 않습니다.")
            MeaningScope.BOOK -> if (
                value.documentSha256 == null || !SHA256.matches(value.documentSha256) ||
                value.rangeStartUtf16 != null || value.rangeEndUtf16 != null
            ) throw TxtImportException("책 뜻 범위가 올바르지 않습니다.")
            MeaningScope.RANGE -> if (
                value.documentSha256 == null || !SHA256.matches(value.documentSha256) ||
                value.rangeStartUtf16 == null || value.rangeEndUtf16 == null ||
                value.rangeStartUtf16 < 0 || value.rangeEndUtf16 <= value.rangeStartUtf16
            ) throw TxtImportException("구간 뜻 범위가 올바르지 않습니다.")
        }
    }

    companion object {
        const val FILE_NAME = "user-meanings.dat"
        const val MAX_SOURCE_UTF16 = 200
        const val MAX_MEANING_UTF16 = 2_000
        const val MAX_MEANINGS = 100_000
        private const val MAGIC = 0x57564D4E
        private const val VERSION = 1
        private val ID = Regex("[0-9a-f-]{36}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
