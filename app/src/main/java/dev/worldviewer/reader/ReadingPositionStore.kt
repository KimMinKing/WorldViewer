package dev.worldviewer.reader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ReadingPosition(
    val documentSha256: String,
    val blockIndex: Int,
    val utf16Offset: Int,
    val quoteBefore: String,
    val quoteAfter: String,
)

class ReadingPositionStore(private val directory: File) {
    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("position-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    fun load(documentSha256: String): ReadingPosition? {
        validateSha(documentSha256)
        val target = target(documentSha256)
        if (!target.isFile) return null
        return DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
            if (input.readInt() != MAGIC) throw TxtImportException("읽기 위치 정보가 손상됐습니다.")
            if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 읽기 위치 정보입니다.")
            val position = ReadingPosition(
                documentSha256 = input.readUTF(),
                blockIndex = input.readInt(),
                utf16Offset = input.readInt(),
                quoteBefore = input.readUTF(),
                quoteAfter = input.readUTF(),
            ).also { position ->
                validate(position)
                if (position.documentSha256 != documentSha256) {
                    throw TxtImportException("읽기 위치의 문서 식별자가 일치하지 않습니다.")
                }
            }
            input.requirePersistentRecordEnd("읽기 위치 정보")
            position
        }
    }

    fun save(position: ReadingPosition) {
        validate(position)
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("읽기 위치 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("position-${position.documentSha256}-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(position.documentSha256)
                    output.writeInt(position.blockIndex)
                    output.writeInt(position.utf16Offset)
                    output.writeUTF(position.quoteBefore)
                    output.writeUTF(position.quoteAfter)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target(position.documentSha256).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target(position.documentSha256).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun target(documentSha256: String): File = File(directory, "$documentSha256.position")

    private fun validate(position: ReadingPosition) {
        validateSha(position.documentSha256)
        if (position.blockIndex < 0 || position.utf16Offset < 0) {
            throw TxtImportException("읽기 위치의 원문 주소가 올바르지 않습니다.")
        }
        if (position.quoteBefore.length > MAX_QUOTE_UTF16 || position.quoteAfter.length > MAX_QUOTE_UTF16) {
            throw TxtImportException("읽기 위치의 인용문이 너무 깁니다.")
        }
    }

    private fun validateSha(documentSha256: String) {
        if (!SHA256.matches(documentSha256)) {
            throw TxtImportException("읽기 위치의 문서 식별자가 올바르지 않습니다.")
        }
    }

    companion object {
        const val MAX_QUOTE_UTF16 = 32
        private const val MAGIC = 0x57565053
        private const val VERSION = 1
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
