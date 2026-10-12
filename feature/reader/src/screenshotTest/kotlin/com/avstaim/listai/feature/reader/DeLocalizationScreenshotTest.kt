package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Menu de", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "de")
@Composable
fun DeReadingMenuScreenshot() { InsertAdaptiveScreenshots() }

@PreviewTest
@Preview(name = "Settings de", widthDp = 320, heightDp = 700, fontScale = 1.3f, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DeReadingSettingsScreenshot() { ShelfSettingsScreenshots() }

@PreviewTest
@Preview(name = "Guide stacks de", widthDp = 740, heightDp = 360, fontScale = 1.3f, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DeStacksGuideScreenshot() { GuideStacksScreenshots() }
