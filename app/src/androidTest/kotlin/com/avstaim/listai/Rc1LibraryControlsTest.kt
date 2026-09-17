package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.feature.library.LibraryScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Rc1LibraryControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsHasAFullTouchTargetAndStaysSeparateFromVersionImportAndImportProgress() {
        val importing = mutableStateOf(false)
        var settingsOpened = 0
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    LibraryScreen(emptyList(), importing.value, {}, {}, {}, {},
                        versionLabel = "1.0.0-rc1 · development build with a deliberately long label",
                        onSettings = { settingsOpened++ })
                }
            }
        }
        val settings = compose.onNodeWithTag("library_settings").assertIsDisplayed()
        val initialBounds = settings.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(initialBounds.width >= 48f * density - 1f)
        assertTrue(initialBounds.height >= 48f * density - 1f)
        assertFalse(initialBounds.overlaps(compose.onNodeWithTag("library_version").fetchSemanticsNode().boundsInRoot))
        assertFalse(initialBounds.overlaps(compose.onNodeWithTag("library_import").fetchSemanticsNode().boundsInRoot))
        settings.performClick()
        assertEquals(1, settingsOpened)

        compose.runOnUiThread { importing.value = true }
        compose.onNodeWithTag("library_import").assertDoesNotExist()
        val importProgress = compose.onNodeWithTag("library_cancel_import").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(initialBounds, settings.fetchSemanticsNode().boundsInRoot)
        assertFalse(initialBounds.overlaps(importProgress))
        settings.performClick()
        assertEquals(2, settingsOpened)
    }
}
