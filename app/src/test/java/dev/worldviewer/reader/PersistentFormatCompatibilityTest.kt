package dev.worldviewer.reader

import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersistentFormatCompatibilityTest {
    private val document = "a".repeat(64)

    @Test
    fun `version one headers remain stable for every user data format`() {
        persistentFiles().forEach { format ->
            DataInputStream(format.file.inputStream()).use { input ->
                assertEquals(format.label, format.magic, input.readInt())
                assertEquals(format.label, 1, input.readInt())
            }
        }
    }

    @Test
    fun `trailing bytes are rejected without rewriting user data`() {
        persistentFiles().forEach { format ->
            Files.write(format.file.toPath(), byteArrayOf(0x7f), StandardOpenOption.APPEND)
            val corrupt = format.file.readBytes()

            val error = assertThrows(format.label, TxtImportException::class.java) { format.load() }

            assertEquals(format.label, "${format.label}에 예상하지 못한 데이터가 있습니다.", error.message)
            assertArrayEquals(format.label, corrupt, format.file.readBytes())
        }
    }

    @Test
    fun `unknown future versions are rejected without rewriting user data`() {
        persistentFiles().forEach { format ->
            val future = format.file.readBytes().also { bytes ->
                bytes[4] = 0
                bytes[5] = 0
                bytes[6] = 0
                bytes[7] = 2
            }
            format.file.writeBytes(future)

            assertThrows(format.label, TxtImportException::class.java) { format.load() }

            assertArrayEquals(format.label, future, format.file.readBytes())
        }
    }

    private fun persistentFiles(): List<PersistentFile> {
        val root = Files.createTempDirectory("worldviewer-format-v1").toFile()
        val metadataDirectory = root.resolve("imports")
        val catalogDirectory = root.resolve("library")
        val positionDirectory = root.resolve("positions")
        val styleDirectory = root.resolve("styles")
        val chapterDirectory = root.resolve("chapters")
        val meaningDirectory = root.resolve("meanings")
        val annotationDirectory = root.resolve("annotations")

        val metadata = ImportMetadata(
            storedName = "$document.original",
            displayName = "호환성.txt",
            sha256 = document,
            byteCount = 17,
            encoding = "UTF-8",
        )
        ImportMetadataStore(metadataDirectory).save(metadata)

        val catalog = BookCatalog(
            currentBookId = document,
            books = listOf(
                BookCatalogEntry(
                    bookId = document,
                    documentSha256 = document,
                    storedName = "$document.original",
                    displayName = "호환성.txt",
                    byteCount = 17,
                    encoding = "UTF-8",
                    addedAtEpochMillis = 10,
                    lastOpenedAtEpochMillis = 20,
                ),
            ),
        )
        BookCatalogStore(catalogDirectory).save(catalog)

        val position = ReadingPosition(document, 2, 11, "前", "後")
        ReadingPositionStore(positionDirectory).save(position)

        val preferences = ReaderStylePreferences(
            defaultStyle = ReaderStyle(21, 130, ReaderTheme.DARK),
            bookStyles = mapOf(document to ReaderStyle(24, 150, ReaderTheme.LIGHT)),
        )
        ReaderStyleStore(styleDirectory).save(preferences)

        val chapters = listOf(
            ManualChapter(
                id = "00000000-0000-0000-0000-000000000001",
                title = "第一章",
                utf16Offset = 3,
                quoteBefore = "前",
                quoteAfter = "後",
            ),
        )
        ManualChapterStore(chapterDirectory).save(document, chapters)

        val meanings = listOf(
            UserMeaning(
                id = "00000000-0000-0000-0000-000000000002",
                sourceText = "江湖",
                meaning = "강호",
                scope = MeaningScope.GLOBAL,
                documentSha256 = null,
                rangeStartUtf16 = null,
                rangeEndUtf16 = null,
                createdAtEpochMillis = 30,
                updatedAtEpochMillis = 30,
            ),
        )
        UserMeaningStore(meaningDirectory).save(meanings)

        val annotations = listOf(
            UserAnnotation(
                id = "00000000-0000-0000-0000-000000000003",
                type = AnnotationType.HIGHLIGHT,
                documentSha256 = document,
                startUtf16 = 1,
                endUtf16 = 3,
                quoteBefore = "前",
                quoteSelected = "江湖",
                quoteAfter = "後",
                note = null,
                createdAtEpochMillis = 40,
                updatedAtEpochMillis = 40,
            ),
        )
        UserAnnotationStore(annotationDirectory).save(annotations)

        return listOf(
            PersistentFile(
                "최근 문서 정보",
                0x5756494D,
                metadataDirectory.resolve(ImportMetadataStore.FILE_NAME),
            ) { ImportMetadataStore(metadataDirectory).load() },
            PersistentFile(
                "책 목록 정보",
                0x57564342,
                catalogDirectory.resolve(BookCatalogStore.FILE_NAME),
            ) { BookCatalogStore(catalogDirectory).load() },
            PersistentFile(
                "읽기 위치 정보",
                0x57565053,
                positionDirectory.resolve("$document.position"),
            ) { ReadingPositionStore(positionDirectory).load(document) },
            PersistentFile(
                "독서 스타일 정보",
                0x57565354,
                styleDirectory.resolve(ReaderStyleStore.FILE_NAME),
            ) { ReaderStyleStore(styleDirectory).load() },
            PersistentFile(
                "수동 목차 정보",
                0x57564D43,
                chapterDirectory.resolve("$document.chapters"),
            ) { ManualChapterStore(chapterDirectory).load(document) },
            PersistentFile(
                "사용자 뜻 정보",
                0x57564D4E,
                meaningDirectory.resolve(UserMeaningStore.FILE_NAME),
            ) { UserMeaningStore(meaningDirectory).load() },
            PersistentFile(
                "주석 정보",
                0x5756414E,
                annotationDirectory.resolve(UserAnnotationStore.FILE_NAME),
            ) { UserAnnotationStore(annotationDirectory).load() },
        )
    }

    private data class PersistentFile(
        val label: String,
        val magic: Int,
        val file: java.io.File,
        val load: () -> Any?,
    )
}
