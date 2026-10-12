package com.avstaim.listai.feature.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.BookBlock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

val ReaderPdfPageNumber = SemanticsPropertyKey<Int>("ReaderPdfPageNumber")
var SemanticsPropertyReceiver.readerPdfPageNumber by ReaderPdfPageNumber

private sealed interface PdfPageLoad {
    data object Loading : PdfPageLoad
    data class Ready(val bitmap: ImageBitmap?) : PdfPageLoad
}

/** The private PDF is opened once on a worker; gestures only transform the prepared bitmap. */
@Composable
internal fun PdfPageViewer(
    page: BookBlock.FixedPage,
    onDismissCompleted: () -> Unit,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    sourceBounds: Rect? = null,
    preview: BookImagePreview? = null,
    visible: Boolean = true,
    darkTheme: Boolean = false,
    onDismissRequested: (() -> Unit)? = null,
) {
    key(page) {
        val loaded by produceState<PdfPageLoad>(PdfPageLoad.Loading, page) {
            value = PdfPageLoad.Ready(renderPdfViewerPage(page)?.asImageBitmap())
        }
        BookImageViewerFrame(
            bitmap = (loaded as? PdfPageLoad.Ready)?.bitmap,
            alternativeText = null,
            onDismissCompleted = onDismissCompleted,
            isLoading = loaded is PdfPageLoad.Loading,
            imageId = "pdf:${page.id.value}",
            devicePosture = devicePosture,
            sourceBounds = sourceBounds,
            preview = preview,
            visible = visible,
            onDismissRequested = onDismissRequested,
            pdfPageNumber = page.pageIndex + 1,
            darkTheme = darkTheme,
        )
    }
}

/** No descriptor, renderer or native PDF page survives this worker call. */
internal suspend fun renderPdfViewerPage(source: BookBlock.FixedPage): Bitmap? = withContext(Dispatchers.IO) {
    var bitmap: Bitmap? = null
    try {
        require(source.mediaType == "application/pdf")
        val descriptor = ParcelFileDescriptor.open(File(source.sourcePath), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
        renderer.use {
            currentCoroutineContext().ensureActive()
            it.openPage(source.pageIndex).use { page ->
                check(page.width == source.widthPoints && page.height == source.heightPoints) {
                    "The private PDF changed after indexing"
                }
                val size = pdfPageRenderSize(page.width, page.height)
                val target = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                bitmap = target
                // PDF pages retain their authored colors and white background in both themes.
                target.eraseColor(Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
                }
                page.render(target, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                currentCoroutineContext().ensureActive()
                target.prepareToDraw()
            }
        }
        bitmap
    } catch (cancelled: CancellationException) {
        bitmap?.recycle()
        throw cancelled
    } catch (_: Exception) {
        bitmap?.recycle()
        null
    } catch (_: OutOfMemoryError) {
        bitmap?.recycle()
        null
    }
}
