package com.avstaim.listai.feature.reader.demo

import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.plainText
import java.text.Normalizer

internal enum class DemoPageKind {
    TITLE,
    TEXT,
    IMAGE,
    CHAPTER,
    END,
}

internal data class DemoPageUiModel(
    val stableId: String,
    val title: String?,
    val body: String,
    val imageAlternativeText: String?,
    val kind: DemoPageKind,
)

internal fun BookDocument.toDemoPages(): List<DemoPageUiModel> {
    val firstBlock = blocks.firstOrNull()
    val normalizedMetadataTitle = metadata.title.normalizedTitle()
    val contentBlocks = if (
        firstBlock is BookBlock.Heading &&
        normalizedMetadataTitle.isNotEmpty() &&
        firstBlock.plainText().normalizedTitle() == normalizedMetadataTitle
    ) {
        blocks.drop(1)
    } else {
        blocks
    }

    // BookView requires one physical leaf. A metadata-only document therefore opens onto a clean
    // inner leaf instead of recreating its cover title or crashing with an empty page list.
    if (contentBlocks.isEmpty()) {
        return listOf(
            DemoPageUiModel(
                stableId = "${id.value}-empty-content",
                title = null,
                body = "",
                imageAlternativeText = null,
                kind = DemoPageKind.TEXT,
            ),
        )
    }

    return contentBlocks.mapIndexed { index, block ->
        when (block) {
            is BookBlock.Heading -> DemoPageUiModel(
                stableId = block.id.value,
                title = block.plainText(),
                body = if (index == contentBlocks.lastIndex) "" else "§ ${index / 8 + 1}",
                imageAlternativeText = null,
                kind = if (index == contentBlocks.lastIndex) {
                    DemoPageKind.END
                } else {
                    DemoPageKind.CHAPTER
                },
            )

            is BookBlock.Paragraph -> DemoPageUiModel(
                stableId = block.id.value,
                title = null,
                body = block.plainText(),
                imageAlternativeText = null,
                kind = DemoPageKind.TEXT,
            )

            is BookBlock.Image -> DemoPageUiModel(
                stableId = block.id.value,
                title = null,
                body = block.plainText(),
                imageAlternativeText = block.alternativeText,
                kind = DemoPageKind.IMAGE,
            )

            is BookBlock.Quote -> DemoPageUiModel(
                stableId = block.id.value,
                title = null,
                body = block.plainText(),
                imageAlternativeText = null,
                kind = DemoPageKind.TEXT,
            )

            is BookBlock.Poem -> DemoPageUiModel(
                stableId = block.id.value,
                title = block.title
                    .joinToString(separator = "\n") { it.plainText() }
                    .ifBlank { null },
                body = block.stanzas.joinToString(separator = "\n\n") { it.plainText() } +
                    block.textAuthors
                        .joinToString(separator = "\n", prefix = "\n") { it.plainText() }
                        .takeIf { block.textAuthors.isNotEmpty() }
                        .orEmpty(),
                imageAlternativeText = null,
                kind = DemoPageKind.TEXT,
            )

            is BookBlock.FixedPage, is BookBlock.Spacer -> DemoPageUiModel(
                stableId = block.id.value,
                title = null,
                body = "",
                imageAlternativeText = null,
                kind = DemoPageKind.TEXT,
            )
        }
    }
}

private val TitleWhitespace = Regex("\\s+")

private fun String.normalizedTitle(): String = Normalizer
    .normalize(this, Normalizer.Form.NFKC)
    .trim()
    .replace(TitleWhitespace, " ")
    .lowercase()

internal object DemoPageNavigator {
    /**
     * Maps the semantic page under the reader's focus to the canonical physical spread that
     * contains it. Page zero is the right-only first content leaf; all following spreads start on an odd
     * index. Keeping this derivation separate prevents a narrow -> wide -> narrow resize from
     * silently moving the reader from the right leaf to the preceding left leaf.
     */
    fun layoutAnchorIndex(current: Int, pageCount: Int, spread: Boolean): Int {
        require(pageCount > 0) { "pageCount must be positive" }
        val semanticIndex = current.coerceIn(0, pageCount - 1)
        if (!spread || semanticIndex == 0) return semanticIndex
        return if (semanticIndex % 2 == 0) semanticIndex - 1 else semanticIndex
    }

    fun nextIndex(current: Int, pageCount: Int, spread: Boolean): Int {
        require(pageCount > 0) { "pageCount must be positive" }
        val layoutAnchor = layoutAnchorIndex(current, pageCount, spread)
        if (!spread) return (layoutAnchor + 1).coerceAtMost(pageCount - 1)
        if (layoutAnchor <= 0) return 1.coerceAtMost(pageCount - 1)

        // A spread anchored at layoutAnchor already exposes layoutAnchor + 1 on its right. If there is no
        // page after that, advancing would repeat the visible right page on the left.
        val nextSpread = layoutAnchor + 2
        return if (nextSpread < pageCount) nextSpread else layoutAnchor
    }

    fun previousIndex(current: Int, pageCount: Int, spread: Boolean): Int {
        require(pageCount > 0) { "pageCount must be positive" }
        val layoutAnchor = layoutAnchorIndex(current, pageCount, spread)
        if (layoutAnchor <= 0) return 0
        val step = if (spread && layoutAnchor > 1) 2 else 1
        return (layoutAnchor - step).coerceAtLeast(0)
    }
}
