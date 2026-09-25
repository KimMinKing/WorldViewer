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

enum class AnnotationType { BOOKMARK, HIGHLIGHT, NOTE }

data class UserAnnotation(
    val id: String,
    val type: AnnotationType,
    val documentSha256: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val quoteBefore: String,
    val quoteSelected: String,
    val quoteAfter: String,
    val note: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

class UserAnnotationStore(private val directory: File) {
    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.startsWith("annotations-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    fun load(): List<UserAnnotation> {
        val target = File(directory, FILE_NAME)
        if (!target.isFile) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
                if (input.readInt() != MAGIC) throw TxtImportException("주석 정보가 손상됐습니다.")
                if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 주석 정보입니다.")
                val count = input.readInt()
                if (count !in 0..MAX_ANNOTATIONS) throw TxtImportException("주석 개수가 올바르지 않습니다.")
                List(count) {
                    UserAnnotation(
                        id = input.readUTF(),
                        type = runCatching { AnnotationType.valueOf(input.readUTF()) }
                            .getOrElse { throw TxtImportException("주석 종류가 올바르지 않습니다.") },
                        documentSha256 = input.readUTF(),
                        startUtf16 = input.readInt(),
                        endUtf16 = input.readInt(),
                        quoteBefore = input.readUTF(),
                        quoteSelected = input.readUTF(),
                        quoteAfter = input.readUTF(),
                        note = input.readBoolean().let { if (it) input.readUTF() else null },
                        createdAtEpochMillis = input.readLong(),
                        updatedAtEpochMillis = input.readLong(),
                    ).also(::validate)
                }.also(::validateAll)
            }
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("주석을 읽을 수 없습니다.", error)
        }
    }

    fun save(values: List<UserAnnotation>) = saveWithOutput(values) { it }

    internal fun saveWithOutput(values: List<UserAnnotation>, wrap: (OutputStream) -> OutputStream) {
        validateAll(values)
        if (!directory.exists() && !directory.mkdirs()) throw TxtImportException("주석 저장 공간을 만들 수 없습니다.")
        val temporary = File.createTempFile("annotations-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(wrap(fileOutput)))
                try {
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(values.size)
                    values.forEach { value ->
                        output.writeUTF(value.id)
                        output.writeUTF(value.type.name)
                        output.writeUTF(value.documentSha256)
                        output.writeInt(value.startUtf16)
                        output.writeInt(value.endUtf16)
                        output.writeUTF(value.quoteBefore)
                        output.writeUTF(value.quoteSelected)
                        output.writeUTF(value.quoteAfter)
                        output.writeBoolean(value.note != null)
                        value.note?.let(output::writeUTF)
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
                    temporary.toPath(), File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            if (error is TxtImportException) throw error
            throw TxtImportException("주석을 저장할 수 없습니다.", error)
        } finally {
            temporary.delete()
        }
    }

    private fun validateAll(values: List<UserAnnotation>) {
        if (values.size > MAX_ANNOTATIONS) throw TxtImportException("주석이 너무 많습니다.")
        values.forEach(::validate)
        if (values.map(UserAnnotation::id).toSet().size != values.size) {
            throw TxtImportException("주석 식별자가 중복됐습니다.")
        }
    }

    private fun validate(value: UserAnnotation) {
        if (!ID.matches(value.id) || !SHA256.matches(value.documentSha256)) {
            throw TxtImportException("주석 식별자가 올바르지 않습니다.")
        }
        val validRange = if (value.type == AnnotationType.BOOKMARK) {
            value.startUtf16 >= 0 && value.endUtf16 == value.startUtf16
        } else {
            value.startUtf16 >= 0 && value.endUtf16 > value.startUtf16
        }
        if (!validRange) throw TxtImportException("주석 원문 범위가 올바르지 않습니다.")
        if (
            value.quoteBefore.length > MAX_CONTEXT_UTF16 || value.quoteAfter.length > MAX_CONTEXT_UTF16 ||
            value.quoteSelected.length > MAX_SELECTED_UTF16
        ) throw TxtImportException("주석 원문 인용문이 너무 깁니다.")
        when (value.type) {
            AnnotationType.NOTE -> if (value.note.isNullOrBlank() || value.note.length > MAX_NOTE_UTF16) {
                throw TxtImportException("메모 내용이 올바르지 않습니다.")
            }
            AnnotationType.BOOKMARK, AnnotationType.HIGHLIGHT -> if (value.note != null) {
                throw TxtImportException("이 주석 종류에는 메모가 없어야 합니다.")
            }
        }
        if (value.createdAtEpochMillis < 0 || value.updatedAtEpochMillis < value.createdAtEpochMillis) {
            throw TxtImportException("주석 시간이 올바르지 않습니다.")
        }
    }

    companion object {
        const val FILE_NAME = "user-annotations.dat"
        const val MAX_CONTEXT_UTF16 = 32
        const val MAX_SELECTED_UTF16 = 200
        const val MAX_NOTE_UTF16 = 4_000
        const val MAX_ANNOTATIONS = 100_000
        private const val MAGIC = 0x5756414E
        private const val VERSION = 1
        private val ID = Regex("[0-9a-f-]{36}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
