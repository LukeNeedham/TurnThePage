package com.avstaim.listai.reader.pagination

import java.util.Locale

/** Generated book furniture follows the book's language, independently of the app's UI locale. */
class BookTextLabels internal constructor(
    private val chapter: String,
    val illustration: String,
    private val oneMoreNote: String,
    private val otherMoreNotes: String,
    private val fewMoreNotes: String = otherMoreNotes,
    private val russianQuantities: Boolean = false,
) {
    fun chapter(number: String): String = "$chapter $number"

    fun moreNotes(count: Int): String {
        val pattern = if (russianQuantities) when {
            count % 10 == 1 && count % 100 != 11 -> oneMoreNote
            count % 10 in 2..4 && count % 100 !in 12..14 -> fewMoreNotes
            else -> otherMoreNotes
        } else if (count == 1) oneMoreNote else otherMoreNotes
        return pattern.replace("%d", count.toString())
    }
}

private val englishBookText = BookTextLabels("Chapter", "Illustration", "%d more note", "%d more notes")
private val russianBookText = BookTextLabels("Глава", "Иллюстрация", "Ещё %d примечание", "Ещё %d примечаний",
    "Ещё %d примечания", russianQuantities = true)

private val esBookText = BookTextLabels("Capítulo", "Ilustración", "%d nota más", "%d notas más")

fun bookTextLabels(localeTag: String): BookTextLabels = when (
    localeTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
) {
    "ru" -> russianBookText
    "es" -> esBookText
    else -> englishBookText
}
