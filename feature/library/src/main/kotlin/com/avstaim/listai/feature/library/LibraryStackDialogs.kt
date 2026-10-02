package com.avstaim.listai.feature.library

import android.os.Build
import android.view.WindowManager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.*

internal data class LibraryBookPickerRequest(val initialIds: List<BookId>, val destinationStackId: String? = null)

/** Name and membership belong to one sheet; confirmation commits both together. */
@Composable
internal fun LibraryBookPicker(
    catalog: LibraryCatalog,
    request: LibraryBookPickerRequest,
    onDismiss: () -> Unit,
    onConfirm: (List<BookId>, String) -> Unit,
    initialName: String = "",
) {
    var selected by rememberSaveable(request) { mutableStateOf(request.initialIds.map { it.value }) }
    var query by rememberSaveable(request) { mutableStateOf("") }
    var filterKind by rememberSaveable(request) { mutableStateOf<Int?>(null) }
    var filterValue by rememberSaveable(request) { mutableStateOf<String?>(null) }
    var filterExpanded by rememberSaveable(request) { mutableStateOf(false) }
    var editedName by rememberSaveable(request) { mutableStateOf<String?>(null) }
    val available = remember(catalog, request.destinationStackId) {
        searchLibrary(catalog, "").filter { request.destinationStackId == null || it.stackId != request.destinationStackId }
    }
    val choices = remember(available, filterKind) {
        filterKind?.let { kind -> available.flatMap { metadataValues(it, kind) }.distinct().sorted() }.orEmpty()
    }
    val filtered = remember(available, query, filterKind, filterValue) {
        available.filter { librarySearchMatches(it, query) &&
            (filterValue == null || filterValue in metadataValues(it, filterKind ?: 0)) }
    }
    val selectedBooks = available.filter { it.id.value in selected }
    val ids = selectedBooks.map { it.id }
    val defaultName = stringResource(R.string.library_new_stack)
    val name = (editedName ?: suggestedStackName(selectedBooks)
        ?: initialName.takeIf { selectedBooks.size <= 1 && it.isNotBlank() } ?: defaultName).take(120)
    val isCreating = request.destinationStackId == null
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val destination = catalog.stacks.firstOrNull { it.id == request.destinationStackId }
    fun toggle(book: LibraryBook) {
        selected = if (book.id.value in selected) selected - book.id.value else selected + book.id.value
    }
    LibraryPaperSheet(onDismiss, Modifier.testTag("library_book_picker"), expanded = true) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactHeader = keyboardVisible || (maxWidth < 360.dp && LocalDensity.current.fontScale >= 1.3f)
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(stringResource(if (isCreating) R.string.library_sheet_create_title else R.string.library_add_books),
                    style = if (compactHeader) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compactHeader) Text(if (isCreating) stringResource(R.string.library_sheet_create_hint)
                    else stringResource(R.string.library_sheet_add_hint, destination?.name.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                else Spacer(Modifier.height(12.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("library_picker_list"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
            if (isCreating) item(key = "name") {
                OutlinedTextField(name, { editedName = it.take(120) }, singleLine = true,
                    label = { Text(stringResource(R.string.library_stack_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("library_stack_name"))
            }
            item(key = "search") {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_sheet_search_hint), maxLines = 1,
                        overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { LibrarySheetIcon(LibraryMenuIcon.SEARCH, Modifier.size(20.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("library_picker_search"))
            }
            item(key = "filters") {
                val filterLabels = listOf(R.string.library_filter_author, R.string.library_filter_series, R.string.library_filter_genre)
                LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(3) { kind ->
                        FilterChip(selected = filterKind == kind,
                            onClick = {
                                if (filterKind == kind) {
                                    filterExpanded = !filterExpanded
                                } else {
                                    filterKind = kind
                                    filterValue = null
                                    filterExpanded = true
                                }
                            }, label = { Text(stringResource(filterLabels[kind]), style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.testTag("library_picker_filter_$kind"))
                    }
                }
                if (filterExpanded) {
                    if (choices.isEmpty()) Text(stringResource(R.string.library_no_metadata),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                    else LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(choices, key = { it }) { value ->
                            FilterChip(selected = filterValue == value, onClick = {
                                filterValue = if (filterValue == value) null else value
                                filterExpanded = false
                            }, label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 230.dp)) })
                        }
                    }
                }
                filterValue?.let { value ->
                    InputChip(selected = true, onClick = { filterValue = null; filterKind = null; filterExpanded = false },
                        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { LibrarySheetIcon(LibraryMenuIcon.CLOSE, Modifier.size(16.dp)) },
                        modifier = Modifier.widthIn(max = 280.dp).testTag("library_picker_clear_filter"))
                }
            }
            item(key = "selection") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.library_sheet_found_count, filtered.size),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = (selected + filtered.map { it.id.value }).distinct() },
                        enabled = filtered.isNotEmpty(), modifier = Modifier.testTag("library_select_filtered")) {
                        Text(stringResource(R.string.library_sheet_select_all), maxLines = 1)
                    }
                }
            }
            if (filtered.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.library_search_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(filtered, key = { it.id.value }) { book ->
                val checked = book.id.value in selected
                Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { toggle(book) })
                    .testTag("library_select_${book.id.value}").padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LibrarySheetCover(book)
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (book.authors.isNotBlank()) Text(book.authors, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                        val stack = catalog.stacks.firstOrNull { book.id in it.bookIds }
                        val detail = stack?.let { stringResource(R.string.library_in_stack, it.name) } ?: book.series
                        if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    Checkbox(checked, onCheckedChange = null)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (isCreating && ids.size < 2 && !keyboardVisible) Text(stringResource(R.string.library_sheet_minimum),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.library_cancel)) }
                Button(onClick = { onConfirm(ids, name.trim()) },
                    enabled = ids.size >= (if (isCreating) 2 else 1) && (!isCreating || name.trim().isNotEmpty()),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1.5f).heightIn(min = 48.dp).testTag("library_picker_confirm")) {
                    Text(stringResource(if (isCreating) R.string.library_sheet_create_count else R.string.library_sheet_add_count, ids.size))
                }
            }
        }
    }
}

