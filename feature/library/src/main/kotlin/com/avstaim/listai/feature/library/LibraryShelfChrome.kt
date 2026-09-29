package com.avstaim.listai.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.LibraryStack

/** Each action owns its touch surface; the space around the buttons remains live shelf. */
@Composable
internal fun LibraryShelfFab(label: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.size(52.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp), color = colors.surface,
        contentColor = colors.primary.copy(alpha = if (enabled) 1f else .38f),
        shadowElevation = 6.dp, border = BorderStroke(.7.dp, colors.outlineVariant.copy(alpha = .65f))) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** Paper controls stay outside the shelf gesture host, including their full touch targets. */
@Composable
internal fun LibrarySearchStrip(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit,
    onDone: () -> Unit, focusRequester: FocusRequester, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.surface, shape = RoundedCornerShape(3.dp), shadowElevation = 4.dp,
        border = BorderStroke(.7.dp, colors.outlineVariant.copy(alpha = .65f))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LibrarySheetIcon(LibraryMenuIcon.SEARCH, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(query, onQueryChange, enabled = enabled, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(focusRequester).testTag("library_search_field"),
                decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(stringResource(R.string.library_search_hint), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                } })
            if (query.isNotEmpty()) {
                val clear = stringResource(R.string.library_search_clear)
                IconButton({ onQueryChange("") }, enabled = enabled, modifier = Modifier.testTag("library_search_clear")
                    .semantics { contentDescription = clear }) { LibrarySheetIcon(LibraryMenuIcon.CLOSE, Modifier.size(18.dp)) }
            }
            TextButton(onClose, enabled = enabled, modifier = Modifier.testTag("library_back")) {
                Text(stringResource(R.string.library_close), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun LibraryStackHeader(stack: LibraryStack, onBack: () -> Unit, onMenu: () -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.surface, shape = RoundedCornerShape(3.dp), shadowElevation = 3.dp,
        border = BorderStroke(.7.dp, colors.outlineVariant.copy(alpha = .65f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val backLabel = stringResource(R.string.library_to_shelf)
            IconButton(onBack, enabled = enabled, modifier = Modifier.testTag("library_back").semantics { contentDescription = backLabel }) {
                Canvas(Modifier.size(20.dp)) {
                    val c = colors.onSurfaceVariant
                    drawLine(c, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .85f, size.height * .5f), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(c, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .5f, size.height * .2f), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(c, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .5f, size.height * .8f), 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(stack.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.library_sheet_book_count, stack.bookIds.size, stack.bookIds.size),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp))
            }
            val actions = stringResource(R.string.library_stack_actions)
            IconButton(onMenu, enabled = enabled, modifier = Modifier.testTag("library_stack_menu").semantics { contentDescription = actions }) {
                Canvas(Modifier.size(20.dp)) {
                    repeat(3) { drawCircle(colors.onSurfaceVariant, 1.6.dp.toPx(), Offset(size.width * (.15f + it * .35f), size.height * .5f)) }
                }
            }
        }
    }
}

@Composable
internal fun LibraryShelfConfirmation(title: String, message: String, confirm: String, confirmTag: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    LibraryPaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
            TextButton(onConfirm, Modifier.fillMaxWidth().testTag(confirmTag)) { Text(confirm) }
            TextButton(onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_cancel)) }
        }
    }
}
