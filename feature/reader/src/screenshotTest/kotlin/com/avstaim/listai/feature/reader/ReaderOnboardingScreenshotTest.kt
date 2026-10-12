package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages

@PreviewTest
@Preview(name = "Reading guide — compact Russian", widthDp = 320, heightDp = 568, locale = "ru")
@Preview(name = "Reading guide — dark English", widthDp = 412, heightDp = 892, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Reading guide — English spread", widthDp = 892, heightDp = 412, locale = "en")
@Preview(name = "Reading guide — dark Russian spread", widthDp = 740, heightDp = 360, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ReaderOnboardingScreenshots() {
    val spread = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    OnboardingPreview(if (spread) ReaderOnboardingStep.DRAG_FORWARD else ReaderOnboardingStep.MENU)
}

@PreviewTest
@Preview(name = "Guide bookmarks — compact Russian large type", widthDp = 320, heightDp = 568, locale = "ru", fontScale = 1.3f)
@Preview(name = "Guide bookmarks — dark English", widthDp = 412, heightDp = 892, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideBookmarksScreenshots() { OnboardingPreview(ReaderOnboardingStep.BOOKMARKS) }

@PreviewTest
@Preview(name = "Guide illustrations — compact Russian", widthDp = 320, heightDp = 568, locale = "ru")
@Preview(name = "Guide illustrations — dark English spread large type", widthDp = 740, heightDp = 360, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideIllustrationsScreenshots() { OnboardingPreview(ReaderOnboardingStep.ILLUSTRATIONS) }

@PreviewTest
@Preview(name = "Guide stacks — compact Russian large type", widthDp = 320, heightDp = 568, locale = "ru", fontScale = 1.3f)
@Preview(name = "Guide stacks — dark English spread", widthDp = 892, heightDp = 412, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideStacksScreenshots() { OnboardingPreview(ReaderOnboardingStep.STACKS) }

@PreviewTest
@Preview(name = "Guide search — dark Russian spread", widthDp = 740, heightDp = 360, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Guide search — compact English", widthDp = 320, heightDp = 568, locale = "en")
@Composable
fun GuideSearchScreenshots() { OnboardingPreview(ReaderOnboardingStep.SEARCH) }

@PreviewTest
@Preview(name = "Guide shelf zoom — compact Russian", widthDp = 320, heightDp = 568, locale = "ru")
@Preview(name = "Guide shelf zoom — dark English spread large type", widthDp = 740, heightDp = 360, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideShelfZoomScreenshots() { OnboardingPreview(ReaderOnboardingStep.SHELF_ZOOM) }

@PreviewTest
@Preview(name = "Guide text zoom — compact Russian large type", widthDp = 320, heightDp = 568, locale = "ru", fontScale = 1.3f)
@Preview(name = "Guide text zoom — dark English spread", widthDp = 740, heightDp = 360, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideTextZoomScreenshots() { OnboardingPreview(ReaderOnboardingStep.TEXT_ZOOM) }

@PreviewTest
@Preview(name = "Guide PDF zoom — compact English", widthDp = 320, heightDp = 568, locale = "en")
@Preview(name = "Guide PDF zoom — dark Russian spread large type", widthDp = 740, heightDp = 360, locale = "ru", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuidePdfZoomScreenshots() { OnboardingPreview(ReaderOnboardingStep.PDF_ZOOM) }

@Composable
private fun OnboardingPreview(step: ReaderOnboardingStep) {
    val dark = isSystemInDarkTheme()
    val spread = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val document = remember { FakeBookDocumentFactory.create() }
    ListaiTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize()) {
            PhysicalBookView(document.toDemoPages(), document.metadata.title, null, Modifier.fillMaxSize(),
                forcedPresentation = if (spread) BookPresentation.SPREAD else BookPresentation.SINGLE,
                initialPageIndex = 4, darkTheme = dark, outerPadding = 4.dp, coverInset = 3.dp)
            val state = remember {
                ReaderOnboardingState(ReaderOnboardingSession(step.ordinal)).apply { readerReady = true }
            }
            ReaderOnboardingOverlay(state, dark, illustrationProgress = .4f)
        }
    }
}
