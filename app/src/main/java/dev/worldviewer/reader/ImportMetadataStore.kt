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

data class ImportMetadata(
    val storedName: String,
    val displayName: String,
    val sha256: String,
    val byteCount: Long,
    val encoding: String,
)

class ImportMetadataStore(private val directory: File) {
    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("current-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    fun load(): ImportMetadata? {
        val target = File(directory, FILE_NAME)
        if (!target.isFile) return null
        return DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
            if (input.readInt() != MAGIC) throw TxtImportException("최근 문서 정보가 손상됐습니다.")
            if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 최근 문서 정보입니다.")
            val metadata = ImportMetadata(
                storedName = input.readUTF(),
                displayName = input.readUTF(),
                sha256 = input.readUTF(),
                byteCount = input.readLong(),
                encoding = input.readUTF(),
            ).also(::validate)
            input.requirePersistentRecordEnd("최근 문서 정보")
            metadata
        }
    }

    fun save(metadata: ImportMetadata) {
        validate(metadata)
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("최근 문서 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("current-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(metadata.storedName)
                    output.writeUTF(metadata.displayName)
                    output.writeUTF(metadata.sha256)
                    output.writeLong(metadata.byteCount)
                    output.writeUTF(metadata.encoding)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            val target = File(directory, FILE_NAME)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validate(metadata: ImportMetadata) {
        if (!STORED_NAME.matches(metadata.storedName) || metadata.storedName != "${metadata.sha256}.original") {
            throw TxtImportException("최근 문서의 원본 식별자가 올바르지 않습니다.")
        }
        if (metadata.displayName.isBlank() || metadata.displayName.length > 200) {
            throw TxtImportException("최근 문서의 파일명이 올바르지 않습니다.")
        }
        if (metadata.byteCount < 0) {
            throw TxtImportException("최근 문서의 크기가 올바르지 않습니다.")
        }
        if (metadata.encoding !in ENCODINGS) {
            throw TxtImportException("최근 문서의 인코딩 정보가 올바르지 않습니다.")
        }
    }

    companion object {
        const val FILE_NAME = "current.meta"
        private const val MAGIC = 0x5756494D
        private const val VERSION = 1
        private val STORED_NAME = Regex("[0-9a-f]{64}\\.original")
        private val ENCODINGS = setOf(
            "UTF-8",
            "UTF-8 BOM",
            "UTF-16LE BOM",
            "UTF-16BE BOM",
            "UTF-16LE",
            "UTF-16BE",
            "GB18030",
            "Big5",
        )
    }
}
