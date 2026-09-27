package dev.worldviewer.reader

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

data class FolderTxtCandidate(
    val uri: Uri,
    val displayName: String,
    val sha256: String,
    val byteCount: Long,
    val encodings: List<EncodingCandidate>,
)

data class FolderTxtScan(
    val newFiles: List<FolderTxtCandidate>,
    val duplicateCount: Int,
    val failures: List<String>,
)

/** Scans only the directory explicitly granted by the Android folder picker. */
class FolderTxtScanner(private val resolver: ContentResolver) {
    fun scan(
        treeUri: Uri,
        existingHashes: Set<String>,
        cancellation: ImportCancellation,
        onCount: (Int) -> Unit = {},
    ): FolderTxtScan {
        val newFiles = mutableListOf<FolderTxtCandidate>()
        val failures = mutableListOf<String>()
        val seenHashes = existingHashes.toMutableSet()
        val visitedDirectories = mutableSetOf<String>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(DocumentsContract.getTreeDocumentId(treeUri) to "")
        var duplicateCount = 0
        var visitedCount = 0
        var reportedCount = 0
        while (pending.isNotEmpty()) {
            cancellation.throwIfCancelled()
            val (parentId, parentPath) = pending.removeFirst()
            if (!visitedDirectories.add(parentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
                ?: throw TxtImportException("선택한 폴더의 파일 목록을 읽을 수 없습니다.")
            cursor.use { rows ->
                while (rows.moveToNext()) {
                    cancellation.throwIfCancelled()
                    visitedCount++
                    if (visitedCount > MAX_DOCUMENTS) {
                        throw TxtImportException("폴더에 항목이 너무 많습니다. 더 작은 폴더를 선택하세요.")
                    }
                    val id = rows.getString(0) ?: continue
                    val name = rows.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val mime = rows.getString(2)
                    val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id to path)
                    } else if (name.endsWith(".txt", ignoreCase = true)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        runCatching { inspect(uri, path, cancellation) }
                            .onSuccess { file ->
                                if (seenHashes.add(file.sha256)) newFiles += file else duplicateCount++
                            }
                            .onFailure { error ->
                                if (error is TxtImportCancelledException) throw error
                                failures += "$path: ${error.message ?: "읽기 실패"}"
                            }
                        val count = newFiles.size + duplicateCount + failures.size
                        if (count - reportedCount >= 10) {
                            onCount(count)
                            reportedCount = count
                        }
                    }
                }
            }
        }
        return FolderTxtScan(newFiles, duplicateCount, failures)
    }

    private fun inspect(uri: Uri, path: String, cancellation: ImportCancellation): FolderTxtCandidate {
        val digest = MessageDigest.getInstance("SHA-256")
        val sample = java.io.ByteArrayOutputStream(TextEncodingDetector.SAMPLE_BYTES)
        var size = 0L
        (resolver.openInputStream(uri) ?: throw TxtImportException("파일을 열 수 없습니다.")).use { source ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                cancellation.throwIfCancelled()
                val count = source.read(bytes)
                if (count < 0) break
                if (size > Long.MAX_VALUE - count) throw TxtImportException("파일이 너무 큽니다.")
                size += count
                if (sample.size() < TextEncodingDetector.SAMPLE_BYTES) {
                    sample.write(bytes, 0, minOf(count, TextEncodingDetector.SAMPLE_BYTES - sample.size()))
                }
                digest.update(bytes, 0, count)
            }
        }
        val encodings = TextEncodingDetector.candidates(sample.toByteArray())
        if (encodings.isEmpty()) throw TxtImportException("지원하는 인코딩 후보가 없습니다.")
        return FolderTxtCandidate(
            uri = uri,
            displayName = path.takeLast(MAX_DISPLAY_NAME),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            byteCount = size,
            encodings = encodings,
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        const val MAX_DOCUMENTS = 20_000
        const val MAX_DISPLAY_NAME = 200
    }
}
