package com.avstaim.listai

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BundledBookLanguageTest {
    @Test fun supportedSystemLanguagesSelectOneEditionIncludingRegionalVariants() {
        mapOf(
            "ru-RU" to "ru", "ru-BY" to "ru", "en-GB" to "en",
            "es-MX" to "es", "de-AT" to "de", "fr-CA" to "fr",
            "it-CH" to "it", "pt-BR" to "pt-BR", "pt-PT" to "pt-BR",
        ).forEach { (tag, edition) ->
            assertEquals(tag, edition, bundledBookLanguage(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun unsupportedOrMissingPrimarySystemLanguageUsesEnglish() {
        for (tag in listOf("ja-JP", "zh-Hans-CN", "uk-UA", "pl-PL", "und", "")) {
            assertEquals(tag, "en", bundledBookLanguage(Locale.forLanguageTag(tag)))
        }
    }
}
