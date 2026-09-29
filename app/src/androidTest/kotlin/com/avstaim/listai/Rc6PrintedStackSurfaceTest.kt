package com.avstaim.listai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.library.LibraryCover
import com.avstaim.listai.feature.library.LibraryScreen
import com.avstaim.listai.feature.library.brightPrintedStackFixture
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Canvas homographies must show the same bound print on real Android renderers. */
@RunWith(AndroidJUnit4::class)
class Rc6PrintedStackSurfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var dark by mutableStateOf(false)
    private var zoom by mutableStateOf(ShelfZoom.MEDIUM)
    private var bright by mutableStateOf(false)

    @After fun cleanup() { compose.runOnUiThread { compose.activity.setContent {} } }

    @Test fun printedStackLidIsVisibleAtEveryZoomAndOpensItsBooks() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val illustrated = BitmapFactory.decodeResource(compose.activity.resources,
            com.avstaim.listai.feature.library.R.drawable.rc6_stack_cover).asImageBitmap()
        val lightPrint = brightPrintedStackFixture()
        val books = List(6) { index -> LibraryBook(BookId("rc6-print-$index"), "Седьмой огонь",
            "Нина Ладожина", "$index.fb2", null, 1, null, 1000, ReadingProgress(), stackId = "print") }
        val catalog = LibraryCatalog(books, listOf(LibraryStack("print", "Печатные обложки", books.map { it.id })),
            listOf(LibraryShelfItem.Stack("print")))
        compose.runOnUiThread { compose.activity.setContent {
            ListaiTheme(darkTheme = dark) {
                LibraryScreen(books, false, {}, {}, {}, {}, catalog = catalog, shelfZoom = zoom,
                    coverContent = { LibraryCover(it, if (bright) lightPrint else illustrated) })
            }
        } }
        val directory = File(compose.activity.filesDir, "rc6-printed-stacks").apply { mkdirs() }
        var illustratedLuminance = 0.0
        for (night in listOf(false, true)) for (scale in ShelfZoom.entries) for (lightCover in listOf(false, true)) {
            compose.runOnIdle { dark = night; zoom = scale; bright = lightCover }
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("library_stack_print").assertIsDisplayed().captureToImage().asAndroidBitmap()
            // A native projective draw silently omitted by a renderer would leave only the
            // flat cabinet/board. Both fixtures contain rich, visible print in the upper half.
            val luminances = mutableListOf<Double>()
            for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 3)
                for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    luminances += ((pixel shr 16 and 255) + (pixel shr 8 and 255) + (pixel and 255)) / 3.0
                }
            if (lightCover) assertTrue("Native canvas must show the replacement print for $scale, dark=$night",
                luminances.average() > illustratedLuminance + 25.0)
            else illustratedLuminance = luminances.average()
            val name = "${scale.name.lowercase()}-${if (night) "dark" else "light"}-${if (lightCover) "bright" else "illustrated"}"
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("library_stack_print").performClick()
        compose.onNodeWithTag("library_book_rc6-print-0").assertIsDisplayed()
        compose.onNodeWithTag("library_back").performClick()
        compose.onNodeWithTag("library_stack_print").assertIsDisplayed()
    }
}
