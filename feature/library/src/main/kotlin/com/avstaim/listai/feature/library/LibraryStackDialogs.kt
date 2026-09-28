package com.avstaim.listai.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.*

internal data class LibraryBookPickerRequest(val initialIds: List<BookId>, val destinationStackId: String? = null)

/** Explicit membership and metadata filters make bulk movement predictable. */
@Composable
internal fun LibraryBookPicker(
    catalog: LibraryCatalog,
    request: LibraryBookPickerRequest,
    onDismiss: () -> Unit,
    onConfirm: (List<BookId>) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(request.initialIds.map { it.value }) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterKind by rememberSaveable { mutableStateOf(0) }
    var filterValue by rememberSaveable { mutableStateOf<String?>(null) }
    var filterMenu by remember { mutableStateOf(false) }
    val available = remember(catalog, request.destinationStackId) {
        searchLibrary(catalog, "").filter { request.destinationStackId == null || it.stackId != request.destinationStackId }
    }
    val choices = remember(available, filterKind) { available.flatMap { metadataValues(it, filterKind) }.distinct().sorted() }
    val filtered = available.filter { librarySearchMatches(it, query) && (filterValue == null || filterValue in metadataValues(it, filterKind)) }
    val ids = selected.map(::BookId).filter { id -> available.any { it.id == id } }
    fun toggle(book: LibraryBook) { selected = if (book.id.value in selected) selected - book.id.value else selected + book.id.value }
    val isCreating = request.destinationStackId == null
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("library_book_picker"),
        title = { Text(stringResource(if (isCreating) R.string.library_create_stack else R.string.library_add_books)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("library_picker_search"),
                    singleLine = true, label = { Text(stringResource(R.string.library_search_hint)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(R.string.library_filter_author, R.string.library_filter_series, R.string.library_filter_genre).forEachIndexed { index, label ->
                        TextButton(onClick = { filterKind = index; filterValue = null; filterMenu = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Box {
                    filterValue?.let { value -> InputChip(selected = true, onClick = { filterValue = null }, label = { Text("$value ×") }) }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        if (choices.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.library_no_metadata)) }, onClick = { filterMenu = false })
                        choices.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { filterValue = value; filterMenu = false }) }
                    }
                }
                TextButton(onClick = { selected = (selected + filtered.map { it.id.value }).distinct() }, enabled = filtered.isNotEmpty(),
                    modifier = Modifier.testTag("library_select_filtered")) { Text(stringResource(R.string.library_select_filtered)) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(filtered, key = { it.id.value }) { book ->
                        Row(Modifier.fillMaxWidth().clickable { toggle(book) }.testTag("library_select_${book.id.value}").padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = book.id.value in selected, onCheckedChange = { toggle(book) })
                            Column(Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.bodyMedium)
                                if (book.authors.isNotBlank()) Text(book.authors, style = MaterialTheme.typography.bodySmall)
                                book.series?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (book.genres.isNotEmpty()) Text(book.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                catalog.stacks.firstOrNull { book.id in it.bookIds }?.let { stack ->
                                    Text(stringResource(R.string.library_in_stack, stack.name), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) Text(stringResource(R.string.library_search_empty), Modifier.padding(vertical = 16.dp))
                Text(stringResource(R.string.library_selected_count, ids.size), style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(ids) }, enabled = ids.size >= if (isCreating) 2 else 1,
            modifier = Modifier.testTag("library_picker_confirm")) { Text(stringResource(if (isCreating) R.string.library_continue else R.string.library_add_books)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } })
}

internal fun metadataValues(book: LibraryBook, kind: Int): List<String> = when (kind) {
    0 -> book.authorNames.ifEmpty { listOf(book.authors) }.filter(String::isNotBlank)
    1 -> listOfNotNull(book.series?.takeIf(String::isNotBlank))
    else -> book.genres
}

@Composable
internal fun LibraryStackNameDialog(initialName: String, renaming: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (renaming) R.string.library_rename_stack else R.string.library_create_stack)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("library_stack_name"),
            label = { Text(stringResource(R.string.library_stack_name)) }, supportingText = { Text("${name.length} / 120") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.trim().isNotEmpty(),
            modifier = Modifier.testTag("library_name_confirm")) { Text(stringResource(R.string.library_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } })
}
