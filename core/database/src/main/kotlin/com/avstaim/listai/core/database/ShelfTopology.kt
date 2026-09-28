package com.avstaim.listai.core.database

/** Only placement is captured for undo. Reader checkpoints, bookmarks and files never enter it. */
data class ShelfTopology(
    val shelf: List<String>,
    val stacks: List<ShelfStack>,
    val books: Set<String>,
) {
    fun createStack(id: String, name: String, selected: List<String>): ShelfTopology {
        val members = selected.distinct().filter { it in books }
        if (members.size < 2) return this
        val title = validatedStackName(name)
        val positions = shelf.filter { key ->
            if (key.startsWith("book:")) key.removePrefix("book:") in members
            else stacks.firstOrNull { stackKey(it.id) == key }?.bookIds?.any { it in members } == true
        }
        val insertion = shelf.indexOfFirst { it in positions }.coerceAtLeast(0)
        val detached = removeMembers(members.toSet())
        val before = shelf.take(insertion).count { it in detached.shelf }
        return detached.copy(
            shelf = detached.shelf.toMutableList().apply { add(before, stackKey(id)) },
            stacks = detached.stacks + ShelfStack(id, title, members),
        ).normalized()
    }

    fun renameStack(id: String, name: String): ShelfTopology = copy(stacks = stacks.map {
        if (it.id == id) it.copy(name = validatedStackName(name)) else it
    })

    fun addToStack(id: String, selected: List<String>): ShelfTopology {
        val target = stacks.firstOrNull { it.id == id } ?: return this
        val members = selected.distinct().filter { it in books && it !in target.bookIds }
        if (members.isEmpty()) return this
        val detached = removeMembers(members.toSet())
        return detached.copy(stacks = detached.stacks.map {
            if (it.id == id) it.copy(bookIds = it.bookIds + members) else it
        }).normalized()
    }

    fun extract(selected: List<String>): ShelfTopology {
        val members = selected.toSet()
        val expanded = shelf.flatMap { key ->
            val stack = stacks.firstOrNull { stackKey(it.id) == key }
            if (stack == null) listOf(key) else listOf(key) + stack.bookIds.filter { it in members }.map(::bookKey)
        }
        return copy(shelf = expanded, stacks = stacks.map {
            it.copy(bookIds = it.bookIds.filterNot(members::contains))
        }).normalized()
    }

    fun dissolve(id: String): ShelfTopology {
        val stack = stacks.firstOrNull { it.id == id } ?: return this
        return copy(shelf = shelf.flatMap { if (it == stackKey(id)) stack.bookIds.map(::bookKey) else listOf(it) },
            stacks = stacks.filterNot { it.id == id }).normalized()
    }

    fun moveShelfItem(key: String, targetKey: String): ShelfTopology = copy(shelf = shelf.moving(key, targetKey))

    fun moveStackBook(id: String, bookId: String, targetBookId: String): ShelfTopology = copy(stacks = stacks.map {
        if (it.id == id) it.copy(bookIds = it.bookIds.moving(bookId, targetBookId)) else it
    })

    /** A deleted book stays deleted, and an import since the operation stays on the root shelf. */
    fun restoreAgainst(current: ShelfTopology): ShelfTopology {
        val added = current.books - books
        val newItems = current.shelf.filter { it.startsWith("book:") && it.removePrefix("book:") in added }
        return copy(books = current.books, shelf = newItems + shelf).normalized()
    }

    fun normalized(): ShelfTopology {
        val assigned = mutableSetOf<String>()
        val liveStacks = stacks.distinctBy { it.id }.mapNotNull { stack ->
            val members = stack.bookIds.filter { it in books && assigned.add(it) }
            stack.copy(bookIds = members).takeIf { members.isNotEmpty() }
        }
        val rootBooks = books - assigned
        val valid = rootBooks.mapTo(mutableSetOf(), ::bookKey) + liveStacks.map { stackKey(it.id) }
        val root = shelf.filter { it in valid }.distinct().toMutableList()
        liveStacks.forEach { if (stackKey(it.id) !in root) root += stackKey(it.id) }
        rootBooks.sorted().forEach { if (bookKey(it) !in root) root += bookKey(it) }
        return copy(shelf = root, stacks = liveStacks)
    }

    private fun removeMembers(ids: Set<String>): ShelfTopology = copy(
        shelf = shelf.filterNot { it.startsWith("book:") && it.removePrefix("book:") in ids },
        stacks = stacks.map { it.copy(bookIds = it.bookIds.filterNot(ids::contains)) }.filter { it.bookIds.isNotEmpty() },
    ).let { changed -> changed.copy(shelf = changed.shelf.filter { key ->
        !key.startsWith("stack:") || changed.stacks.any { stackKey(it.id) == key }
    }) }

    companion object {
        fun bookKey(id: String): String = "book:$id"
        fun stackKey(id: String): String = "stack:$id"
    }
}

data class ShelfStack(val id: String, val name: String, val bookIds: List<String>)

fun validatedStackName(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 120) }

private fun List<String>.moving(id: String, target: String): List<String> {
    val from = indexOf(id)
    val to = indexOf(target)
    if (from < 0 || to < 0 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
