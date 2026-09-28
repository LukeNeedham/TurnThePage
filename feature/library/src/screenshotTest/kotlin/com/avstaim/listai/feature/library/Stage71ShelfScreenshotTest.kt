package com.avstaim.listai.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.core.model.ShelfZoom

@PreviewTest
@Preview(name = "Cover geometry — portrait wide square and long fallback", widthDp = 412, heightDp = 620)
@Composable
fun Stage71CoverGeometryScreenshot() { CoverGeometryPreview() }

@PreviewTest
@Preview(name = "Cover geometry — large type", widthDp = 412, heightDp = 620, fontScale = 1.8f)
@Composable
fun Stage71CoverLargeTypeScreenshot() { CoverGeometryPreview() }

@PreviewTest
@Preview(name = "Shelf flight — 0 percent", widthDp = 412, heightDp = 800)
@Composable
fun Stage71ShelfFlightStartScreenshot() { ShelfFlightPreview(0f) }

@PreviewTest
@Preview(name = "Shelf flight — 30 percent", widthDp = 412, heightDp = 800)
@Composable
fun Stage71ShelfFlightLiftScreenshot() { ShelfFlightPreview(.3f) }

@PreviewTest
@Preview(name = "Shelf flight — 70 percent", widthDp = 412, heightDp = 800)
@Composable
fun Stage71ShelfFlightExpandScreenshot() { ShelfFlightPreview(.7f) }

@PreviewTest
@Preview(name = "Shelf flight — 100 percent closed book", widthDp = 412, heightDp = 800)
@Composable
fun Stage71ShelfFlightEndScreenshot() { ShelfFlightPreview(1f) }

@Composable
private fun CoverGeometryPreview() {
    val images = remember { listOf(coverFixture(240, 360), coverFixture(420, 210), coverFixture(280, 280), null) }
    ListaiTheme(darkTheme = false) {
        Surface {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                images.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        pair.forEachIndexed { column, image ->
                            val index = row * 2 + column
                            Box(Modifier.size(154.dp, 228.dp)) {
                                LibraryCover(fixtureBook(index), image)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfFlightPreview(progress: Float) {
    val image = remember { coverFixture(240, 360) }
    val book = remember { fixtureBook(0) }
    val density = LocalDensity.current
    val geometry = libraryShelfGeometry(412f, ShelfZoom.MEDIUM)
    val sourceLeft = (412f - geometry.bookWidthDp * 2f - 24f) / 2f
    // Root clearance16 + shelf content padding16 + the book baseline gap22.
    val sourceTop = 54f
    ListaiTheme(darkTheme = false) {
        Surface {
            Box(Modifier.fillMaxSize()) {
                LibraryScreen(listOf(book, fixtureBook(3)), false, {}, {}, {}, {},
                    hiddenBookId = book.id, coverContent = { LibraryCover(it, image.takeIf { _ -> it.id == book.id }) })
                ShelfBookFlight(book,
                    with(density) { Rect(sourceLeft.dp.toPx(), sourceTop.dp.toPx(),
                        (sourceLeft + geometry.bookWidthDp).dp.toPx(), (sourceTop + geometry.bookHeightDp).dp.toPx()) },
                    with(density) { Rect(7.dp.toPx(), 7.dp.toPx(), 405.dp.toPx(), 793.dp.toPx()) },
                    progress, previewImage = image)
            }
        }
    }
}

private fun fixtureBook(index: Int) = LibraryBook(BookId("stage71-$index"),
    if (index == 3) "Путешествие к далёкой обсерватории: записки о тишине, времени и незнакомых созвездиях" else "Original artwork $index",
    "Александр Константинович Северный", "fixture.fb2", null, 1, 1, 1000, ReadingProgress())

/** Corner marks and edge lettering expose cropping/mats; the entire print intentionally fills the board. */
private fun coverFixture(width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(0xFF2B526E.toInt())
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDAEAF4.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
        drawRect(3f, 3f, width - 3f, height - 3f, line)
        drawCircle(width / 2f, height / 2f, minOf(width, height) * .28f, line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F7FC.toInt(); textSize = minOf(width, height) * .075f; textAlign = Paint.Align.CENTER }
        drawText("ORIGINAL COVER", width / 2f, text.textSize * 1.4f, text)
        drawText("COMPLETE IMAGE", width / 2f, height - text.textSize * .6f, text)
    }
    return bitmap.asImageBitmap()
}
