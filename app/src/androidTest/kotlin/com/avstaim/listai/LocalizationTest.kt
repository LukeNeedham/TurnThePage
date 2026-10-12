package com.avstaim.listai

import android.app.LocaleConfig
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.feature.library.R as LibraryR
import com.avstaim.listai.feature.reader.R as ReaderR
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Exercises Android's compiled resources and plural rules without changing the device locale. */
@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @Test
    fun requestedLocalesSelectResourcesFromAllThreeModules() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val supportedTags = setOf("en", "ru", "es", "de", "fr", "it", "pt-BR")
        val declaredTags = base.resources.getXml(R.xml.locales_config).use { xml ->
            buildSet {
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "locale") {
                        add(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name"))
                    }
                    xml.next()
                }
            }
        }
        assertEquals("Declared app languages", supportedTags, declaredTags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeConfig = LocaleConfig(base)
            assertEquals("Android must resolve the manifest locale configuration",
                LocaleConfig.STATUS_SUCCESS, localeConfig.status)
            val platformLocales = localeConfig.supportedLocales
            val platformTags = if (platformLocales == null) emptySet() else
                (0 until platformLocales.size()).map { platformLocales[it].toLanguageTag() }.toSet()
            assertEquals("System app-language picker", supportedTags, platformTags)
        }

        for (expected in locales) {
            val context = localizedContext(expected.tag)
            val resources = context.resources

            assertEquals(expected.tag, "Turn the Page", context.getString(R.string.app_name))
            assertEquals(expected.tag, expected.guideTitle,
                context.getString(ReaderR.string.reader_onboarding_menu_title))
            assertEquals("${expected.tag}: one book", expected.oneBook,
                resources.getQuantityString(LibraryR.plurals.library_sheet_book_count, 1, 1))
            assertEquals("${expected.tag}: two books", expected.twoBooks,
                resources.getQuantityString(LibraryR.plurals.library_sheet_book_count, 2, 2))

            val configuredLocale = resources.configuration.locales[0]
            assertEquals(expected.tag, Locale.forLanguageTag(expected.tag), configuredLocale)
            if (expected.tag == "pt-BR") {
                assertEquals("Brazilian Portuguese must retain its regional qualifier", "BR",
                    configuredLocale.country)
            }
        }
    }

    @Test
    fun importSummaryFormatsFourLocalizedLinesWithTheirOwnCounts() {
        for (expected in locales) {
            val summary = localizedContext(expected.tag)
                .getString(R.string.library_import_summary, 11, 22, 33, 44)

            assertEquals("${expected.tag}: escaped newlines must become four lines", 4,
                summary.lines().size)
            assertEquals(expected.tag, expected.summaryLines, summary.lines())
        }
    }

    private fun localizedContext(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        return base.createConfigurationContext(configuration)
    }

    private data class ExpectedLocale(
        val tag: String,
        val guideTitle: String,
        val oneBook: String,
        val twoBooks: String,
        val summaryLines: List<String>,
    )

    private val locales = listOf(
        ExpectedLocale("es", "Tu menú de lectura", "1 libro", "2 libros", listOf(
            "Añadidos: 11",
            "Ya estaban en la biblioteca: 22",
            "No se pudieron importar: 33",
            "Otros archivos omitidos: 44",
        )),
        ExpectedLocale("de", "Dein Lesemenü", "1 Buch", "2 Bücher", listOf(
            "Hinzugefügt: 11",
            "Bereits im Bücherregal: 22",
            "Nicht importiert: 33",
            "Andere Dateien übersprungen: 44",
        )),
        ExpectedLocale("fr", "Votre menu de lecture", "1 livre", "2 livres", listOf(
            "Ajoutés : 11",
            "Déjà dans la bibliothèque : 22",
            "Échec de l’importation : 33",
            "Autres fichiers ignorés : 44",
        )),
        ExpectedLocale("it", "Il menu di lettura", "1 libro", "2 libri", listOf(
            "Aggiunti: 11",
            "Già nella biblioteca: 22",
            "Importazione non riuscita: 33",
            "Altri file ignorati: 44",
        )),
        ExpectedLocale("pt-BR", "Seu menu de leitura", "1 livro", "2 livros", listOf(
            "Adicionados: 11",
            "Já estavam na biblioteca: 22",
            "Não foi possível importar: 33",
            "Outros arquivos ignorados: 44",
        )),
    )
}
