package com.avstaim.listai.feature.library

import com.avstaim.listai.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private fun book(id: String, title: String, author: String = "", series: String? = null) = LibraryBook(
        BookId(id), title, author, "$id.fb2", null, 0, null, 100, ReadingProgress(), series = series,
    )
    @Test fun wordsMatchAcrossFieldsWithCaseSpacesAndYoNormalized() {
        val book = book("a", "Звёздное море", "Пётр Орлов", "Дальний берег")
        assertTrue(librarySearchMatches(book, "  ОРЛОВ   звездное БЕРЕГ "))
        assertFalse(librarySearchMatches(book, "звездное чужой"))
        assertTrue(librarySearchMatches(book, " \n "))
    }
    @Test fun globalSearchTraversesStackAtItsShelfPositionWithoutDuplicates() {
        val a = book("a", "A"); val b = book("b", "B"); val c = book("c", "C"); val d = book("d", "D")
        val catalog = LibraryCatalog(listOf(d, c, b, a), listOf(LibraryStack("s", "Stack", listOf(b.id, c.id))),
            listOf(LibraryShelfItem.Book(a.id), LibraryShelfItem.Stack("s"), LibraryShelfItem.Book(c.id)))
        assertEquals(listOf(a.id, b.id, c.id, d.id), searchLibrary(catalog, "").map { it.id })
        assertEquals(listOf(c), searchLibrary(catalog, "c"))
        assertTrue(searchLibrary(catalog, "missing").isEmpty())
    }
    @Test fun namingUsesSharedSeriesThenSharedAuthorWithoutGuessingUnknownMetadata() {
        assertEquals("Море", suggestedStackName(listOf(book("a", "A", "Пётр", "Море"), book("b", "B", "Мария", "море"))))
        assertEquals("Пётр", suggestedStackName(listOf(book("a", "A", "Пётр"), book("b", "B", "петр"))))
        assertNull(suggestedStackName(listOf(book("a", "A"), book("b", "B"))))
    }
}
