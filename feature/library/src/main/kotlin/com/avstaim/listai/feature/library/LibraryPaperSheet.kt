package com.avstaim.listai.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

/** One paper surface for library actions, with the same safe bounds as the book shelf. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LibraryPaperSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val scrim = Color(0xff132432).copy(alpha = .38f)
    if (LocalInspectionMode.current) {
        // Preview the exact paper body; platform modal windows have a separate render root.
        Box(Modifier.fillMaxSize().background(scrim).systemBarsPadding()) {
            Surface(Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth(),
                shape = shape, color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                LibraryPaperSheetBody(modifier, expanded, content)
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetMaxWidth = 560.dp, shape = shape,
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
            scrimColor = scrim, dragHandle = null,
            contentWindowInsets = { WindowInsets.safeDrawing }) {
            LibraryPaperSheetBody(modifier, expanded, content)
        }
    }
}

@Composable
private fun LibraryPaperSheetBody(modifier: Modifier, expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().imePadding()) {
        val availableHeight = (maxHeight - 12.dp).coerceAtLeast(1.dp)
        Column(Modifier.fillMaxWidth()
            .then(if (expanded) Modifier.height(availableHeight) else Modifier.heightIn(max = availableHeight))
            .then(modifier)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 14.dp)
                .size(32.dp, 3.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
            content()
        }
    }
}
