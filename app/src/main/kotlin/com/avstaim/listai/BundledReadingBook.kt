package com.avstaim.listai

import android.content.Context
import androidx.core.app.LocaleManagerCompat
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.files.LibraryRepository
import java.util.Locale
import kotlinx.coroutines.flow.first

/** Match the primary system language; every Portuguese locale uses the Brazilian edition. */
internal fun bundledBookLanguage(locale: Locale): String = when (locale.language) {
    "ru", "es", "de", "fr", "it" -> locale.language
    "pt" -> "pt-BR"
    else -> "en"
}

internal fun bundledBookResource(language: String): Int = when (language) {
    "ru" -> R.raw.stage4_reading_sample
    "es" -> R.raw.stage4_reading_sample_es
    "de" -> R.raw.stage4_reading_sample_de
    "fr" -> R.raw.stage4_reading_sample_fr
    "it" -> R.raw.stage4_reading_sample_it
    "pt-BR" -> R.raw.stage4_reading_sample_pt_br
    else -> R.raw.stage4_reading_sample_en
}

/** Reserving the language before import keeps retries on the same content-addressed book. */
internal suspend fun importBundledReadingBookOnce(
    context: Context,
    repository: LibraryRepository,
    preferences: AppPreferences,
    systemLocale: Locale = LocaleManagerCompat.getSystemLocales(context)[0] ?: Locale.ROOT,
) {
    if (preferences.initialized.first()) return
    // Application resources can have a per-app locale on Android 13+. The book follows the system.
    val language = preferences.claimBundledBookLanguage(
        bundledBookLanguage(systemLocale),
    )
    val sample = repository.import("stage4_reading_sample_$language.fb2") {
        context.resources.openRawResource(bundledBookResource(language))
    }
    preferences.completeBundledBookImport(sample.value)
}

/** Content-addressed import restores a deleted sample and reuses the existing edition without duplicates. */
internal suspend fun ensureBundledReadingBook(
    context: Context, repository: LibraryRepository, preferences: AppPreferences,
): com.avstaim.listai.core.model.BookId {
    val language = preferences.claimBundledBookLanguage(bundledBookLanguage(
        LocaleManagerCompat.getSystemLocales(context)[0] ?: Locale.ROOT))
    return repository.import("stage4_reading_sample_$language.fb2") {
        context.resources.openRawResource(bundledBookResource(language))
    }
}
