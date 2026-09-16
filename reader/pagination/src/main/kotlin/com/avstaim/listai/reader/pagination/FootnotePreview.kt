package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.ParagraphRole

val LayoutKey.footnoteTextIndent: Float
    get() = (fontSizeSp * density * textScale * 1.7f).coerceAtMost(contentWidth * .22f)

/** Measurement-only key shared with rasterization; the outer page retains its own geometry. */
fun LayoutKey.footnoteLayoutKey(): LayoutKey = copy(
    fontSizeSp = fontSizeSp * .82f,
    lineHeightMultiplier = 1.18f,
    paragraphSpacingDp = 0f,
    horizontalMarginsDp = 0f,
    pageWidthPx = (contentWidth - footnoteTextIndent).toInt().coerceAtLeast(1),
)

/** VERSE avoids the body paragraph's first-line indent without forcing alignment or italics. */
fun footnoteFlowBlock(noteId: NoteId, content: List<InlineNode>): FlowBlock = FlowBlock(
    BookBlock.Paragraph(BlockId("listai:preview:${noteId.value}"), content, ParagraphRole.VERSE),
    globalOffset = 0,
)
