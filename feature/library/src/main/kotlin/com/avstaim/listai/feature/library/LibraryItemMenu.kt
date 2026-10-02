package com.avstaim.listai.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.LibraryStack

internal enum class LibraryMenuIcon { OPEN, SHARE, STACK, ADD, RENAME, EXTRACT, DELETE, DISSOLVE, EARLIER, LATER, SEARCH, CLOSE }

internal data class LibraryMenuAction(val label: Int, val tag: String, val icon: LibraryMenuIcon,
    val onClick: () -> Unit, val destructive: Boolean = false)

@Composable
internal fun LibraryItemMenu(
    book: LibraryBook? = null,
    stack: LibraryStack? = null,
    books: List<LibraryBook> = emptyList(),
    actions: List<LibraryMenuAction>,
    onDismiss: () -> Unit,
) {
    LibraryPaperSheet(onDismiss, Modifier.testTag("library_item_menu")) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val cover = book ?: stack?.bookIds?.firstNotNullOfOrNull { id -> books.firstOrNull { it.id == id } }
                if (cover != null) LibrarySheetCover(cover, stack = stack != null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (stack == null) R.string.library_sheet_book_label else R.string.library_sheet_stack_label),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stack?.name ?: book?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val secondary = stack?.let { pluralStringResource(R.plurals.library_sheet_book_count, it.bookIds.size, it.bookIds.size) }
                        ?: book?.authors.orEmpty()
                    if (secondary.isNotBlank()) Text(secondary, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
                val closeLabel = stringResource(R.string.library_close)
                IconButton(onDismiss, Modifier.size(40.dp).testTag("library_item_menu_close")
                    .semantics { contentDescription = closeLabel }) {
                    LibrarySheetIcon(LibraryMenuIcon.CLOSE, Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            actions.filterNot { it.destructive }.forEach { LibraryMenuActionRow(it) }
            val destructive = actions.filter { it.destructive }
            if (destructive.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                destructive.forEach { LibraryMenuActionRow(it) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LibraryMenuActionRow(action: LibraryMenuAction) {
    val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = action.onClick)
        .testTag(action.tag).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LibrarySheetIcon(action.icon, Modifier.size(24.dp), tint)
        Text(stringResource(action.label), style = MaterialTheme.typography.bodyLarge, color = tint,
            modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun LibrarySheetCover(book: LibraryBook, modifier: Modifier = Modifier, stack: Boolean = false) {
    Box(modifier.size(if (stack) 64.dp else 48.dp, 68.dp).clearAndSetSemantics { }) {
        if (stack) {
            Surface(Modifier.offset(x = 10.dp, y = 8.dp).size(48.dp, 58.dp),
                shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.outlineVariant) { }
            Surface(Modifier.offset(x = 5.dp, y = 4.dp).size(48.dp, 60.dp),
                shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) { }
        }
        Box(Modifier.size(48.dp, 64.dp)) { LibraryCover(book) }
    }
}

/** Small original vector marks, with a consistent physical stroke and no bitmap assets. */
@Composable
internal fun LibrarySheetIcon(icon: LibraryMenuIcon, modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) =
                drawLine(color, Offset(x, y), Offset(xx, yy), 1.65f, StrokeCap.Round)
            fun page(left: Float, top: Float, width: Float, height: Float) =
                drawRoundRect(color, Offset(left, top), Size(width, height), CornerRadius(1.5f), style = stroke)
            when (icon) {
                LibraryMenuIcon.OPEN -> { page(4f, 3f, 16f, 18f); line(8f, 3f, 8f, 21f); line(12f, 8f, 17f, 8f); line(12f, 12f, 17f, 12f) }
                LibraryMenuIcon.STACK -> { page(5f, 3f, 14f, 14f); line(3f, 7f, 3f, 20f); line(3f, 20f, 17f, 20f); line(8f, 7f, 16f, 7f); line(8f, 11f, 14f, 11f) }
                LibraryMenuIcon.ADD -> { page(3f, 4f, 12f, 16f); line(17f, 10f, 23f, 10f); line(20f, 7f, 20f, 13f) }
                LibraryMenuIcon.SHARE -> {
                    listOf(Offset(6f, 12f), Offset(18f, 5f), Offset(18f, 19f)).forEach { drawCircle(color, 2.4f, it, style = stroke) }
                    line(8.1f, 10.8f, 15.8f, 6.3f); line(8.1f, 13.2f, 15.8f, 17.7f)
                }
                LibraryMenuIcon.RENAME -> {
                    drawPath(Path().apply { moveTo(5f, 15f); lineTo(16f, 4f); lineTo(20f, 8f); lineTo(9f, 19f); lineTo(4f, 20f); close() }, color, style = stroke)
                    line(13f, 7f, 17f, 11f)
                }
                LibraryMenuIcon.EXTRACT -> { line(4f, 19f, 20f, 19f); line(12f, 4f, 12f, 15f); line(8f, 11f, 12f, 15f); line(12f, 15f, 16f, 11f) }
                LibraryMenuIcon.DELETE -> { page(6f, 7f, 12f, 14f); line(4f, 5f, 20f, 5f); line(9f, 2f, 15f, 2f); line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f) }
                LibraryMenuIcon.DISSOLVE -> { page(3f, 4f, 6f, 13f); page(15f, 7f, 6f, 13f); line(11f, 10f, 13f, 10f); line(11f, 14f, 13f, 14f) }
                LibraryMenuIcon.EARLIER, LibraryMenuIcon.LATER -> {
                    val up = icon == LibraryMenuIcon.EARLIER
                    line(12f, 4f, 12f, 20f)
                    val y = if (up) 4f else 20f
                    val base = if (up) 9f else 15f
                    line(7f, base, 12f, y); line(12f, y, 17f, base)
                }
                LibraryMenuIcon.SEARCH -> { drawCircle(color, 6.5f, Offset(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
                LibraryMenuIcon.CLOSE -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            }
        }
    }
}
