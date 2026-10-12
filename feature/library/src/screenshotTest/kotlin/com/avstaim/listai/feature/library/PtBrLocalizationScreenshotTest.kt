package com.avstaim.listai.feature.library

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Book menu pt-BR", widthDp = 320, heightDp = 680, fontScale = 1.3f, locale = "pt-rBR", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PtBrLibraryBookMenuScreenshot() { LibraryBookMenuPaperScreenshot() }

@PreviewTest
@Preview(name = "Stack creation pt-BR", widthDp = 840, heightDp = 760, fontScale = 1.3f, locale = "pt-rBR")
@Composable
fun PtBrLibraryStackCreationScreenshot() { LibraryStackCreationPaperScreenshot() }
