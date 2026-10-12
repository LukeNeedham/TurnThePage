package com.avstaim.listai.feature.library

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Book menu it", widthDp = 320, heightDp = 680, fontScale = 1.3f, locale = "it", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ItLibraryBookMenuScreenshot() { LibraryBookMenuPaperScreenshot() }

@PreviewTest
@Preview(name = "Stack creation it", widthDp = 840, heightDp = 760, fontScale = 1.3f, locale = "it")
@Composable
fun ItLibraryStackCreationScreenshot() { LibraryStackCreationPaperScreenshot() }
