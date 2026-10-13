package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.ReaderPreferences
import com.avstaim.listai.core.model.ReaderScreenTimeoutMode
import com.avstaim.listai.core.model.ReaderTheme

@PreviewTest
@Preview(name = "Screen sheet light en", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Screen sheet dark ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Screen sheet narrow large de", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "de")
@Preview(name = "Screen sheet narrow large dark fr", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "fr", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ScreenSheetScreenshots() { ScreenSettingsPreview(sheet = true) }

@PreviewTest
@Preview(name = "Screen insert light ru", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Screen insert dark en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Screen insert narrow large en", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "en")
@Preview(name = "Screen insert narrow large dark ru", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ScreenInsertScreenshots() { ScreenSettingsPreview(sheet = false) }

@Composable
private fun ScreenSettingsPreview(sheet: Boolean) {
    val dark = isSystemInDarkTheme()
    val preferences = ReaderPreferences(
        theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT,
        screenTimeoutMode = if (sheet) ReaderScreenTimeoutMode.SYSTEM else ReaderScreenTimeoutMode.ALWAYS_ON,
    )
    ListaiTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp),
            contentAlignment = Alignment.Center) {
            if (sheet) {
                PaperSettingsSheet(preferences, {}, {}, onShowHints = {})
            } else {
                PaperReaderInsert(
                    preferences = preferences,
                    onPreferencesChange = {},
                    bookmarks = emptyList(),
                    onAddBookmark = {},
                    onDeleteBookmark = {},
                    onBookmarkSelected = {},
                    onReturnToShelf = {},
                    onDismiss = {},
                    onGoToToc = {},
                    onReturnFromToc = null,
                    navigation = null,
                    onConfirmNavigation = {},
                    initialSection = PaperInsertSection.SETTINGS,
                    onShowHints = {},
                )
            }
        }
    }
}
