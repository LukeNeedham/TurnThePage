package com.avstaim.listai.feature.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A matte cabinet: books are the navigation, and each row rests on a physical shelf. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    importing: Boolean,
    onImport: () -> Unit,
    onCancelImport: () -> Unit,
    onOpen: (BookId) -> Unit,
    onDelete: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    coverContent: @Composable (LibraryBook) -> Unit = { LibraryCover(it) },
) {
    var deleting by remember { mutableStateOf<LibraryBook?>(null) }
    val colors = MaterialTheme.colorScheme
    val ordered = remember(books) {
        books.sortedWith(compareByDescending<LibraryBook> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.importedAt }.thenBy { it.id.value })
    }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background).testTag("library_screen")) {
        val columns = (maxWidth.value / 164f).toInt().coerceIn(1, 6)
        val bookWidth = ((maxWidth - 40.dp - 24.dp * (columns - 1)) / columns).coerceAtMost(154.dp)
        val bookHeight = bookWidth * 1.48f
        val rows = ordered.chunked(columns)
        LazyColumn(Modifier.fillMaxSize().displayCutoutPadding(), contentPadding = PaddingValues(top = 32.dp, bottom = 88.dp)) {
            item {
                Text(stringResource(R.string.library_title), Modifier.padding(start = 26.dp, bottom = 24.dp),
                    color = colors.onBackground.copy(alpha = .7f), style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif)
            }
            items(maxOf(3, rows.size)) { rowIndex ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(bookHeight + 22.dp).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                        rows.getOrNull(rowIndex).orEmpty().forEach { book ->
                            Box(Modifier.width(bookWidth).height(bookHeight)
                                .testTag("library_book_${book.id.value}")
                                .shadow(7.dp, RoundedCornerShape(2.dp), ambientColor = Color(0xFF142A3D), spotColor = Color(0xFF142A3D))
                                .combinedClickable(onClick = { onOpen(book.id) }, onLongClick = { deleting = book })) {
                                coverContent(book)
                            }
                        }
                    }
                    ShelfEdge()
                    if (ordered.isEmpty() && rowIndex == 0) {
                        Text(stringResource(R.string.library_empty), Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
                            color = colors.onBackground.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
        if (importing) {
            Row(Modifier.align(Alignment.BottomCenter).padding(20.dp)
                .shadow(4.dp, RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .94f), RoundedCornerShape(8.dp))
                .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.library_importing), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancelImport, modifier = Modifier.testTag("library_cancel_import")) { Text(stringResource(R.string.library_cancel)) }
            }
        } else {
            SmallFloatingActionButton(onClick = onImport,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).testTag("library_import"),
                containerColor = colors.surface.copy(alpha = .72f), contentColor = colors.primary) {
                Text("+", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
    deleting?.let { book ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_message, book.title)) },
            confirmButton = { TextButton(onClick = { onDelete(book.id); deleting = null }, modifier = Modifier.testTag("library_confirm_delete")) { Text(stringResource(R.string.library_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.library_cancel)) } })
    }
}

@Composable
private fun ShelfEdge() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        drawRect(colors.onBackground.copy(alpha = .09f), size = Size(size.width, 3.dp.toPx()))
        drawRect(colors.surfaceVariant, topLeft = Offset(0f, 3.dp.toPx()), size = Size(size.width, 8.dp.toPx()))
        drawRect(colors.onBackground.copy(alpha = .16f), topLeft = Offset(0f, 11.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        drawRect(Color(0xFF15324B).copy(alpha = .07f), topLeft = Offset(0f, 12.dp.toPx()), size = Size(size.width, 4.dp.toPx()))
    }
}

@Composable
internal fun LibraryCover(book: LibraryBook, previewImage: ImageBitmap? = null) {
    val decoded by produceState<ImageBitmap?>(null, book.id, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { book.coverPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    val image = previewImage ?: decoded
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 2.dp, topEnd = 4.dp, bottomEnd = 3.dp))
        .background(colors.secondaryContainer).drawBehind {
            drawRect(Color(0xFF19344C).copy(alpha = .14f), size = Size(6.dp.toPx(), size.height))
            drawLine(Color.White.copy(alpha = .12f), Offset(7.dp.toPx(), 0f), Offset(7.dp.toPx(), size.height), 1.dp.toPx())
        }, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = book.title, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 3.dp, top = 3.dp, bottom = 3.dp))
        } else {
            Column(Modifier.fillMaxSize().padding(start = 17.dp, end = 11.dp, top = 24.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(book.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer,
                    textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(24.dp).height(1.dp).background(colors.onSecondaryContainer.copy(alpha = .45f)))
                Spacer(Modifier.height(10.dp))
                Text(book.authors.ifBlank { stringResource(R.string.library_unknown_author) },
                    fontFamily = FontFamily.Serif, style = MaterialTheme.typography.labelSmall,
                    color = colors.onSecondaryContainer.copy(alpha = .78f), textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
