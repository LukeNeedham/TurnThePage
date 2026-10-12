package com.avstaim.listai.reader.pagination

import org.junit.Assert.assertEquals
import org.junit.Test

class BookTextLabelsTest {
    @Test fun englishFallbackPreservesSourceChapterNumbersAndSingularNotes() {
        for (tag in listOf("en", "en-US", "EN_gb", "", "und", "sv")) {
            val labels = bookTextLabels(tag)
            assertEquals("Chapter IV", labels.chapter("IV"))
            assertEquals("Illustration", labels.illustration)
            assertEquals("1 more note", labels.moreNotes(1))
            assertEquals("12 more notes", labels.moreNotes(12))
        }
    }

    @Test fun russianNoteCountsUseTheWholeNumberIncludingTheTeens() {
        val labels = bookTextLabels("RU_ru")
        assertEquals("Глава четвёртая", labels.chapter("четвёртая"))
        assertEquals("Иллюстрация", labels.illustration)
        for (count in listOf(1, 21, 101)) assertEquals("Ещё $count примечание", labels.moreNotes(count))
        for (count in listOf(2, 4, 22, 104)) assertEquals("Ещё $count примечания", labels.moreNotes(count))
        for (count in listOf(0, 5, 11, 12, 14, 111, 112)) assertEquals("Ещё $count примечаний", labels.moreNotes(count))
    }

    @Test fun spanishBookCaptionsKeepTheNumberAndUseSingularNotes() {
        val labels = bookTextLabels("es")
        assertEquals("Capítulo IV", labels.chapter("IV"))
        assertEquals("Ilustración", labels.illustration)
        assertEquals("1 nota más", labels.moreNotes(1))
        assertEquals("12 notas más", labels.moreNotes(12))
    }

    @Test fun germanBookCaptionsKeepTheNumberAndUseSingularNotes() {
        val labels = bookTextLabels("de")
        assertEquals("Kapitel IV", labels.chapter("IV"))
        assertEquals("Abbildung", labels.illustration)
        assertEquals("1 weitere Anmerkung", labels.moreNotes(1))
        assertEquals("12 weitere Anmerkungen", labels.moreNotes(12))
    }
}
