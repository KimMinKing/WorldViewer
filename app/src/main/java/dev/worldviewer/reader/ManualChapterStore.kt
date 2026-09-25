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

data class ManualChapter(
    val id: String,
    val title: String,
    val utf16Offset: Int,
    val quoteBefore: String,
    val quoteAfter: String,
)

class ManualChapterStore(private val directory: File) {
    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("chapters-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    fun load(documentSha256: String): List<ManualChapter> {
        validateSha(documentSha256)
        val target = target(documentSha256)
        if (!target.isFile) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
                if (input.readInt() != MAGIC) throw TxtImportException("수동 목차 정보가 손상됐습니다.")
                if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 수동 목차 정보입니다.")
                if (input.readUTF() != documentSha256) {
                    throw TxtImportException("수동 목차의 문서 식별자가 일치하지 않습니다.")
                }
                val count = input.readInt()
                if (count !in 0..MAX_CHAPTERS) throw TxtImportException("수동 목차 개수가 올바르지 않습니다.")
                val chapters = List(count) {
                    ManualChapter(
                        id = input.readUTF(),
                        title = input.readUTF(),
                        utf16Offset = input.readInt(),
                        quoteBefore = input.readUTF(),
                        quoteAfter = input.readUTF(),
                    ).also(::validateChapter)
                }.also { chapters ->
                    if (chapters.map(ManualChapter::id).toSet().size != chapters.size) {
                        throw TxtImportException("수동 목차 식별자가 중복됐습니다.")
                    }
                }
                input.requirePersistentRecordEnd("수동 목차 정보")
                chapters
            }
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("수동 목차를 읽을 수 없습니다.", error)
        }
    }

    fun save(documentSha256: String, chapters: List<ManualChapter>) {
        saveWithOutput(documentSha256, chapters) { it }
    }

    internal fun saveWithOutput(
        documentSha256: String,
        chapters: List<ManualChapter>,
        wrap: (OutputStream) -> OutputStream,
    ) {
        validateSha(documentSha256)
        if (chapters.size > MAX_CHAPTERS) throw TxtImportException("수동 목차가 너무 많습니다.")
        chapters.forEach(::validateChapter)
        if (chapters.map(ManualChapter::id).toSet().size != chapters.size) {
            throw TxtImportException("수동 목차 식별자가 중복됐습니다.")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("수동 목차 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("chapters-$documentSha256-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(wrap(fileOutput)))
                try {
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(documentSha256)
                    output.writeInt(chapters.size)
                    chapters.sortedBy(ManualChapter::utf16Offset).forEach { chapter ->
                        output.writeUTF(chapter.id)
                        output.writeUTF(chapter.title)
                        output.writeInt(chapter.utf16Offset)
                        output.writeUTF(chapter.quoteBefore)
                        output.writeUTF(chapter.quoteAfter)
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
                    target(documentSha256).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target(documentSha256).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            if (error is TxtImportException) throw error
            throw TxtImportException("수동 목차를 저장할 수 없습니다.", error)
        } finally {
            temporary.delete()
        }
    }

    private fun target(documentSha256: String): File = File(directory, "$documentSha256.chapters")

    private fun validateChapter(chapter: ManualChapter) {
        if (!ID.matches(chapter.id)) throw TxtImportException("수동 목차 식별자가 올바르지 않습니다.")
        if (chapter.title.isBlank() || chapter.title.length > MAX_TITLE_UTF16) {
            throw TxtImportException("수동 목차 제목이 올바르지 않습니다.")
        }
        if (chapter.utf16Offset < 0) throw TxtImportException("수동 목차 위치가 올바르지 않습니다.")
        if (
            chapter.quoteBefore.length > ReadingPositionStore.MAX_QUOTE_UTF16 ||
            chapter.quoteAfter.length > ReadingPositionStore.MAX_QUOTE_UTF16
        ) {
            throw TxtImportException("수동 목차의 원문 확인 문구가 너무 깁니다.")
        }
    }

    private fun validateSha(documentSha256: String) {
        if (!SHA256.matches(documentSha256)) throw TxtImportException("문서 식별자가 올바르지 않습니다.")
    }

    companion object {
        const val MAX_TITLE_UTF16 = 100
        const val MAX_CHAPTERS = 10_000
        private const val MAGIC = 0x57564D43
        private const val VERSION = 1
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ID = Regex("[0-9a-f-]{36}")
    }
}
