package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Menu es", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "es")
@Composable
fun EsReadingMenuScreenshot() { InsertAdaptiveScreenshots() }

@PreviewTest
@Preview(name = "Settings es", widthDp = 320, heightDp = 700, fontScale = 1.3f, locale = "es", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EsReadingSettingsScreenshot() { ShelfSettingsScreenshots() }

@PreviewTest
@Preview(name = "Guide stacks es", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "es", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EsStacksGuideScreenshot() { GuideStacksScreenshots() }
