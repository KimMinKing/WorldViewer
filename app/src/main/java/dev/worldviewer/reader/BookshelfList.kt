package dev.worldviewer.reader

import java.text.Collator
import java.util.Locale

enum class BookshelfSort { RECENT, TITLE }

/** Changes only the visible order; the saved catalog and its book identities stay untouched. */
object BookshelfList {
    fun visible(
        books: List<BookCatalogEntry>,
        query: String,
        sort: BookshelfSort,
        locale: Locale = Locale.getDefault(),
    ): List<BookCatalogEntry> {
        val term = query.trim()
        val matching = if (term.isEmpty()) books else books.filter {
            it.displayName.contains(term, ignoreCase = true)
        }
        return when (sort) {
            BookshelfSort.RECENT -> matching.sortedWith(
                compareByDescending<BookCatalogEntry> { it.lastOpenedAtEpochMillis }.thenBy { it.bookId },
            )
            BookshelfSort.TITLE -> {
                val collator = Collator.getInstance(locale)
                matching.sortedWith { first, second ->
                    collator.compare(first.displayName, second.displayName)
                        .takeIf { it != 0 } ?: first.bookId.compareTo(second.bookId)
                }
            }
        }
    }
}
