package com.avstaim.listai.feature.reader.bookview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DialogueSpacingTest {
    @Test fun protectsOpeningDashSpacingForCommonBookConventions() {
        for (dash in listOf('—', '–', '―', '-')) for (gap in listOf(" ", "\u00A0", "  ", " \u00A0")) {
            val source = "$dash${gap}Да, это прямая речь."
            assertEquals("$dash${"\u202F".repeat(gap.length)}Да, это прямая речь.", dialogueDisplayText(source).toString())
        }
    }

    @Test fun preservesEverySourceOffsetAcrossInlineBoundariesAndHardBreaks() {
        val source = "  — Я думал.\n–\u00A0Я умею читать.\n— Это опаснее. 😀\nОбычная проза — со своим тире."
        val display = dialogueDisplayText(source).toString()
        assertEquals(source.length, display.length)
        val changes = source.indices.filter { source[it] != display[it] }
        assertEquals(listOf(3, source.indexOf('–') + 1, source.indexOf("— Это") + 1), changes)
        assertEquals(source.substring(source.indexOf("😀")), display.substring(source.indexOf("😀")))
        assertEquals(display, dialogueDisplayText(display).toString())
    }

    @Test fun leavesProseCompoundsQuotesAndExplicitTypographyUntouched() {
        listOf("Обычная проза — со своим тире.", "кого-нибудь", "-42", "—Без пробела",
            "«— Цитата»", "—", "— ", "—\nРеплика на новой строке", "—\tТабуляция", "—\u202FТонкий пробел",
            "", "\n").forEach { source -> assertSame(source, dialogueDisplayText(source)) }
    }
}
