package dev.worldviewer.reader

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfListTest {
    private fun book(id: Char, title: String, opened: Long) = BookCatalogEntry(
        bookId = id.toString().repeat(64),
        documentSha256 = id.toString().repeat(64),
        storedName = "$id.original",
        displayName = title,
        byteCount = 1,
        encoding = "UTF-8",
        addedAtEpochMillis = 1,
        lastOpenedAtEpochMillis = opened,
    )

    @Test fun `query filters display titles without changing saved order or book identity`() {
        val first = book('a', "山海.txt", 10)
        val second = book('b', "Subfolder/江湖.TXT", 30)
        val third = book('c', "江湖二.epub", 20)
        val saved = listOf(second, third, first)

        assertEquals(listOf(second, third), BookshelfList.visible(saved, "  江湖  ", BookshelfSort.RECENT))
        assertEquals(listOf(second), BookshelfList.visible(saved, "FOLDER", BookshelfSort.RECENT))
        assertEquals(emptyList<BookCatalogEntry>(), BookshelfList.visible(saved, "없음", BookshelfSort.RECENT))
        assertEquals(saved, BookshelfList.visible(saved, " ", BookshelfSort.RECENT))
        assertEquals(listOf(second, third, first), saved)
    }

    @Test fun `recent and title ordering use existing metadata with stable ties`() {
        val alpha = book('a', "가 책", 20)
        val beta = book('b', "나 책", 20)
        val newest = book('c', "다 책", 30)
        val saved = listOf(newest, beta, alpha)

        assertEquals(listOf(newest, alpha, beta), BookshelfList.visible(saved, "", BookshelfSort.RECENT))
        assertEquals(listOf(alpha, beta, newest), BookshelfList.visible(saved, "", BookshelfSort.TITLE, Locale.KOREAN))
        assertEquals(saved, listOf(newest, beta, alpha))
    }
}
