package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView

@PreviewTest
@Preview(name = "Dialogue — phone light ru", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Dialogue — phone dark ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DialogueTypographyPhoneScreenshots() { DialogueTypographyPreview() }

@PreviewTest
@Preview(name = "Dialogue — narrow spread dark ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DialogueTypographySpreadScreenshot() { DialogueTypographyPreview(spread = true) }

@PreviewTest
@Preview(name = "Dialogue — wide spread dark ru 26sp", widthDp = 1000, heightDp = 760, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DialogueTypographyWideSpreadScreenshot() { DialogueTypographyPreview(spread = true, fontSizeSp = 26f) }

@PreviewTest
@Preview(name = "Dialogue — narrow large type ru", widthDp = 320, heightDp = 700, locale = "ru", fontScale = 1.3f)
@Composable
fun DialogueTypographyLargeTypeScreenshot() { DialogueTypographyPreview(fontSizeSp = 26f) }

@PreviewTest
@Preview(name = "Dialogue — long and short em dash en", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun DialogueTypographyEnglishScreenshot() { DialogueTypographyPreview(russian = false) }

@PreviewTest
@Preview(name = "Dialogue — NBSP and inline styles ru", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun DialogueTypographyInlineScreenshot() { DialogueTypographyPreview(inlineStyles = true) }

/** Uses production pagination, justification, paper and controls, starting on the first reply. */
@Composable
private fun DialogueTypographyPreview(
    russian: Boolean = true,
    spread: Boolean = false,
    fontSizeSp: Float = 18f,
    inlineStyles: Boolean = false,
) {
    val dark = isSystemInDarkTheme()
    val document = remember(russian, inlineStyles) { dialogueDocument(russian, inlineStyles) }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(
            emptyList(), document.metadata.title, null,
            modifier = Modifier.fillMaxSize(),
            document = document,
            forcedPresentation = if (spread) BookPresentation.SPREAD else BookPresentation.SINGLE,
            initialLocation = BookLocation(BlockId("dialogue-start"), 0, 0L),
            initialCover = CoverPosition.OPEN,
            outerPadding = 4.dp,
            coverInset = 3.dp,
            fontSizeSp = fontSizeSp,
            darkTheme = dark,
            onAddBookmark = {},
            pageControls = { layout ->
                ReaderTopControls(layout, dark, {}, {}, {}, {}, modifier = Modifier.fillMaxSize())
            },
        )
    }
}

private fun dialogueDocument(russian: Boolean, inlineStyles: Boolean): BookDocument {
    val replies = if (russian) listOf(
        "— Я думал, пришлют кого-нибудь, кто умеет выбрасывать бумаги, — сказал он вместо приветствия.",
        "— Я умею читать описи.",
        "— Это опаснее.",
    ) else listOf(
        "— I thought they would send someone who knew how to throw away papers, he said instead of greeting her.",
        "— I can read inventories.",
        "— That is more dangerous.",
    )
    val prose = if (russian)
        "На столе лежали раскрытые папки и пожелтевшие листы. За высоким окном темнел двор, а в комнате было тихо. Он придвинул лампу и осторожно перевернул следующую страницу описи."
    else
        "Open folders and yellowed sheets lay on the table. Beyond the tall window the courtyard was growing dark, and the room was quiet. He moved the lamp closer and carefully turned the next page of the inventory."
    val passage = replies.mapIndexed { index, reply ->
        if (!inlineStyles) listOf(InlineNode.Text(reply)) else when (index) {
            0 -> listOf(InlineNode.Text("—\u00A0"), InlineNode.Emphasis(listOf(InlineNode.Text("Я"))),
                InlineNode.Text(reply.removePrefix("— Я")))
            1 -> listOf(InlineNode.Text("—"), InlineNode.Text("\u00A0"),
                InlineNode.Strong(listOf(InlineNode.Text("Я"))), InlineNode.Text(reply.removePrefix("— Я")))
            else -> listOf(InlineNode.Text("— "), InlineNode.Emphasis(listOf(InlineNode.Text("Это"))),
                InlineNode.Text(reply.removePrefix("— Это")))
        }
    } + listOf(listOf(InlineNode.Text(prose)))
    // Repeating the passage keeps dialogue and normal prose visible on both physical leaves.
    val blocks = List(4) { passage }.flatten().mapIndexed { index, content ->
        BookBlock.Paragraph(BlockId(if (index == 0) "dialogue-start" else "dialogue-$index"), content)
    }
    return BookDocument(
        BookId("dialogue-typography-${if (russian) "ru" else "en"}-${if (inlineStyles) "inline" else "plain"}"),
        BookMetadata(if (russian) "Разговор в архиве" else "A Conversation in the Archive", language = if (russian) "ru" else "en"),
        blocks,
    )
}
