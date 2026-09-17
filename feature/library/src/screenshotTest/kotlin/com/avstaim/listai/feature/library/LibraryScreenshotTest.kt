package com.avstaim.listai.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*

@PreviewTest
@Preview(name = "Bookshelf — light English", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun LibraryEnglishScreenshot() { LibraryPreview() }

@PreviewTest
@Preview(name = "Шкаф — светлый русский", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun LibraryRussianScreenshot() { LibraryPreview(russian = true) }

@PreviewTest
@Preview(name = "Bookshelf — dark English", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun LibraryDarkEnglishScreenshot() { LibraryPreview(dark = true) }

@PreviewTest
@Preview(name = "Шкаф — тёмный русский", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun LibraryDarkRussianScreenshot() { LibraryPreview(russian = true, dark = true) }

@PreviewTest
@Preview(name = "Bookshelf — system night tablet", widthDp = 840, heightDp = 700, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LibrarySystemScreenshot() { LibraryPreview(dark = isSystemInDarkTheme()) }

@PreviewTest
@Preview(name = "Library — empty", widthDp = 360, heightDp = 640, locale = "en")
@Composable
fun LibraryEmptyScreenshot() {
    ListaiTheme(darkTheme = false) {
        Surface { LibraryScreen(emptyList(), false, {}, {}, {}, {}) }
    }
}

@Composable
private fun LibraryPreview(russian: Boolean = false, dark: Boolean = false) {
    val cover = remember {
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFF244B6A.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC3DDEE.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE }
            drawRect(15f, 15f, 225f, 345f, paint)
            drawCircle(120f, 164f, 52f, paint)
            drawLine(68f, 164f, 172f, 164f, paint)
            drawLine(120f, 112f, 120f, 216f, paint)
        }
        bitmap.asImageBitmap()
    }
    val books = listOf(
        LibraryBook(BookId("atlas"), if (russian) "Атлас тихих страниц" else "An Atlas of Quiet Pages",
            if (russian) "Мастерская открытой книги" else "The Open Book Workshop", "atlas.book", null,
            1, 3, 1000, ReadingProgress(fraction = .42f)),
        LibraryBook(BookId("journey"), if (russian) "Путешествие к далёкой обсерватории" else "Journey to the Distant Observatory",
            if (russian) "Александр Волков" else "Alexander Volkov", "journey.book", null,
            1, 2, 1000, ReadingProgress(fraction = .08f)),
        LibraryBook(BookId("new"), if (russian) "Новая книга" else "A New Book", "", "new.book", null,
            1, null, 1000, ReadingProgress()),
        LibraryBook(BookId("sea"), if (russian) "Небо над морем" else "The Sky over the Sea",
            if (russian) "Мария Север" else "Maria North", "sea.book", null, 0, null, 1000, ReadingProgress()),
    )
    ListaiTheme(darkTheme = dark) {
        Surface {
            LibraryScreen(books, false, {}, {}, {}, {}, versionLabel = "v1.0.0-rc1", coverContent = { book ->
                LibraryCover(book, previewImage = cover.takeIf { book.id.value == "atlas" })
            })
        }
    }
}
