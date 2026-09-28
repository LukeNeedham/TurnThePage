package com.avstaim.listai.core.database

import org.junit.Assert.*
import org.junit.Test

class ShelfTopologyTest {
    private val original = ShelfTopology(listOf("book:a", "book:b", "book:c", "book:d"), emptyList(), setOf("a", "b", "c", "d"))

    @Test fun stackOccupiesFirstSelectedSlotAndDissolveKeepsMemberOrder() {
        val grouped = original.createStack("s", "  Series  ", listOf("c", "b", "c"))
        assertEquals(listOf("book:a", "stack:s", "book:d"), grouped.shelf)
        assertEquals(ShelfStack("s", "Series", listOf("c", "b")), grouped.stacks.single())
        assertEquals(listOf("book:a", "book:c", "book:b", "book:d"), grouped.dissolve("s").shelf)
    }

    @Test fun crossStackTransferIsUniqueSingleMemberStackSurvivesAndEmptyOneDisappears() {
        val groups = original.createStack("s", "First", listOf("a", "b"))
            .createStack("t", "Second", listOf("c", "d"))
        val moved = groups.addToStack("t", listOf("a", "a"))
        assertEquals(listOf("b"), moved.stacks.first { it.id == "s" }.bookIds)
        assertEquals(listOf("c", "d", "a"), moved.stacks.first { it.id == "t" }.bookIds)
        val allMoved = moved.addToStack("t", listOf("b"))
        assertEquals(listOf("stack:t"), allMoved.shelf)
        assertEquals(listOf("c", "d", "a", "b"), allMoved.stacks.single().bookIds)
    }

    @Test fun extractionAndStackReorderingHaveIndependentStableOrder() {
        val grouped = original.createStack("s", "Name", listOf("a", "b", "c"))
        val reordered = grouped.moveStackBook("s", "c", "a")
        assertEquals(listOf("c", "a", "b"), reordered.stacks.single().bookIds)
        assertEquals(grouped.shelf, reordered.shelf)
        val extracted = reordered.extract(listOf("b", "c"))
        assertEquals(listOf("stack:s", "book:c", "book:b", "book:d"), extracted.shelf)
        assertEquals(listOf("a"), extracted.stacks.single().bookIds)
        assertEquals(listOf("book:c", "book:b", "book:d", "stack:s"),
            extracted.moveShelfItem("stack:s", "book:d").shelf)
        assertTrue(extracted.extract(listOf("a")).stacks.isEmpty())
    }

    @Test fun undoPreservesLaterImportAndDoesNotResurrectDeletedMembers() {
        val before = original.createStack("s", "Original", listOf("a", "b"))
        val current = before.dissolve("s").copy(books = setOf("a", "c", "d", "new"),
            shelf = listOf("book:new", "book:a", "book:c", "book:d")).normalized()
        val restored = before.restoreAgainst(current)
        assertEquals(listOf("book:new", "stack:s", "book:c", "book:d"), restored.shelf)
        assertEquals(ShelfStack("s", "Original", listOf("a")), restored.stacks.single())
        assertEquals(current.books, restored.books)
        val noMembers = current.copy(books = setOf("c", "d", "new")).normalized()
        assertTrue(before.restoreAgainst(noMembers).stacks.isEmpty())
    }

    @Test fun cancelledOrStaleTargetsAreNoOpsAndNamesAreValidated() {
        assertEquals(original, original.createStack("s", "Name", listOf("a", "missing")))
        assertEquals(original, original.moveShelfItem("book:missing", "book:a"))
        assertEquals(original, original.addToStack("gone", listOf("a")))
        assertThrows(IllegalArgumentException::class.java) { original.createStack("s", "  ", listOf("a", "b")) }
        assertThrows(IllegalArgumentException::class.java) { original.createStack("s", "a".repeat(121), listOf("a", "b")) }
    }
}
