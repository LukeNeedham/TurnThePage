package com.avstaim.listai.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avstaim.listai.core.designsystem.PlyosFontFamily
import com.avstaim.listai.core.model.*
import kotlin.math.roundToInt

enum class PaperInsertSection { MENU, SETTINGS, BOOKMARKS, PROGRESS }

/** A loose reading card. A ruler preview never escapes until the reader confirms it. */
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
    onShowHints: (() -> Unit)? = null,
) {
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    var navigationDraft by remember { mutableStateOf(PaperNavigationDraft.at(navigation.current)) }
    val draft = navigationDraft.preview
    val percentText = navigationDraft.percentText
    val pageText = navigationDraft.pageText
    fun selectPreview(preview: ReaderNavigationPreview) {
        navigationDraft = PaperNavigationDraft.at(preview)
    }
    LaunchedEffect(navigation) {
        navigationDraft = navigationDraft.refreshed(navigation)
    }
    // Resolve again against this snapshot: a newly available exact page must never confirm an old draft.
    val confirmationLocation = navigationDraft.confirmationLocation(navigation)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.widthIn(max = 640.dp), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 560.dp && section == PaperInsertSection.MENU
        val compact = maxHeight < 620.dp
        val inset = if (maxWidth < 340.dp) 20.dp else 26.dp
        Column(Modifier.widthIn(max = if (wide) 620.dp else 420.dp).fillMaxWidth().heightIn(max = 680.dp)
            .shadow(18.dp, InsertPaperShape, ambientColor = Color(0xFF102A41), spotColor = Color(0xFF102A41))
            .clip(InsertPaperShape).background(colors.surface).drawBehind {
                val edge = colors.outlineVariant.copy(alpha = .48f)
                drawLine(edge, Offset(7.dp.toPx(), 0f), Offset(7.dp.toPx(), size.height), 1.dp.toPx())
                drawLine(colors.onSurface.copy(alpha = .035f), Offset(10.dp.toPx(), 0f), Offset(10.dp.toPx(), size.height), 2.dp.toPx())
                val fold = 24.dp.toPx()
                drawPath(Path().apply {
                    moveTo(size.width - fold, 0f); lineTo(size.width - fold, fold); lineTo(size.width, fold); close()
                }, colors.surfaceContainerHighest)
                drawLine(edge, Offset(size.width - fold, fold), Offset(size.width, fold), 1.dp.toPx())
            }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("reader_paper_insert")
            .verticalScroll(rememberScrollState()).padding(start = inset, end = inset,
                top = if (compact) 14.dp else 18.dp, bottom = if (compact) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
            if (section != PaperInsertSection.MENU || !compact) {
                InsertHeading(section, onDismiss) { section = PaperInsertSection.MENU }
            }
            when (section) {
                PaperInsertSection.MENU -> {
                    val place: @Composable () -> Unit = {
                        ReadingPlace(navigation.current, isBookmarked, compact = compact)
                        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                        RulerInvitation(navigation.current.fraction, compact) {
                            selectPreview(navigation.current)
                            section = PaperInsertSection.PROGRESS
                        }
                    }
                    val pockets: @Composable () -> Unit = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InsertPocket(stringResource(R.string.insert_bookmarks),
                                stringResource(R.string.insert_saved_count, bookmarks.size), PaperIcon.Bookmarks,
                                "reader_bookmarks", Modifier.weight(1f), ribbon = true, compact = compact) { section = PaperInsertSection.BOOKMARKS }
                            InsertPocket(stringResource(R.string.insert_type_title),
                                stringResource(R.string.insert_type_detail), PaperIcon.Type,
                                "reader_insert_settings", Modifier.weight(1f), compact = compact) { section = PaperInsertSection.SETTINGS }
                        }
                        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                        ContentsTab(onGoToToc, compact)
                        onReturnFromToc?.let {
                            InkAction(stringResource(R.string.insert_return_from_toc), "reader_insert_return_from_toc", it, PaperIcon.Return)
                        }
                    }
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            Column(Modifier.weight(1.05f)) { place() }
                            Column(Modifier.weight(1f)) { pockets() }
                        }
                    } else {
                        Column { place() }
                        Column { pockets() }
                    }
                    InkRule()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        InkAction(stringResource(R.string.insert_read), "reader_insert_dismiss", onDismiss, PaperIcon.Forward,
                            modifier = Modifier.weight(1f), strong = true)
                        IconPress(PaperIcon.Shelf, stringResource(R.string.insert_shelf), "reader_return_to_shelf", onReturnToShelf)
                    }
                }
                PaperInsertSection.SETTINGS -> {
                    PaperSettingsContent(preferences, onPreferencesChange, onShowHints)
                    SectionFooter(onDismiss)
                }
                PaperInsertSection.BOOKMARKS -> {
                    if (canBookmarkCurrent) {
                        InkAction(stringResource(if (isBookmarked) R.string.insert_remove_current else R.string.insert_add_bookmark),
                            "reader_add_bookmark", if (isBookmarked) onRemoveCurrentBookmark else onAddBookmark,
                            if (isBookmarked) PaperIcon.Minus else PaperIcon.Plus, strong = true)
                    } else {
                        Text(stringResource(R.string.insert_bookmark_return_to_text), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                    }
                    if (bookmarks.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PaperInkIcon(PaperIcon.Bookmarks, Modifier.size(42.dp), colors.outline)
                            Text(stringResource(R.string.insert_no_bookmarks), fontFamily = PlyosFontFamily,
                                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
                        }
                    }
                    bookmarks.forEach { bookmark ->
                        BookmarkStrip(bookmark, { onBookmarkSelected(bookmark) }, { onDeleteBookmark(bookmark) })
                    }
                    SectionFooter(onDismiss)
                }
                PaperInsertSection.PROGRESS -> {
                    Text(stringResource(R.string.insert_preview_position), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    BookmarkRuler(draft.fraction, compact, onChange = { selectPreview(navigation.previewFraction(it)) })
                    ReadingPlace(draft, false, preview = true, compact = compact)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        InkField(stringResource(R.string.insert_percent), percentText, "reader_progress_percent", Modifier.weight(1f)) { value ->
                            navigationDraft = navigationDraft.editPercent(value, navigation)
                        }
                        InkField(stringResource(R.string.insert_page), pageText, "reader_progress_page", Modifier.weight(1f), KeyboardType.Number) { value ->
                            navigationDraft = navigationDraft.editPage(value, navigation)
                        }
                    }
                    if (!navigation.complete) Text(stringResource(R.string.insert_pagination_pending, navigation.availablePages),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    if (confirmationLocation == null) Text(stringResource(R.string.insert_invalid_position),
                        style = MaterialTheme.typography.bodySmall, color = colors.error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        InkAction(stringResource(R.string.insert_start), "reader_progress_start", { selectPreview(navigation.previewFraction(0f)) })
                        InkAction(stringResource(R.string.insert_toc_short), "reader_progress_toc", onGoToToc)
                        InkAction(stringResource(R.string.insert_end), "reader_progress_end", { selectPreview(navigation.previewFraction(1f)) })
                    }
                    InkRule()
                    InkAction(stringResource(R.string.insert_confirm), "reader_progress_confirm", { confirmationLocation?.let(onConfirmNavigation) },
                        PaperIcon.Check, enabled = confirmationLocation != null, strong = true, modifier = Modifier.fillMaxWidth())
                    InkAction(stringResource(R.string.insert_cancel), "reader_progress_cancel", onDismiss, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The shelf and reading card use the same settings and persistence contract. */
@Composable
fun PaperSettingsSheet(
    preferences: ReaderPreferences,
    onPreferencesChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShowHints: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 680.dp)
        .shadow(18.dp, InsertPaperShape).clip(InsertPaperShape).background(colors.surface)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        .testTag("paper_settings_sheet").verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.insert_settings), Modifier.weight(1f),
                fontFamily = PlyosFontFamily, style = MaterialTheme.typography.titleLarge)
            IconPress(PaperIcon.Close, stringResource(R.string.rc1_close_settings), "settings_close", onDismiss)
        }
        InkRule()
        PaperSettingsContent(preferences, onPreferencesChange, onShowHints)
    }
}

