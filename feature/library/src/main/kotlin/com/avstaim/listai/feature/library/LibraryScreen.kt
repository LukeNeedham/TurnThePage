package com.avstaim.listai.feature.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import com.avstaim.listai.core.designsystem.BookCoverFace
import com.avstaim.listai.core.designsystem.drawListaiBackdrop
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
    onSettings: () -> Unit = {},
    darkTheme: Boolean? = null,
) {
    var deleting by remember { mutableStateOf<LibraryBook?>(null) }
    val colors = MaterialTheme.colorScheme
    val useDarkBackdrop = darkTheme ?: (colors.background.luminance() < .5f)
    // Updating a checkpoint must not move the book away from the shelf slot it launched from.
    val bookIds = books.map { it.id }.toSet()
    val orderedIds = remember(bookIds) {
        books.sortedWith(compareByDescending<LibraryBook> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.importedAt }.thenBy { it.id.value }).map { it.id }
    }
    val byId = books.associateBy { it.id }
    val ordered = orderedIds.mapNotNull(byId::get)
    BoxWithConstraints(modifier.fillMaxSize().drawBehind { drawListaiBackdrop(useDarkBackdrop) }
        .testTag("library_screen")) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout
        val safeWidth = with(density) {
            maxWidth - cutout.getLeft(this, direction).toDp() - cutout.getRight(this, direction).toDp()
        }.coerceAtLeast(40.dp)
        val columns = (safeWidth.value / 164f).toInt().coerceIn(1, 6)
        val bookWidth = ((safeWidth - 40.dp - 24.dp * (columns - 1)) / columns).coerceAtMost(154.dp)
        val bookHeight = bookWidth * 1.48f
        val rows = ordered.chunked(columns)
        val shelfRows = calculateLibraryLayout(
            viewportHeightDp = maxHeight.value,
            rowCount = rows.size,
            bookHeightDp = bookHeight.value,
            hasEmptyMessage = ordered.isEmpty(),
            topInsetDp = with(density) { cutout.getTop(this).toDp().value },
            bottomInsetDp = with(density) { cutout.getBottom(this).toDp().value },
        )
        val shelfModifier = Modifier.fillMaxSize().displayCutoutPadding()
        if (shelfRows.scrollable) {
            LazyColumn(shelfModifier,
                userScrollEnabled = interactionEnabled,
                contentPadding = PaddingValues(top = 32.dp, bottom = 88.dp)) {
                items(shelfRows.rowCount) { rowIndex ->
                    LibraryShelfRow(rowIndex, rows, bookWidth, bookHeight, ordered.isEmpty(), interactionEnabled,
                        hiddenBookId, onOpen, onBookBoundsChanged, coverContent, onDeleteRequest = { deleting = it })
                }
            }
        } else {
            Column(shelfModifier.padding(top = 32.dp, bottom = 88.dp)) {
                repeat(shelfRows.rowCount) { rowIndex ->
                    LibraryShelfRow(rowIndex, rows, bookWidth, bookHeight, ordered.isEmpty(), interactionEnabled,
                        hiddenBookId, onOpen, onBookBoundsChanged, coverContent, onDeleteRequest = { deleting = it })
                }
                repeat(shelfRows.decorativeRowCount) { decorativeIndex ->
                    LibraryShelfRow(shelfRows.rowCount + decorativeIndex, rows, bookWidth, bookHeight, false, interactionEnabled,
                        hiddenBookId, onOpen, onBookBoundsChanged, coverContent, onDeleteRequest = { deleting = it })
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (importing) {
                Row(Modifier.fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .94f), RoundedCornerShape(8.dp))
                    .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.library_importing), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelImport, modifier = Modifier.testTag("library_cancel_import")) { Text(stringResource(R.string.library_cancel)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    versionLabel?.takeIf(String::isNotBlank)?.let { version ->
                        Text(version, Modifier.testTag("library_version"),
                            color = colors.onBackground.copy(alpha = .48f),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val description = stringResource(R.string.library_settings)
                Box(Modifier.size(48.dp)
                    .shadow(2.dp, RoundedCornerShape(8.dp))
                    .background(colors.surface.copy(alpha = .82f), RoundedCornerShape(8.dp))
                    .clickable(enabled = interactionEnabled, role = Role.Button, onClick = onSettings)
                    .testTag("library_settings")
                    .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    PaperSettingsIcon()
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (!importing) {
                        SmallFloatingActionButton(onClick = { if (interactionEnabled) onImport() },
                            modifier = Modifier.fillMaxSize().testTag("library_import"),
                            containerColor = colors.surface.copy(alpha = .72f), contentColor = colors.primary) {
                            Text("+", style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
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
private fun LibraryShelfRow(
    rowIndex: Int,
    rows: List<List<LibraryBook>>,
    bookWidth: Dp,
    bookHeight: Dp,
    empty: Boolean,
    interactionEnabled: Boolean,
    hiddenBookId: BookId?,
    onOpen: (BookId) -> Unit,
    onBookBoundsChanged: (BookId, Rect?) -> Unit,
    coverContent: @Composable (LibraryBook) -> Unit,
    onDeleteRequest: (LibraryBook) -> Unit,
) {
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
                        onClick = { onOpen(book.id) }, onLongClick = { onDeleteRequest(book) })) {
                    coverContent(book)
                }
            }
        }
        ShelfEdge()
        if (empty && rowIndex == 0) {
            val colors = MaterialTheme.colorScheme
            Text(stringResource(R.string.library_empty), Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
                color = colors.onBackground.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ShelfEdge() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.onBackground.copy(alpha = .09f), size = Size(size.width, 1.dp.toPx()))
            drawRect(colors.surfaceVariant, topLeft = Offset(0f, 1.dp.toPx()), size = Size(size.width, 5.dp.toPx()))
            drawRect(colors.onBackground.copy(alpha = .16f), topLeft = Offset(0f, 6.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            drawRect(Color(0xFF15324B).copy(alpha = .07f), topLeft = Offset(0f, 7.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        }
    }
}

@Composable
private fun PaperSettingsIcon() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.35.dp.toPx())
        val width = size.width
        val height = size.height
        drawRoundRect(color, topLeft = Offset(2.dp.toPx(), 1.dp.toPx()),
            size = Size(width - 4.dp.toPx(), height - 2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = stroke)
        drawLine(color, Offset(width - 6.dp.toPx(), 1.dp.toPx()), Offset(width - 6.dp.toPx(), 5.dp.toPx()), strokeWidth = stroke.width)
        drawLine(color, Offset(width - 6.dp.toPx(), 5.dp.toPx()), Offset(width - 2.dp.toPx(), 5.dp.toPx()), strokeWidth = stroke.width)
        drawLine(color, Offset(5.dp.toPx(), 9.dp.toPx()), Offset(width - 5.dp.toPx(), 9.dp.toPx()), strokeWidth = stroke.width)
        drawLine(color, Offset(5.dp.toPx(), 13.dp.toPx()), Offset(width - 7.dp.toPx(), 13.dp.toPx()), strokeWidth = stroke.width)
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
