package com.avstaim.listai.feature.library

import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.LibraryCatalog
import com.avstaim.listai.core.model.LibraryShelfItem
import java.util.Locale

/** Search reads the catalog projection only; typing never opens or parses source files. */
internal fun normalizeLibrarySearch(value: String): String = value.lowercase(Locale.ROOT)
    .replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

internal fun librarySearchMatches(book: LibraryBook, query: String): Boolean {
    val tokens = normalizeLibrarySearch(query).split(' ').filter(String::isNotEmpty)
    val text = normalizeLibrarySearch(listOf(book.title, book.authors, book.series.orEmpty()).joinToString(" "))
    return tokens.all(text::contains)
}

/** Depth-one shelf traversal is the stable order for both empty and populated queries. */
internal fun searchLibrary(catalog: LibraryCatalog, query: String): List<LibraryBook> {
    val books = catalog.books.associateBy { it.id }
    val stacks = catalog.stacks.associateBy { it.id }
    val seen = mutableSetOf<BookId>()
    val traversal = buildList {
        catalog.shelf.forEach { item -> when (item) {
            is LibraryShelfItem.Book -> add(item.bookId)
            is LibraryShelfItem.Stack -> addAll(stacks[item.stackId]?.bookIds.orEmpty())
        } }
        // A new import can arrive one emission before its shelf placement. Keep it searchable.
        addAll(catalog.books.map { it.id })
    }
    return traversal.filter(seen::add).mapNotNull(books::get).filter { librarySearchMatches(it, query) }
}

internal fun suggestedStackName(books: List<LibraryBook>): String? {
    if (books.isEmpty()) return null
    val series = books.map { it.series?.trim()?.takeIf(String::isNotBlank) }
    if (series.first() != null && series.all { normalizeLibrarySearch(it.orEmpty()) == normalizeLibrarySearch(series.first().orEmpty()) }) {
        return series.first()
    }
    val authors = books.map { book -> book.authorNames.ifEmpty { listOf(book.authors) }.filter(String::isNotBlank) }
    return authors.first().firstOrNull { author ->
        authors.all { names -> names.any { normalizeLibrarySearch(it) == normalizeLibrarySearch(author) } }
    }
}