@Composable
private fun PaperSettingsContent(preferences: ReaderPreferences,
    onPreferencesChange: (ReaderPreferences) -> Unit, onShowHints: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Text(stringResource(R.string.insert_text_size), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconPress(PaperIcon.Minus, stringResource(R.string.insert_font_smaller), "reader_font_smaller", {
            onPreferencesChange(preferences.copy(fontSizeSp = (preferences.fontSizeSp - 1f).coerceAtLeast(12f)))
        }, enabled = preferences.fontSizeSp > 12f)
        Text(stringResource(R.string.insert_font_value, preferences.fontSizeSp.roundToInt()),
            style = MaterialTheme.typography.headlineMedium, fontFamily = PlyosFontFamily, color = colors.onSurface)
        IconPress(PaperIcon.Plus, stringResource(R.string.insert_font_larger), "reader_font_larger", {
            onPreferencesChange(preferences.copy(fontSizeSp = (preferences.fontSizeSp + 1f).coerceAtMost(32f)))
        }, enabled = preferences.fontSizeSp < 32f)
    }
    Text(stringResource(R.string.insert_type_sample), fontFamily = PlyosFontFamily,
        fontSize = preferences.fontSizeSp.sp, lineHeight = (preferences.fontSizeSp * 1.5f).sp,
        color = colors.onSurface, modifier = Modifier.fillMaxWidth().background(colors.surfaceContainerLow)
            .drawBehind {
                val y = size.height - 8.dp.toPx()
                drawLine(colors.outlineVariant.copy(alpha = .5f), Offset(14.dp.toPx(), y), Offset(size.width - 14.dp.toPx(), y), 1.dp.toPx())
            }.padding(horizontal = 16.dp, vertical = 18.dp))
    InkRule()
    Text(stringResource(R.string.insert_appearance), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderTheme.entries.forEach { theme ->
            ThemePaper(theme, preferences.theme == theme, Modifier.weight(1f)) {
                onPreferencesChange(preferences.copy(theme = theme))
            }
        }
    }
    onShowHints?.let {
        InkRule()
        InkAction(stringResource(R.string.rc1_show_hints), "reader_show_hints", it, PaperIcon.Contents)
    }
}

