package com.avstaim.listai.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Four loose paper tabs on the desk, outside the resting hardback. */
@Composable
internal fun ClosedBookControls(onShelf: () -> Unit, onSettings: () -> Unit,
    onContents: () -> Unit, onBookmarks: () -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf(R.string.rc1_closed_shelf, R.string.rc1_closed_settings,
        R.string.rc1_closed_contents, R.string.rc1_closed_bookmarks)
    val icons = listOf(PaperIcon.Shelf, PaperIcon.Settings, PaperIcon.Contents, PaperIcon.Bookmarks)
    val actions = listOf(onShelf, onSettings, onContents, onBookmarks)
    val tags = listOf("closed_book_shelf", "closed_book_settings", "closed_book_contents", "closed_book_bookmarks")
    BoxWithConstraints(modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 18.dp)) {
        val compact = maxWidth < 350.dp
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            labels.forEachIndexed { index, label ->
                val text = stringResource(label)
                val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 7.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
                Column(Modifier.weight(1f).rotate(if (index % 2 == 0) -1.4f else 1.1f)
                    .shadow(3.dp, shape).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), shape)
                    .clickable(role = Role.Button, onClick = actions[index]).testTag(tags[index])
                    .semantics { contentDescription = text }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    PaperInkIcon(icons[index], Modifier.size(23.dp))
                    if (!compact) Text(text, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Serif)
                }
            }
        }
    }
}
