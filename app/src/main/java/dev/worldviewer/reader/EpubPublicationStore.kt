package dev.worldviewer.reader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class EpubPublication(
    val documentSha256: String,
    val sourceSha256: String,
    val sourceStoredName: String,
    val chapters: List<EpubChapter>,
)

class EpubPublicationStore(private val directory: File) {
    fun save(publication: EpubPublication) {
        validate(publication)
        if (!directory.exists() && !directory.mkdirs()) throw TxtImportException("EPUB 목차 저장 공간을 만들 수 없습니다.")
        val temporary = File.createTempFile("publication-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    output.writeInt(MAGIC); output.writeInt(VERSION)
                    output.writeUTF(publication.documentSha256)
                    output.writeUTF(publication.sourceSha256)
                    output.writeUTF(publication.sourceStoredName)
                    output.writeInt(publication.chapters.size)
                    publication.chapters.forEach { output.writeUTF(it.title); output.writeInt(it.utf16Offset) }
                    output.flush(); raw.fd.sync()
                }
            }
            Files.move(temporary.toPath(), target(publication.documentSha256).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun load(documentSha256: String): EpubPublication? {
        validateSha(documentSha256)
        val target = target(documentSha256)
        if (!target.isFile) return null
        return DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw TxtImportException("EPUB 목차 정보가 손상됐습니다.")
            val publication = EpubPublication(
                input.readUTF(), input.readUTF(), input.readUTF(),
                List(input.readInt().also { if (it !in 1..10_000) throw TxtImportException("EPUB 목차 개수가 올바르지 않습니다.") }) {
                    EpubChapter(input.readUTF(), input.readInt())
                },
            )
            input.requirePersistentRecordEnd("EPUB 목차 정보")
            validate(publication)
            if (publication.documentSha256 != documentSha256) throw TxtImportException("EPUB 문서 식별자가 일치하지 않습니다.")
            publication
        }
    }

    private fun target(sha: String) = File(directory, "$sha.epubmeta")
    private fun validate(publication: EpubPublication) {
        validateSha(publication.documentSha256); validateSha(publication.sourceSha256)
        if (publication.sourceStoredName != "${publication.sourceSha256}.epub") throw TxtImportException("EPUB 원본 정보가 올바르지 않습니다.")
        if (publication.chapters.isEmpty() || publication.chapters.size > 10_000) throw TxtImportException("EPUB 목차가 올바르지 않습니다.")
        publication.chapters.forEach { if (it.title.isBlank() || it.title.length > 100 || it.utf16Offset < 0) throw TxtImportException("EPUB 장 정보가 올바르지 않습니다.") }
    }
    private fun validateSha(sha: String) { if (!SHA.matches(sha)) throw TxtImportException("문서 식별자가 올바르지 않습니다.") }
    companion object { private const val MAGIC = 0x57564550; private const val VERSION = 1; private val SHA = Regex("[0-9a-f]{64}") }
}
