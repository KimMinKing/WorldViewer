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

data class BookCatalogEntry(
    val bookId: String,
    val documentSha256: String,
    val storedName: String,
    val displayName: String,
    val byteCount: Long,
    val encoding: String,
    val addedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long,
)

data class BookCatalog(
    val currentBookId: String?,
    val books: List<BookCatalogEntry>,
)

class BookCatalogStore(private val directory: File) {
    fun load(): BookCatalog? {
        val target = File(directory, FILE_NAME)
        if (!target.isFile) return null
        return DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
            if (input.readInt() != MAGIC) throw TxtImportException("책 목록 정보가 손상됐습니다.")
            if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 책 목록 정보입니다.")
            val currentBookId = input.readBoolean().let { hasCurrent ->
                if (hasCurrent) input.readUTF() else null
            }
            val count = input.readInt()
            if (count !in 0..MAX_BOOKS) throw TxtImportException("책 목록 개수가 올바르지 않습니다.")
            BookCatalog(
                currentBookId = currentBookId,
                books = List(count) {
                    BookCatalogEntry(
                        bookId = input.readUTF(),
                        documentSha256 = input.readUTF(),
                        storedName = input.readUTF(),
                        displayName = input.readUTF(),
                        byteCount = input.readLong(),
                        encoding = input.readUTF(),
                        addedAtEpochMillis = input.readLong(),
                        lastOpenedAtEpochMillis = input.readLong(),
                    )
                },
            ).also(::validate)
        }
    }

    fun reconcileCurrent(legacy: ImportMetadata, nowEpochMillis: Long): BookCatalog {
        require(nowEpochMillis >= 0)
        val existing = load() ?: BookCatalog(currentBookId = null, books = emptyList())
        val previous = existing.books.firstOrNull { it.bookId == legacy.sha256 }
        val current = BookCatalogEntry(
            bookId = legacy.sha256,
            documentSha256 = legacy.sha256,
            storedName = legacy.storedName,
            displayName = legacy.displayName,
            byteCount = legacy.byteCount,
            encoding = legacy.encoding,
            addedAtEpochMillis = previous?.addedAtEpochMillis ?: nowEpochMillis,
            lastOpenedAtEpochMillis = nowEpochMillis,
        )
        val reconciled = BookCatalog(
            currentBookId = current.bookId,
            books = (existing.books.filterNot { it.bookId == current.bookId } + current)
                .sortedWith(compareByDescending<BookCatalogEntry> { it.lastOpenedAtEpochMillis }.thenBy { it.bookId }),
        )
        save(reconciled)
        return reconciled
    }

    fun removeNonCurrent(bookId: String): BookCatalog {
        if (!SHA256.matches(bookId)) throw TxtImportException("책 식별자가 올바르지 않습니다.")
        val existing = load() ?: throw TxtImportException("책 목록을 찾을 수 없습니다.")
        if (existing.currentBookId == bookId) {
            throw TxtImportException("현재 읽는 책은 다른 책을 연 뒤 목록에서 제거할 수 있습니다.")
        }
        if (existing.books.none { it.bookId == bookId }) {
            throw TxtImportException("목록에서 책을 찾을 수 없습니다.")
        }
        val updated = existing.copy(books = existing.books.filterNot { it.bookId == bookId })
        save(updated)
        return updated
    }

    fun save(catalog: BookCatalog) {
        saveInternal(catalog) { temporary ->
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    write(output, catalog)
                    output.flush()
                    raw.fd.sync()
                }
            }
        }
    }

    internal fun saveWithOutput(
        catalog: BookCatalog,
        openOutput: (File) -> OutputStream,
    ) {
        saveInternal(catalog) { temporary ->
            openOutput(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output -> write(output, catalog) }
            }
        }
    }

    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("catalog-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    private fun saveInternal(catalog: BookCatalog, writeTemporary: (File) -> Unit) {
        validate(catalog)
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("책 목록 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("catalog-", ".partial", directory)
        try {
            writeTemporary(temporary)
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
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("책 목록을 저장하지 못했습니다.", error)
        } finally {
            temporary.delete()
        }
    }

    private fun write(output: DataOutputStream, catalog: BookCatalog) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeBoolean(catalog.currentBookId != null)
        catalog.currentBookId?.let(output::writeUTF)
        output.writeInt(catalog.books.size)
        catalog.books.forEach { book ->
            output.writeUTF(book.bookId)
            output.writeUTF(book.documentSha256)
            output.writeUTF(book.storedName)
            output.writeUTF(book.displayName)
            output.writeLong(book.byteCount)
            output.writeUTF(book.encoding)
            output.writeLong(book.addedAtEpochMillis)
            output.writeLong(book.lastOpenedAtEpochMillis)
        }
    }

    private fun validate(catalog: BookCatalog) {
        if (catalog.books.size > MAX_BOOKS) throw TxtImportException("책 목록이 너무 큽니다.")
        if (catalog.books.map(BookCatalogEntry::bookId).toSet().size != catalog.books.size) {
            throw TxtImportException("책 목록에 중복 식별자가 있습니다.")
        }
        if (catalog.currentBookId != null && catalog.books.none { it.bookId == catalog.currentBookId }) {
            throw TxtImportException("현재 책이 책 목록에 없습니다.")
        }
        catalog.books.forEach { book ->
            if (!SHA256.matches(book.bookId) || !SHA256.matches(book.documentSha256)) {
                throw TxtImportException("책 식별자가 올바르지 않습니다.")
            }
            if (book.bookId != book.documentSha256 || book.storedName != "${book.documentSha256}.original") {
                throw TxtImportException("책 원본 식별자가 올바르지 않습니다.")
            }
            if (book.displayName.isBlank() || book.displayName.length > 200) {
                throw TxtImportException("책 제목이 올바르지 않습니다.")
            }
            if (book.byteCount < 0 || book.addedAtEpochMillis < 0 || book.lastOpenedAtEpochMillis < 0) {
                throw TxtImportException("책 저장 정보가 올바르지 않습니다.")
            }
            if (book.encoding !in ENCODINGS) throw TxtImportException("책 인코딩 정보가 올바르지 않습니다.")
        }
    }

    companion object {
        const val FILE_NAME = "books.catalog"
        private const val MAGIC = 0x57564342
        private const val VERSION = 1
        private const val MAX_BOOKS = 10_000
        private val SHA256 = Regex("[0-9a-f]{64}")
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
