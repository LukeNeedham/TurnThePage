package com.avstaim.listai.feature.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import com.avstaim.listai.core.designsystem.BookCoverFace
import com.avstaim.listai.core.designsystem.drawListaiBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    onMoveBook: (BookId, BookId) -> Unit = { _, _ -> },
    onShare: (BookId) -> Unit = {},
    onImportFolder: () -> Unit = {},
) {
    var deleting by remember { mutableStateOf<LibraryBook?>(null) }
    var actionsFor by remember { mutableStateOf<LibraryBook?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val useDarkBackdrop = darkTheme ?: (colors.background.luminance() < .5f)
    // Repository order is durable. Reading checkpoints must never sort the shelf again.
    val incomingIds = books.map { it.id }
    val reorder = remember { LibraryReorderState(incomingIds) }
    LaunchedEffect(incomingIds) {
        if (reorder.drag == null) reorder.ids = incomingIds
        else if (reorder.drag?.originalOrder != incomingIds) { reorder.cancel(); reorder.ids = incomingIds }
    }
    val byId = books.associateBy { it.id }
    val ordered = reorder.ids.mapNotNull(byId::get)
    val listState = rememberLazyListState()
    var shelfBounds by remember { mutableStateOf(Rect.Zero) }
    val haptics = LocalHapticFeedback.current
    val latestMove by rememberUpdatedState(onMoveBook)
    val latestBooks by rememberUpdatedState(byId)
    val latestBoundsChanged by rememberUpdatedState(onBookBoundsChanged)
    val reportBounds: (BookId, Rect?) -> Unit = { id, bounds ->
        if (bounds == null) reorder.bounds.remove(id) else reorder.bounds[id] = bounds
        latestBoundsChanged(id, bounds)
    }
    val openActions: (LibraryBook) -> Unit = { actionsFor = it }
    val moveBook: (BookId, BookId) -> Unit = { source, destination ->
        reorder.ids = moveShelfBook(reorder.ids, source, destination)
        latestMove(source, destination)
    }
    val moveEarlier = stringResource(R.string.library_move_earlier)
    val moveLater = stringResource(R.string.library_move_later)
    val shareLabel = stringResource(R.string.library_share)
    val deleteLabel = stringResource(R.string.library_delete)
    val actionsLabel = stringResource(R.string.library_book_actions)
    val importLabel = stringResource(R.string.library_import)
    val accessibilityActions: (LibraryBook) -> List<CustomAccessibilityAction> = { book ->
        buildList {
            add(CustomAccessibilityAction(shareLabel) { onShare(book.id); true })
            val index = reorder.ids.indexOf(book.id)
            reorder.ids.getOrNull(index - 1)?.let { target ->
                add(CustomAccessibilityAction(moveEarlier) { moveBook(book.id, target); true })
            }
            reorder.ids.getOrNull(index + 1)?.let { target ->
                add(CustomAccessibilityAction(moveLater) { moveBook(book.id, target); true })
            }
            add(CustomAccessibilityAction(deleteLabel) { deleting = book; true })
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().drawBehind { drawListaiBackdrop(useDarkBackdrop) }
        .onGloballyPositioned { shelfBounds = it.boundsInRoot() }
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
        val edgeSize = with(density) { 64.dp.toPx() }
        val bottomControls = with(density) { 88.dp.toPx() }
        val dragSlop = with(density) { 8.dp.toPx() }
        LaunchedEffect(reorder.drag?.id, shelfRows.scrollable) {
            while (isActive && reorder.drag != null && shelfRows.scrollable) {
                val active = reorder.drag
                if (active?.moved == true) {
                    val y = active.pointer.y - shelfBounds.top
                    val lowerEdge = shelfBounds.height - bottomControls
                    val speed = when {
                        y < edgeSize -> -((edgeSize - y) / edgeSize).coerceIn(0f, 1f)
                        y > lowerEdge - edgeSize -> ((y - lowerEdge + edgeSize) / edgeSize).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (speed != 0f) {
                        listState.scrollBy(speed * with(density) { 12.dp.toPx() })
                        // Let newly visible rows publish their bounds before testing the next destination.
                        withFrameNanos { }
                        reorder.updateDestination()
                    }
                }
                delay(16)
            }
        }
        val shelfModifier = Modifier.fillMaxSize().pointerInput(interactionEnabled) {
            if (interactionEnabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                reorder.start(held.position + shelfBounds.topLeft)
                if (reorder.drag == null) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                var released = false
                try {
                    while (true) {
                        // After a hold the shelf owns the gesture. Consume release before the
                        // cover's clickable sees it, even when the finger never moved.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == held.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            reorder.drag?.let { active ->
                                if (active.moved) {
                                    val target = active.originalOrder.getOrNull(reorder.ids.indexOf(active.id))
                                    if (target != null && target != active.id) latestMove(active.id, target)
                                } else actionsFor = latestBooks[active.id]
                            }
                            reorder.drag = null
                            released = true
                            break
                        }
                        reorder.move(change.position - change.previousPosition, dragSlop)
                    }
                } finally {
                    if (!released) reorder.cancel()
                }
            }
        }.displayCutoutPadding()
        if (shelfRows.scrollable) {
            LazyColumn(shelfModifier, state = listState,
                userScrollEnabled = interactionEnabled && reorder.drag == null,
                contentPadding = PaddingValues(top = 32.dp, bottom = 88.dp)) {
                items(shelfRows.rowCount) { rowIndex ->
                    LibraryShelfRow(rowIndex, rows, bookWidth, bookHeight, ordered.isEmpty(), interactionEnabled,
                        hiddenBookId, reorder.drag?.id, onOpen, reportBounds, coverContent, openActions, actionsLabel, accessibilityActions)
                }
            }
        } else {
            Column(shelfModifier.padding(top = 32.dp, bottom = 88.dp)) {
                repeat(shelfRows.rowCount) { rowIndex ->
                    LibraryShelfRow(rowIndex, rows, bookWidth, bookHeight, ordered.isEmpty(), interactionEnabled,
                        hiddenBookId, reorder.drag?.id, onOpen, reportBounds, coverContent, openActions, actionsLabel, accessibilityActions)
                }
                repeat(shelfRows.decorativeRowCount) { decorativeIndex ->
                    LibraryShelfRow(shelfRows.rowCount + decorativeIndex, rows, bookWidth, bookHeight, false, interactionEnabled,
                        hiddenBookId, reorder.drag?.id, onOpen, reportBounds, coverContent, openActions, actionsLabel, accessibilityActions)
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
                        SmallFloatingActionButton(onClick = { if (interactionEnabled) importMenu = true },
                            modifier = Modifier.fillMaxSize().testTag("library_import")
                                .semantics { contentDescription = importLabel },
                            containerColor = colors.surface.copy(alpha = .72f), contentColor = colors.primary) {
                            Text("+", style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_import_files)) },
                            modifier = Modifier.testTag("library_import_files"),
                            onClick = { importMenu = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_import_folder)) },
                            modifier = Modifier.testTag("library_import_folder"),
                            onClick = { importMenu = false; onImportFolder() })
                    }
                }
            }
        }
        reorder.drag?.takeIf { it.moved }?.let { active ->
            latestBooks[active.id]?.let { book ->
                Box(Modifier.offset {
                    val position = active.topLeft - shelfBounds.topLeft
                    IntOffset(position.x.roundToInt(), position.y.roundToInt())
                }.size(bookWidth, bookHeight).graphicsLayer { scaleX = 1.04f; scaleY = 1.04f }
                    .shadow(18.dp, RoundedCornerShape(2.dp)).testTag("library_dragging_book")) {
                    coverContent(book)
                }
            }
        }
    }
    actionsFor?.let { book ->
        AlertDialog(onDismissRequest = { actionsFor = null },
            title = { Text(book.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.library_reorder_hint), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                    TextButton(onClick = { actionsFor = null; onShare(book.id) },
                        modifier = Modifier.fillMaxWidth().testTag("library_share")) { Text(shareLabel) }
                    val index = reorder.ids.indexOf(book.id)
                    reorder.ids.getOrNull(index - 1)?.let { target ->
                        TextButton(onClick = { moveBook(book.id, target); actionsFor = null },
                            modifier = Modifier.fillMaxWidth().testTag("library_move_earlier")) { Text(moveEarlier) }
                    }
                    reorder.ids.getOrNull(index + 1)?.let { target ->
                        TextButton(onClick = { moveBook(book.id, target); actionsFor = null },
                            modifier = Modifier.fillMaxWidth().testTag("library_move_later")) { Text(moveLater) }
                    }
                    TextButton(onClick = { actionsFor = null; deleting = book },
                        modifier = Modifier.fillMaxWidth().testTag("library_delete")) { Text(deleteLabel) }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text(stringResource(R.string.library_close)) } })
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
    draggingBookId: BookId?,
    onOpen: (BookId) -> Unit,
    onBookBoundsChanged: (BookId, Rect?) -> Unit,
    coverContent: @Composable (LibraryBook) -> Unit,
    onActions: (LibraryBook) -> Unit,
    actionsLabel: String,
    accessibilityActions: (LibraryBook) -> List<CustomAccessibilityAction>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(bookHeight + 22.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            rows.getOrNull(rowIndex).orEmpty().forEach { book -> key(book.id) {
                DisposableEffect(book.id) {
                    onDispose { onBookBoundsChanged(book.id, null) }
                }
                Box(Modifier.width(bookWidth).height(bookHeight)
                    .testTag("library_book_${book.id.value}")
                    .onGloballyPositioned { onBookBoundsChanged(book.id, it.boundsInRoot()) }
                    .graphicsLayer { alpha = when (book.id) { hiddenBookId -> 0f; draggingBookId -> .25f; else -> 1f } }
                    .shadow(7.dp, RoundedCornerShape(2.dp), ambientColor = Color(0xFF142A3D), spotColor = Color(0xFF142A3D))
                    .clickable(enabled = interactionEnabled, role = Role.Button, onClick = { onOpen(book.id) })
                    .semantics {
                        if (interactionEnabled) {
                            onLongClick(actionsLabel) { onActions(book); true }
                            customActions = accessibilityActions(book)
                        }
                    }) {
                    coverContent(book)
                }
            } }
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
