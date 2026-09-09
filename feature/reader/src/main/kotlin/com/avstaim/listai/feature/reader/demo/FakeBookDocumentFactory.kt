package com.avstaim.listai.feature.reader.demo

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.ImageId
import com.avstaim.listai.core.model.InlineNode

object FakeBookDocumentFactory {
    const val DEFAULT_LEAF_COUNT = 32
    const val MIN_LEAF_COUNT = 20
    const val MAX_LEAF_COUNT = 50

    fun create(leafCount: Int = DEFAULT_LEAF_COUNT): BookDocument {
        require(leafCount in MIN_LEAF_COUNT..MAX_LEAF_COUNT) {
            "The demo document must contain between $MIN_LEAF_COUNT and $MAX_LEAF_COUNT leaves"
        }

        val blocks = List(leafCount) { index -> createBlock(index, leafCount) }

        return BookDocument(
            id = BookId("demo-atlas-of-quiet-pages"),
            metadata = BookMetadata(
                title = "The Atlas of Quiet Pages",
                authors = listOf("Open Book Workshop"),
                language = "en",
                genres = listOf("demo"),
                annotation = "A deterministic document used to develop the physical book renderer.",
            ),
            blocks = blocks,
        )
    }

    private fun createBlock(index: Int, leafCount: Int): BookBlock {
        val id = BlockId("demo-block-${index.toString().padStart(3, '0')}")

        return when {
            index == 0 -> paragraph(
                id = id,
                text = "The room settled as the cover opened. A narrow ribbon of light crossed " +
                    "the paper, and the first sentence waited without ceremony. Near the spine " +
                    "the sheet darkened gently; beyond the margin, the rest of the journey was " +
                    "already waiting.",
            )

            index == leafCount - 1 -> BookBlock.Heading(
                id = id,
                level = 1,
                content = listOf(InlineNode.Text("The End")),
            )

            index == 9 -> BookBlock.Image(
                id = id,
                imageId = ImageId("demo-moonlit-library"),
                alternativeText = "A moonlit path running between tall bookshelves",
            )

            index == 16 -> paragraph(
                id = id,
                text = "На полях оставалось достаточно воздуха, чтобы слова не спорили со страницей. " +
                    "Тишина книги ощущалась не пустотой, а приглашением перевернуть следующий лист.",
            )

            index % 8 == 0 -> BookBlock.Heading(
                id = id,
                level = 2,
                content = listOf(InlineNode.Text("Chapter ${index / 8 + 1}")),
            )

            else -> paragraph(
                id = id,
                text = sampleParagraph(index),
            )
        }
    }

    private fun paragraph(id: BlockId, text: String) = BookBlock.Paragraph(
        id = id,
        content = listOf(InlineNode.Text(text)),
    )

    private fun sampleParagraph(index: Int): String {
        val openings = listOf(
            "The room settled as the cover opened.",
            "A narrow ribbon of light crossed the paper.",
            "Every page carried the quiet weight of what came before it.",
            "The reader paused with a fingertip resting at the corner.",
        )
        val bodies = listOf(
            "The letters remained still while the leaf seemed ready to rise, bend, and reveal the next part of the journey.",
            "Near the spine the paper darkened gently, joining two pages into a single object rather than two panels on a screen.",
            "Nothing hurried the gesture; the page could move forward, stop halfway, or return to its familiar place.",
            "Beyond the margin, another scene waited under the surface, visible only when the sheet began to turn.",
        )
        val endings = listOf(
            "It was a small motion, but it made the story feel tangible.",
            "For a moment the device disappeared and only the book remained.",
            "The next sentence waited on the other side.",
            "Then the edge lifted from the reader's hand.",
        )

        return buildString {
            append(openings[index % openings.size])
            append(' ')
            append(bodies[index % bodies.size])
            append(' ')
            append(bodies[(index + 1) % bodies.size])
            append(' ')
            append(endings[index % endings.size])
        }
    }
}
