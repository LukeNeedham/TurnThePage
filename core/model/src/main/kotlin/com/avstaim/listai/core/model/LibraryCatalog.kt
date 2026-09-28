package com.avstaim.listai.core.model

/** Window-independent shelf density; MEDIUM preserves the original library geometry. */
enum class ShelfZoom { LARGE, MEDIUM, SMALL }

data class LibraryStack(val id: String, val name: String, val bookIds: List<BookId>)

sealed interface LibraryShelfItem {
    val key: String

    data class Book(val bookId: BookId) : LibraryShelfItem {
        override val key: String get() = "book:${bookId.value}"
    }

    data class Stack(val stackId: String) : LibraryShelfItem {
        override val key: String get() = "stack:$stackId"
    }
}

/** One transactionally consistent snapshot, including books hidden inside stacks. */
data class LibraryCatalog(
    val books: List<LibraryBook> = emptyList(),
    val stacks: List<LibraryStack> = emptyList(),
    val shelf: List<LibraryShelfItem> = emptyList(),
)