internal fun metadataValues(book: LibraryBook, kind: Int): List<String> = when (kind) {
    0 -> book.authorNames.ifEmpty { listOf(book.authors) }.filter(String::isNotBlank)
    1 -> listOfNotNull(book.series?.takeIf(String::isNotBlank))
    else -> book.genres
}

@Composable
internal fun LibraryStackNameDialog(initialName: String, renaming: Boolean,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit, books: List<LibraryBook> = emptyList()) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName.take(120)) }
    val body: @Composable () -> Unit = {
        Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 14.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (renaming) R.string.library_rename_stack else R.string.library_sheet_create_title),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (!renaming && books.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(164.dp), contentAlignment = Alignment.Center) {
                        books.take(2).forEachIndexed { index, book ->
                            Box(Modifier.size(88.dp, 130.dp).offset(x = if (index == 0) (-35).dp else 35.dp,
                                y = if (index == 0) (-2).dp else 3.dp)
                                .graphicsLayer { rotationZ = if (index == 0) -7f else 6f }
                                .shadow(5.dp, RoundedCornerShape(2.dp))) { LibraryCover(book) }
                        }
                    }
                } else Spacer(Modifier.height(20.dp))
                OutlinedTextField(name, { name = it.take(120) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("library_stack_name"),
                    label = { Text(stringResource(R.string.library_stack_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = { if (name.isNotEmpty()) {
                        val clearLabel = stringResource(R.string.library_stack_name_clear)
                        IconButton({ name = "" }, Modifier.semantics { contentDescription = clearLabel }) {
                            LibrarySheetIcon(LibraryMenuIcon.CLOSE, Modifier.size(16.dp))
                        }
                    } })
                if (!renaming && books.isNotEmpty()) Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.library_sheet_book_count, books.size, books.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start).padding(top = 4.dp))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onDismiss, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) { Text(stringResource(R.string.library_cancel)) }
                    Button(onClick = { onConfirm(name.trim()) }, enabled = name.trim().isNotEmpty(),
                        shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("library_name_confirm")) {
                        Text(stringResource(if (renaming) R.string.library_save else R.string.library_sheet_create))
                    }
                }
            }
        }
    }
    if (LocalInspectionMode.current) {
        Box(Modifier.fillMaxSize().background(Color(0xFF102231).copy(alpha = .4f)).safeDrawingPadding().imePadding().padding(24.dp),
            contentAlignment = Alignment.Center) { body() }
    } else Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = Build.VERSION.SDK_INT < 30)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    // Legacy fullscreen windows ignore ADJUST_RESIZE. Let Android 8–10
                    // resize this temporary dialog so its actions stay above the IME.
                    if (Build.VERSION.SDK_INT >= 30) hide(WindowInsetsCompat.Type.systemBars())
                    else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) { body() }
    }
}
