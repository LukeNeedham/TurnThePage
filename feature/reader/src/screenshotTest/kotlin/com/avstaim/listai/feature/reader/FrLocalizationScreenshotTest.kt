package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Menu fr", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "fr")
@Composable
fun FrReadingMenuScreenshot() { InsertAdaptiveScreenshots() }

@PreviewTest
@Preview(name = "Settings fr", widthDp = 320, heightDp = 700, fontScale = 1.3f, locale = "fr", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FrReadingSettingsScreenshot() { ShelfSettingsScreenshots() }

@PreviewTest
@Preview(name = "Guide stacks fr", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "fr", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FrStacksGuideScreenshot() { GuideStacksScreenshots() }