@Composable
private fun InsertHeading(section: PaperInsertSection, onDismiss: () -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (section != PaperInsertSection.MENU) {
                IconPress(PaperIcon.Back, stringResource(R.string.insert_back), "reader_insert_back", onBack)
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.insert_card_label), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp))
                if (section != PaperInsertSection.MENU) {
                    Text(stringResource(when (section) {
                        PaperInsertSection.SETTINGS -> R.string.insert_settings
                        PaperInsertSection.BOOKMARKS -> R.string.insert_bookmarks
                        PaperInsertSection.PROGRESS -> R.string.insert_progress
                        PaperInsertSection.MENU -> R.string.insert_title
                    }), fontFamily = PlyosFontFamily, color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
                }
            }
            IconPress(PaperIcon.Close, stringResource(R.string.insert_read), "reader_insert_close", onDismiss)
        }
        InkRule()
    }
}

@Composable
private fun ReadingPlace(place: ReaderNavigationPreview, isBookmarked: Boolean, preview: Boolean = false, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!preview && !compact) Text(stringResource(R.string.insert_your_place), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(place.sectionTitle ?: stringResource(R.string.insert_main_text), fontFamily = PlyosFontFamily,
                style = if (compact) MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 25.sp)
                    else MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
            Text(when {
                place.pageNumber == null -> stringResource(R.string.insert_page_unknown)
                place.totalPages == null -> stringResource(R.string.insert_page_available, place.pageNumber)
                else -> stringResource(R.string.insert_page_of, place.pageNumber, place.totalPages)
            }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (!preview) {
            Column(Modifier.width(52.dp).clip(VerticalRibbonShape).background(colors.secondaryContainer)
                .padding(top = 12.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                PaperInkIcon(if (isBookmarked) PaperIcon.Check else PaperIcon.Bookmarks, Modifier.size(19.dp), colors.onSecondaryContainer)
                Text(stringResource(R.string.insert_percent_value, (place.fraction * 100).roundToInt()),
                    color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RulerInvitation(fraction: Float, compact: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("reader_insert_progress").clickable(role = Role.Button, onClick = onOpen)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.insert_progress), fontFamily = PlyosFontFamily, color = colors.primary,
                style = MaterialTheme.typography.titleMedium)
            PaperInkIcon(PaperIcon.Forward, Modifier.size(19.dp))
        }
        BookmarkRuler(fraction, compact)
        if (!compact) Text(stringResource(R.string.insert_ruler_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun InsertPocket(title: String, detail: String, icon: PaperIcon, tag: String, modifier: Modifier,
    ribbon: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = if (ribbon) BookmarkStripShape else InsertPaperShape
    Column(modifier.testTag(tag).shadow(2.dp, shape).clip(shape)
        .background(if (ribbon) colors.secondaryContainer else colors.surfaceContainerLow)
        .clickable(role = Role.Button, onClick = onClick).drawBehind {
            val edge = colors.outlineVariant.copy(alpha = .75f)
            drawLine(edge, Offset(0f, 5.dp.toPx()), Offset(size.width, 5.dp.toPx()), 1.dp.toPx())
            if (!ribbon) drawLine(edge, Offset(0f, size.height - 4.dp.toPx()), Offset(size.width, size.height - 4.dp.toPx()), 1.dp.toPx())
        }.padding(start = 12.dp, end = if (ribbon) 16.dp else 12.dp,
            top = if (compact) 12.dp else 18.dp, bottom = if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 7.dp)) {
        PaperInkIcon(icon, Modifier.size(if (compact) 24.dp else 29.dp))
        Text(title, fontFamily = PlyosFontFamily, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!compact) Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, minLines = 2)
    }
}

@Composable
private fun ContentsTab(onClick: () -> Unit, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reader_insert_toc").clip(ContentsTabShape).background(colors.surfaceContainerHigh)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperInkIcon(PaperIcon.Contents, Modifier.size(if (compact) 22.dp else 25.dp))
        Text(stringResource(R.string.insert_toc), Modifier.weight(1f), color = colors.primary,
            fontFamily = PlyosFontFamily, style = MaterialTheme.typography.titleMedium)
        PaperInkIcon(PaperIcon.Forward, Modifier.size(18.dp))
    }
}

@Composable
private fun ThemePaper(theme: ReaderTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(when (theme) {
        ReaderTheme.LIGHT -> R.string.insert_theme_light
        ReaderTheme.DARK -> R.string.insert_theme_dark
        ReaderTheme.SYSTEM -> R.string.insert_theme_system
    })
    Column(modifier.testTag("reader_theme_${theme.name.lowercase()}").clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }.padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Canvas(Modifier.width(48.dp).height(58.dp)) {
            val dark = Color(0xFF263442)
            val white = Color(0xFFF8FAFC)
            val paper = if (theme == ReaderTheme.DARK) dark else white
            val ink = if (theme == ReaderTheme.DARK) white else dark
            drawRect(colors.outline.copy(alpha = .35f), topLeft = Offset(3.dp.toPx(), 3.dp.toPx()))
            drawRect(paper, size = Size(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()))
            if (theme == ReaderTheme.SYSTEM) drawRect(dark, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2 - 3.dp.toPx(), size.height - 3.dp.toPx()))
            repeat(5) { i ->
                val y = (14 + i * 6).dp.toPx()
                drawLine(ink.copy(alpha = .65f), Offset(8.dp.toPx(), y), Offset(size.width / 2, y), 1.dp.toPx())
                drawLine((if (theme == ReaderTheme.SYSTEM) white else ink).copy(alpha = .65f),
                    Offset(size.width / 2, y), Offset(size.width - 11.dp.toPx(), y), 1.dp.toPx())
            }
            if (selected) drawRect(colors.primary, size = Size(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        Text(label, color = if (selected) colors.primary else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Canvas(Modifier.size(6.dp)) { if (selected) drawCircle(colors.primary) }
    }
}

@Composable
private fun SectionFooter(onDismiss: () -> Unit) {
    InkRule()
    InkAction(stringResource(R.string.insert_read), "bookmarks_close", onDismiss, PaperIcon.Forward, modifier = Modifier.fillMaxWidth(), strong = true)
}

@Composable
private fun InkAction(text: String, tag: String, onClick: () -> Unit, icon: PaperIcon? = null,
    modifier: Modifier = Modifier, enabled: Boolean = true, strong: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val color = colors.primary.copy(alpha = if (enabled) 1f else .4f)
    Row(modifier.testTag(tag).heightIn(min = 48.dp).clip(ContentsTabShape)
        .then(if (strong) Modifier.background(colors.primary.copy(alpha = .075f)) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = if (strong) 12.dp else 0.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { PaperInkIcon(it, Modifier.size(20.dp), color) }
        Text(text, color = color, fontFamily = PlyosFontFamily, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun IconPress(icon: PaperIcon, label: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        PaperInkIcon(icon, Modifier.size(22.dp), color.copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable
private fun InkRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)))
}

@Composable
private fun BookmarkStrip(bookmark: Bookmark, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val darkTheme = colors.background.luminance() < .5f
    val ribbon = bookmarkColor(bookmark, darkTheme)
    val ink = bookmarkInkColor(darkTheme)
    val deleteLabel = stringResource(R.string.insert_delete_bookmark)
    Row(Modifier.fillMaxWidth().shadow(2.dp, BookmarkStripShape).clip(BookmarkStripShape)
        .background(ribbon), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).testTag("bookmark_jump_${bookmark.id}").clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 15.dp, top = 15.dp, bottom = 15.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            PaperInkIcon(PaperIcon.Bookmarks, Modifier.size(18.dp), ink.copy(alpha = .78f))
            Text(bookmark.snippet.ifBlank { stringResource(R.string.insert_saved_place) }, color = ink,
                fontFamily = PlyosFontFamily, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(end = 6.dp)) {
            IconPress(PaperIcon.Close, deleteLabel, "bookmark_delete_${bookmark.id}", onDelete, color = ink)
        }
    }
}

@Composable
private fun InkField(label: String, value: String, tag: String, modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 38.dp).testTag(tag)
            .semantics { contentDescription = label }, singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = colors.onSurface, fontFamily = PlyosFontFamily),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), cursorBrush = SolidColor(colors.primary))
        InkRule()
    }
}

@Composable
private fun BookmarkRuler(fraction: Float, compact: Boolean = false, onChange: ((Float) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val currentChange by rememberUpdatedState(onChange)
    val label = stringResource(R.string.insert_progress)
    val input = if (onChange == null) Modifier else Modifier.testTag("reader_progress_ruler")
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { currentChange?.invoke(it.coerceIn(0f, 1f)); true }
        }.pointerInput(Unit) {
            detectTapGestures { currentChange?.invoke(((it.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f)) }
        }.pointerInput(Unit) {
            detectDragGestures(onDragStart = {
                currentChange?.invoke(((it.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f))
            }) { change, _ ->
                change.consume()
                currentChange?.invoke(((change.position.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f))
            }
        }
    Column(Modifier.fillMaxWidth().then(input)) {
        Canvas(Modifier.fillMaxWidth().height(if (compact) 62.dp else 76.dp)) {
            val inset = 12.dp.toPx()
            val top = (if (compact) 14.dp else 18.dp).toPx()
            val paperHeight = (if (compact) 34.dp else 40.dp).toPx()
            val markerBottom = (if (compact) 59.dp else 69.dp).toPx()
            val width = size.width
            drawRect(colors.onSurface.copy(alpha = .06f), topLeft = Offset(0f, top + 4.dp.toPx()), size = Size(width, paperHeight))
            drawRect(colors.surfaceContainerHighest, topLeft = Offset(0f, top), size = Size(width, paperHeight))
            drawLine(colors.surface.copy(alpha = .7f), Offset(0f, top + 1.dp.toPx()), Offset(width, top + 1.dp.toPx()), 1.dp.toPx())
            drawLine(colors.outline.copy(alpha = .4f), Offset(0f, top + paperHeight - 1.dp.toPx()), Offset(width, top + paperHeight - 1.dp.toPx()), 1.dp.toPx())
            for (tick in 0..50) {
                val x = inset + (width - inset * 2) * tick / 50f
                drawLine(colors.onSurfaceVariant.copy(alpha = if (tick % 5 == 0) .75f else .38f), Offset(x, top + 5.dp.toPx()),
                    Offset(x, top + (if (tick % 5 == 0) 25.dp else 14.dp).toPx()), 1.dp.toPx())
            }
            val x = inset + (width - inset * 2) * fraction
            val half = 7.dp.toPx()
            drawPath(Path().apply {
                moveTo(x - half, 5.dp.toPx()); lineTo(x + half, 5.dp.toPx()); lineTo(x + half, markerBottom)
                lineTo(x, markerBottom - 7.dp.toPx()); lineTo(x - half, markerBottom); close()
            }, colors.primary)
            drawLine(colors.onPrimary.copy(alpha = .8f), Offset(x, 26.dp.toPx()), Offset(x, 43.dp.toPx()), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "50", "100 %").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
    }
}

/** Engraved line drawings shared by the reading card and the book's paper tabs. */
enum class PaperIcon { Bookmarks, Appearance, Contents, Description, Shelf, Settings, Type, Close, Back, Forward, Plus, Minus, Check, Return, Start, End }

@Composable
fun PaperInkIcon(icon: PaperIcon, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        val stroke = 1.35f * scale
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        fun outline(vararg points: Pair<Float, Float>, closed: Boolean = false) {
            val path = Path().apply {
                points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * scale, y * scale) else lineTo(x * scale, y * scale) }
                if (closed) close()
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        when (icon) {
            PaperIcon.Settings -> {
                listOf(6f, 12f, 18f).forEachIndexed { index, y ->
                    val x = if (index == 1) 15f else 8f
                    line(3f, y, x - 2f, y); line(x + 2f, y, 21f, y)
                    drawCircle(color, 2f * scale, point(x, y), style = Stroke(stroke))
                }
            }
            PaperIcon.Bookmarks -> {
                outline(6f to 3f, 16f to 3f, 16f to 21f, 11f to 17f, 6f to 21f, closed = true)
                line(19f, 6f, 19f, 18f)
            }
            PaperIcon.Appearance -> {
                drawCircle(color, radius = 8f * scale, center = point(12f, 12f), style = Stroke(stroke))
                drawArc(color, -90f, 180f, true, topLeft = point(4f, 4f), size = Size(16f * scale, 16f * scale))
                line(12f, 4f, 12f, 20f)
            }
            PaperIcon.Contents -> {
                outline(3f to 4f, 10f to 4f, 12f to 6f, 14f to 4f, 21f to 4f, 21f to 20f, 14f to 20f, 12f to 22f,
                    10f to 20f, 3f to 20f, closed = true)
                line(12f, 6f, 12f, 21f)
                listOf(9f, 12f, 15f).forEach { y -> line(6f, y, 9f, y); line(15f, y, 18f, y) }
            }
            PaperIcon.Description -> {
                outline(5f to 3f, 17f to 3f, 20f to 6f, 20f to 21f, 5f to 21f, 5f to 3f)
                line(17f, 3f, 17f, 7f); line(17f, 7f, 20f, 7f)
                line(8f, 10f, 17f, 10f); line(8f, 14f, 17f, 14f); line(8f, 18f, 14f, 18f)
            }
            PaperIcon.Shelf -> {
                outline(3f to 4f, 7f to 4f, 7f to 19f, 3f to 19f, closed = true)
                outline(9f to 3f, 13f to 3f, 13f to 19f, 9f to 19f, closed = true)
                outline(15f to 6f, 18.5f to 5f, 22f to 18f, 18.5f to 19f, closed = true)
                line(2f, 22f, 22f, 22f); line(4f, 7f, 6f, 7f); line(10f, 15f, 12f, 15f)
            }
            PaperIcon.Type -> {
                outline(3f to 20f, 9f to 4f, 15f to 20f)
                line(5f, 15f, 13f, 15f); line(1f, 20f, 6f, 20f); line(12f, 20f, 17f, 20f)
                outline(17f to 20f, 20f to 11f, 23f to 20f); line(18f, 17f, 22f, 17f)
            }
            PaperIcon.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            PaperIcon.Back -> { line(20f, 12f, 4f, 12f); outline(10f to 6f, 4f to 12f, 10f to 18f) }
            PaperIcon.Forward -> { line(4f, 12f, 20f, 12f); outline(14f to 6f, 20f to 12f, 14f to 18f) }
            PaperIcon.Plus -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            PaperIcon.Minus -> line(5f, 12f, 19f, 12f)
            PaperIcon.Check -> outline(4f to 12f, 9f to 17f, 20f to 6f)
            PaperIcon.Return -> { outline(9f to 5f, 4f to 10f, 9f to 15f); outline(4f to 10f, 19f to 10f, 19f to 19f, 12f to 19f) }
            PaperIcon.Start -> { line(4f, 4f, 4f, 20f); outline(17f to 5f, 9f to 12f, 17f to 19f) }
            PaperIcon.End -> { line(20f, 4f, 20f, 20f); outline(7f to 5f, 15f to 12f, 7f to 19f) }
        }
    }
}

internal enum class PaperNavigationInput { RULER, PERCENT, PAGE }

/** Keeps editing intent while incremental pagination changes which exact pages are available. */
internal data class PaperNavigationDraft(
    val preview: ReaderNavigationPreview,
    val percentText: String,
    val pageText: String,
    val input: PaperNavigationInput = PaperNavigationInput.RULER,
) {
    fun editPercent(text: String, navigation: ReaderNavigationState): PaperNavigationDraft {
        val place = validPercent(text)?.let { navigation.previewFraction(it / 100f) }
        return copy(preview = place ?: preview, percentText = text,
            pageText = place?.pageNumber?.toString() ?: if (place != null) "" else pageText,
            input = PaperNavigationInput.PERCENT)
    }

    fun editPage(text: String, navigation: ReaderNavigationState): PaperNavigationDraft {
        val place = text.toIntOrNull()?.let(navigation::previewPage)
        return copy(preview = place ?: preview, pageText = text,
            percentText = place?.let { positionPercent(it.fraction) } ?: percentText, input = PaperNavigationInput.PAGE)
    }

    fun refreshed(navigation: ReaderNavigationState): PaperNavigationDraft {
        if (input == PaperNavigationInput.PAGE) {
            val exact = pageText.toIntOrNull()?.let(navigation::previewPage)
            if (exact != null) return copy(preview = exact, percentText = positionPercent(exact.fraction))
        }
        val info = navigation.previewFraction(preview.fraction)
        return copy(preview = preview.copy(pageNumber = info.pageNumber, totalPages = info.totalPages),
            pageText = if (input != PaperNavigationInput.PAGE) info.pageNumber?.toString().orEmpty() else pageText)
    }

    fun confirmationLocation(navigation: ReaderNavigationState): BookLocation? {
        // Even before refreshed() runs, an exact-page request resolves only to that page in the new index.
        if (input == PaperNavigationInput.PAGE) return pageText.toIntOrNull()?.let(navigation::previewPage)?.location
        if (validPercent(percentText) == null) return null
        return preview.location
    }

    companion object {
        fun at(preview: ReaderNavigationPreview) = PaperNavigationDraft(preview,
            positionPercent(preview.fraction), preview.pageNumber?.toString().orEmpty())
    }
}

private fun validPercent(text: String): Float? = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
private fun positionPercent(fraction: Float): String = ((fraction * 1000).roundToInt() / 10f).toString()

private object InsertPaperShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(Path().apply {
        val fold = with(density) { 24.dp.toPx() }
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

private object VerticalRibbonShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(Path().apply {
        val notch = with(density) { 9.dp.toPx() }
        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
        lineTo(size.width / 2, size.height - notch); lineTo(0f, size.height); close()
    })
}

private object ContentsTabShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(Path().apply {
        val cut = with(density) { 6.dp.toPx() }
        moveTo(cut, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - cut)
        lineTo(size.width - cut, size.height); lineTo(0f, size.height); lineTo(0f, cut); close()
    })
}
