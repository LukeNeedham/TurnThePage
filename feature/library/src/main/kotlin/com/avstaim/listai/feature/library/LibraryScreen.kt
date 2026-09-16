package com.avstaim.listai.feature.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import com.avstaim.listai.core.designsystem.BookCoverFace
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
    hiddenBookId: BookId? = null,
    interactionEnabled: Boolean = true,
    onBookBoundsChanged: (BookId, Rect?) -> Unit = { _, _ -> },
    versionLabel: String? = null,
) {
    var deleting by remember { mutableStateOf<LibraryBook?>(null) }
    val colors = MaterialTheme.colorScheme
    // Updating a checkpoint must not move the book away from the shelf slot it launched from.
    val bookIds = books.map { it.id }.toSet()
    val orderedIds = remember(bookIds) {
        books.sortedWith(compareByDescending<LibraryBook> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.importedAt }.thenBy { it.id.value }).map { it.id }
    }
    val byId = books.associateBy { it.id }
    val ordered = orderedIds.mapNotNull(byId::get)
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background).testTag("library_screen")) {
        val columns = (maxWidth.value / 164f).toInt().coerceIn(1, 6)
        val bookWidth = ((maxWidth - 40.dp - 24.dp * (columns - 1)) / columns).coerceAtMost(154.dp)
        val bookHeight = bookWidth * 1.48f
        val rows = ordered.chunked(columns)
        LazyColumn(Modifier.fillMaxSize().displayCutoutPadding(),
            userScrollEnabled = interactionEnabled,
            contentPadding = PaddingValues(top = 32.dp, bottom = 88.dp)) {
            items(maxOf(3, rows.size)) { rowIndex ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(bookHeight + 22.dp).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                        rows.getOrNull(rowIndex).orEmpty().forEach { book ->
                            DisposableEffect(book.id) {
                                onDispose { onBookBoundsChanged(book.id, null) }
                            }
                            Box(Modifier.width(bookWidth).height(bookHeight)
                                .testTag("library_book_${book.id.value}")
                                .onGloballyPositioned { onBookBoundsChanged(book.id, it.boundsInRoot()) }
                                .graphicsLayer { alpha = if (book.id == hiddenBookId) 0f else 1f }
                                .shadow(7.dp, RoundedCornerShape(2.dp), ambientColor = Color(0xFF142A3D), spotColor = Color(0xFF142A3D))
                                .combinedClickable(enabled = interactionEnabled,
                                    onClick = { onOpen(book.id) }, onLongClick = { deleting = book })) {
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
            SmallFloatingActionButton(onClick = { if (interactionEnabled) onImport() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).testTag("library_import"),
                containerColor = colors.surface.copy(alpha = .72f), contentColor = colors.primary) {
                Text("+", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        versionLabel?.takeIf(String::isNotBlank)?.let { version ->
            Text(
                text = version,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 30.dp)
                    .testTag("library_version"),
                color = colors.onBackground.copy(alpha = .48f),
                style = MaterialTheme.typography.labelSmall,
            )
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
fun LibraryCover(book: LibraryBook, previewImage: ImageBitmap? = null, face: BookCoverFace = BookCoverFace.FRONT) {
    val cached = remember(book.coverPath) { book.coverPath?.let(ShelfCoverCache::get) }
    val decoded by produceState(cached, book.id, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { book.coverPath?.let(ShelfCoverCache::decode) }.getOrNull()
        }
    }
    val image = previewImage ?: decoded
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = book.title }) {
        val surface = rememberBookCoverSurface(book.title, book.authors.ifBlank { null },
            IntSize(constraints.maxWidth.coerceAtLeast(1), constraints.maxHeight.coerceAtLeast(1)), image)
        Canvas(Modifier.fillMaxSize()) {
            drawBookCover(surface, Rect(Offset.Zero, size), face)
        }
    }
}

/** Shares the already visible original artwork with the lifted book, avoiding a fallback flash. */
private object ShelfCoverCache {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(path: String): ImageBitmap? = cache.get(path)
    fun decode(path: String): ImageBitmap? {
        get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 4_194_304L) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { it.prepareToDraw() }?.asImageBitmap()
            ?.also { cache.put(path, it) }
    }
}
