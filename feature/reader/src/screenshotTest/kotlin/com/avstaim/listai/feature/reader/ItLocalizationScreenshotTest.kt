package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Menu it", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "it")
@Composable
fun ItReadingMenuScreenshot() { InsertAdaptiveScreenshots() }

@PreviewTest
@Preview(name = "Settings it", widthDp = 320, heightDp = 700, fontScale = 1.3f, locale = "it", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ItReadingSettingsScreenshot() { ShelfSettingsScreenshots() }

@PreviewTest
@Preview(name = "Guide stacks it", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "it", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ItStacksGuideScreenshot() { GuideStacksScreenshots() }
