package com.avstaim.listai.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.*
import kotlin.math.roundToInt

enum class PaperInsertSection { MENU, SETTINGS, BOOKMARKS, PROGRESS }

/** A loose folded sheet. Its draft position never escapes until the reader confirms it. */
@Composable
fun PaperReaderInsert(
    preferences: ReaderPreferences,
    onPreferencesChange: (ReaderPreferences) -> Unit,
    bookmarks: List<Bookmark>,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onBookmarkSelected: (Bookmark) -> Unit,
    onReturnToShelf: () -> Unit,
    onDismiss: () -> Unit,
    onGoToToc: () -> Unit,
    onReturnFromToc: (() -> Unit)?,
    navigation: ReaderNavigationState,
    onConfirmNavigation: (BookLocation) -> Unit,
    modifier: Modifier = Modifier,
    isBookmarked: Boolean = false,
    onRemoveCurrentBookmark: () -> Unit = {},
    canBookmarkCurrent: Boolean = true,
    initialSection: PaperInsertSection = PaperInsertSection.MENU,
) {
    var section by remember { mutableStateOf(initialSection) }
    var draft by remember { mutableStateOf(navigation.current) }
    var percentText by remember { mutableStateOf(((draft.fraction * 1000).roundToInt() / 10f).toString()) }
    var pageText by remember { mutableStateOf(draft.pageNumber?.toString().orEmpty()) }
    fun selectPreview(preview: ReaderNavigationPreview) {
        draft = preview
        percentText = ((preview.fraction * 1000).roundToInt() / 10f).toString()
        pageText = preview.pageNumber?.toString().orEmpty()
    }
    LaunchedEffect(navigation) {
        // A growing pagination prefix must not discard a place the reader is previewing.
        val refreshed = navigation.previewFraction(draft.fraction)
        if (pageText == draft.pageNumber?.toString().orEmpty()) {
            pageText = refreshed.pageNumber?.toString().orEmpty()
        }
        draft = draft.copy(pageNumber = refreshed.pageNumber, totalPages = refreshed.totalPages)
    }
    val percentValid = percentText.replace(',', '.').toFloatOrNull()?.let { it.isFinite() && it in 0f..100f } == true
    val pageValid = (pageText.isBlank() && draft.pageNumber == null) ||
        pageText.toIntOrNull()?.let { it in 1..navigation.availablePages } == true
    val colors = MaterialTheme.colorScheme
    val shape = remember { InsertPaperShape }
    Column(modifier.widthIn(max = 380.dp).fillMaxWidth().heightIn(max = 680.dp)
        .shadow(16.dp, shape, ambientColor = Color(0xFF102A41), spotColor = Color(0xFF102A41))
        .clip(shape).background(colors.surface).drawBehind {
            drawLine(colors.outlineVariant.copy(alpha = .5f), Offset(20.dp.toPx(), 0f), Offset(20.dp.toPx(), size.height), 1.dp.toPx())
            val fold = 20.dp.toPx()
            drawPath(Path().apply { moveTo(size.width - fold, 0f); lineTo(size.width - fold, fold); lineTo(size.width, fold); close() },
                colors.surfaceVariant)
        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        .testTag("reader_paper_insert")
        .verticalScroll(rememberScrollState()).padding(start = 36.dp, end = 28.dp, top = 30.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val title = when (section) {
            PaperInsertSection.MENU -> R.string.insert_title
            PaperInsertSection.SETTINGS -> R.string.insert_settings
            PaperInsertSection.BOOKMARKS -> R.string.insert_bookmarks
            PaperInsertSection.PROGRESS -> R.string.insert_progress
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif, color = colors.onSurface)
        InkRule()
        when (section) {
            PaperInsertSection.MENU -> {
                PaperAction(stringResource(R.string.insert_read), "reader_insert_dismiss", onDismiss, marker = "01")
                PaperAction(stringResource(R.string.insert_settings), "reader_insert_settings", { section = PaperInsertSection.SETTINGS }, marker = "02")
                PaperAction(stringResource(R.string.insert_toc), "reader_insert_toc", onGoToToc, marker = "03")
                onReturnFromToc?.let { PaperAction(stringResource(R.string.insert_return_from_toc), "reader_insert_return_from_toc", it, marker = "↩") }
                PaperAction(stringResource(R.string.insert_bookmarks), "reader_bookmarks", { section = PaperInsertSection.BOOKMARKS }, marker = "04")
                PaperAction(stringResource(R.string.insert_progress), "reader_insert_progress", { selectPreview(navigation.current); section = PaperInsertSection.PROGRESS }, marker = "05")
                InkRule()
                PaperAction(stringResource(R.string.insert_shelf), "reader_return_to_shelf", onReturnToShelf, marker = "↗")
            }
            PaperInsertSection.SETTINGS -> {
                Text(stringResource(R.string.insert_text_size), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("−", Modifier.testTag("reader_font_smaller").clickable(enabled = preferences.fontSizeSp > 12f) {
                        onPreferencesChange(preferences.copy(fontSizeSp = (preferences.fontSizeSp - 1f).coerceAtLeast(12f)))
                    }.padding(12.dp), style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                    Text(stringResource(R.string.insert_font_value, preferences.fontSizeSp.roundToInt()),
                        style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, color = colors.onSurface)
                    Text("+", Modifier.testTag("reader_font_larger").clickable(enabled = preferences.fontSizeSp < 32f) {
                        onPreferencesChange(preferences.copy(fontSizeSp = (preferences.fontSizeSp + 1f).coerceAtMost(32f)))
                    }.padding(12.dp), style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                }
                Text(stringResource(R.string.insert_type_sample), fontFamily = FontFamily.Serif,
                    fontSize = androidx.compose.ui.unit.TextUnit(preferences.fontSizeSp, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = colors.onSurface, modifier = Modifier.padding(bottom = 12.dp))
                InkRule()
                Text(stringResource(R.string.insert_appearance), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                ReaderTheme.entries.forEach { theme ->
                    val label = stringResource(when (theme) { ReaderTheme.LIGHT -> R.string.insert_theme_light; ReaderTheme.DARK -> R.string.insert_theme_dark; ReaderTheme.SYSTEM -> R.string.insert_theme_system })
                    Row(Modifier.fillMaxWidth().testTag("reader_theme_${theme.name.lowercase()}").clickable {
                        onPreferencesChange(preferences.copy(theme = theme))
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Canvas(Modifier.size(18.dp)) {
                            drawCircle(colors.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                            if (preferences.theme == theme) drawCircle(colors.primary, radius = 5.dp.toPx())
                        }
                        Text(label, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    }
                }
            }
            PaperInsertSection.BOOKMARKS -> {
                if (canBookmarkCurrent) {
                    PaperAction(stringResource(if (isBookmarked) R.string.insert_remove_current else R.string.insert_add_bookmark),
                        "reader_add_bookmark", if (isBookmarked) onRemoveCurrentBookmark else onAddBookmark, marker = if (isBookmarked) "−" else "+")
                } else {
                    Text(stringResource(R.string.insert_bookmark_return_to_text), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
                if (bookmarks.isEmpty()) Text(stringResource(R.string.insert_no_bookmarks),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                bookmarks.forEach { bookmark ->
                    BookmarkStrip(bookmark, { onBookmarkSelected(bookmark) }, { onDeleteBookmark(bookmark) })
                }
            }
            PaperInsertSection.PROGRESS -> {
                Text(stringResource(R.string.insert_preview_position), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                BookmarkRuler(draft.fraction) { selectPreview(navigation.previewFraction(it)) }
                Text(draft.sectionTitle ?: stringResource(R.string.insert_main_text), fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
                Text(when {
                    draft.pageNumber == null -> stringResource(R.string.insert_page_unknown)
                    draft.totalPages == null -> stringResource(R.string.insert_page_available, draft.pageNumber!!)
                    else -> stringResource(R.string.insert_page_of, draft.pageNumber!!, draft.totalPages!!)
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    InkField(stringResource(R.string.insert_percent), percentText, "reader_progress_percent", Modifier.weight(1f)) { value ->
                        percentText = value
                        value.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }?.let {
                            draft = navigation.previewFraction(it / 100f)
                            pageText = draft.pageNumber?.toString().orEmpty()
                        }
                    }
                    InkField(stringResource(R.string.insert_page), pageText, "reader_progress_page", Modifier.weight(1f)) { value ->
                        pageText = value
                        value.toIntOrNull()?.let(navigation::previewPage)?.let {
                            draft = it
                            percentText = ((it.fraction * 1000).roundToInt() / 10f).toString()
                        }
                    }
                }
                if (!navigation.complete) Text(stringResource(R.string.insert_pagination_pending, navigation.availablePages),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (!percentValid || !pageValid) Text(stringResource(R.string.insert_invalid_position),
                    style = MaterialTheme.typography.bodySmall, color = colors.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PaperAction(stringResource(R.string.insert_start), "reader_progress_start", { selectPreview(navigation.previewFraction(0f)) })
                    PaperAction(stringResource(R.string.insert_end), "reader_progress_end", { selectPreview(navigation.previewFraction(1f)) })
                    PaperAction(stringResource(R.string.insert_toc_short), "reader_progress_toc", onGoToToc)
                }
                InkRule()
                PaperAction(stringResource(R.string.insert_confirm), "reader_progress_confirm", { onConfirmNavigation(draft.location) }, marker = "→", enabled = percentValid && pageValid)
                PaperAction(stringResource(R.string.insert_cancel), "reader_progress_cancel", onDismiss)
            }
        }
        if (section != PaperInsertSection.MENU && section != PaperInsertSection.PROGRESS) {
            InkRule()
            PaperAction(stringResource(R.string.insert_back), "reader_insert_back", { section = PaperInsertSection.MENU }, marker = "‹")
            PaperAction(stringResource(R.string.insert_read), "bookmarks_close", onDismiss)
        }
    }
}

@Composable
private fun PaperAction(text: String, tag: String, onClick: () -> Unit, marker: String? = null, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .4f)
    Row(Modifier.testTag(tag).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        marker?.let { Text(it, Modifier.width(24.dp), color = color.copy(alpha = .6f), style = MaterialTheme.typography.labelMedium) }
        Text(text, color = color, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InkRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)))
}

@Composable
private fun BookmarkStrip(bookmark: Bookmark, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).shadow(2.dp, BookmarkStripShape)
        .clip(BookmarkStripShape).background(colors.primaryContainer), verticalAlignment = Alignment.CenterVertically) {
        Text(bookmark.snippet.ifBlank { stringResource(R.string.insert_saved_place) },
            Modifier.weight(1f).testTag("bookmark_jump_${bookmark.id}").clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 15.dp, bottom = 15.dp, end = 8.dp),
            color = colors.onPrimaryContainer, fontFamily = FontFamily.Serif,
            style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("×", Modifier.testTag("bookmark_delete_${bookmark.id}").clickable(onClick = onDelete).padding(start = 8.dp, end = 22.dp, top = 14.dp, bottom = 14.dp),
            color = colors.primary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun InkField(label: String, value: String, tag: String, modifier: Modifier, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface, fontFamily = FontFamily.Serif),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(colors.primary))
        InkRule()
    }
}

@Composable
private fun BookmarkRuler(fraction: Float, onChange: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val currentChange by rememberUpdatedState(onChange)
    Canvas(Modifier.fillMaxWidth().height(82.dp).testTag("reader_progress_ruler")
        .pointerInput(Unit) { detectTapGestures { currentChange(((it.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { currentChange(((it.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f)) }) { change, _ ->
            change.consume(); currentChange(((change.position.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f))
        } }) {
        val inset = 12.dp.toPx()
        val top = 22.dp.toPx()
        drawRect(colors.primaryContainer, topLeft = Offset(0f, top), size = Size(size.width, 36.dp.toPx()))
        for (tick in 0..50) {
            val x = inset + (size.width - inset * 2) * tick / 50f
            drawLine(colors.primary.copy(alpha = .55f), Offset(x, top + 4.dp.toPx()),
                Offset(x, top + (if (tick % 5 == 0) 19.dp else 11.dp).toPx()), 1.dp.toPx())
        }
        val x = inset + (size.width - inset * 2) * fraction
        val half = 8.dp.toPx()
        drawPath(Path().apply { moveTo(x - half, 10.dp.toPx()); lineTo(x + half, 10.dp.toPx()); lineTo(x + half, 67.dp.toPx());
            lineTo(x, 60.dp.toPx()); lineTo(x - half, 67.dp.toPx()); close() }, colors.primary)
        drawLine(colors.onPrimary.copy(alpha = .8f), Offset(x, 26.dp.toPx()), Offset(x, 43.dp.toPx()), 1.dp.toPx())
    }
}

private object InsertPaperShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(Path().apply {
        val fold = with(density) { 20.dp.toPx() }
        moveTo(0f, 0f); lineTo(size.width - fold, 0f); lineTo(size.width, fold)
        lineTo(size.width, size.height); lineTo(0f, size.height); close()
    })
}

private object BookmarkStripShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(Path().apply {
        val notch = with(density) { 10.dp.toPx() }
        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width - notch, size.height / 2)
        lineTo(size.width, size.height); lineTo(0f, size.height); close()
    })
}
