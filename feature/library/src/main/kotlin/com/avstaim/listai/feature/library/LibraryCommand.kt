package com.avstaim.listai.feature.library

import com.avstaim.listai.core.model.BookId

/** Durable operations are executed by the controller/repository, never by a UI-only copy. */
sealed interface LibraryCommand {
    data class CreateStack(val name: String, val bookIds: List<BookId>) : LibraryCommand
    data class RenameStack(val stackId: String, val name: String) : LibraryCommand
    data class AddToStack(val stackId: String, val bookIds: List<BookId>) : LibraryCommand
    data class ExtractFromStack(val bookIds: List<BookId>) : LibraryCommand
    data class DissolveStack(val stackId: String) : LibraryCommand
    data class MoveShelfItem(val key: String, val targetKey: String) : LibraryCommand
    data class MoveStackBook(val stackId: String, val bookId: BookId, val targetBookId: BookId) : LibraryCommand
}
