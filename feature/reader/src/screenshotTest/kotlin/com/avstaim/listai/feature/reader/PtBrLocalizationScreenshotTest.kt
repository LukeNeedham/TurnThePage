package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Menu pt-BR", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "pt-rBR")
@Composable
fun PtBrReadingMenuScreenshot() { InsertAdaptiveScreenshots() }

@PreviewTest
@Preview(name = "Settings pt-BR", widthDp = 320, heightDp = 700, fontScale = 1.3f, locale = "pt-rBR", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PtBrReadingSettingsScreenshot() { ShelfSettingsScreenshots() }

@PreviewTest
@Preview(name = "Guide stacks pt-BR", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "pt-rBR", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PtBrStacksGuideScreenshot() { GuideStacksScreenshots() }
