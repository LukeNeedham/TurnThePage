package com.avstaim.listai.feature.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.BookImage
import com.avstaim.listai.feature.reader.bookview.singlePageViewport
import com.avstaim.listai.feature.reader.bookview.toBookDisplayFeatureInWindow
import com.avstaim.listai.feature.reader.bookview.toLocalViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val ReaderImageZoom = SemanticsPropertyKey<Float>("ReaderImageZoom")
var SemanticsPropertyReceiver.readerImageZoom by ReaderImageZoom
val ReaderImageId = SemanticsPropertyKey<String>("ReaderImageId")
var SemanticsPropertyReceiver.readerImageId by ReaderImageId
val ReaderImageTransition = SemanticsPropertyKey<Float>("ReaderImageTransition")
var SemanticsPropertyReceiver.readerImageTransition by ReaderImageTransition
val ReaderImageHeld = SemanticsPropertyKey<Boolean>("ReaderImageHeld")
var SemanticsPropertyReceiver.readerImageHeld by ReaderImageHeld
val ReaderImageOffset = SemanticsPropertyKey<Offset>("ReaderImageOffset")
var SemanticsPropertyReceiver.readerImageOffset by ReaderImageOffset

private sealed interface BookImageLoad {
    data object Loading : BookImageLoad
    data class Ready(val bitmap: ImageBitmap?) : BookImageLoad
}

/** Retains the prepared crop through entry and exit while the original decodes independently. */
@Composable
internal fun BookImageViewer(
    image: BookImage,
    alternativeText: String?,
    onDismissCompleted: () -> Unit,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    sourceBounds: Rect? = null,
    preview: BookImagePreview? = null,
    visible: Boolean = true,
    transientPreview: Boolean = false,
    onDismissRequested: (() -> Unit)? = null,
) {
    key(image) {
        val loaded by produceState<BookImageLoad>(BookImageLoad.Loading, image) {
            value = BookImageLoad.Ready(withContext(Dispatchers.IO) { decodeBookViewerImage(image) })
        }
        val progress = remember { Animatable(0f) }
        var closeRequested by remember { mutableStateOf(false) }
        val desiredVisible = visible && !closeRequested
        val completed by rememberUpdatedState(onDismissCompleted)
        val requested by rememberUpdatedState(onDismissRequested)
        LaunchedEffect(desiredVisible) {
            progress.animateTo(if (desiredVisible) 1f else 0f, tween(260, easing = FastOutSlowInEasing))
            if (!desiredVisible) completed()
        }
        BookImageViewerContent(
            bitmap = (loaded as? BookImageLoad.Ready)?.bitmap,
            alternativeText = alternativeText,
            onDismiss = {
                if (!closeRequested) {
                    closeRequested = true
                    requested?.invoke()
                }
            },
            isLoading = loaded is BookImageLoad.Loading,
            imageId = image.id.value,
            devicePosture = devicePosture,
            sourceBounds = sourceBounds,
            preview = preview,
            transitionProgress = progress.value,
            transientPreview = transientPreview,
            externalHeld = transientPreview && desiredVisible,
        )
    }
}

/** Deterministic motion frames and crop rendering can be inspected without worker or window callbacks. */
@Composable
internal fun BookImageViewerContent(
    bitmap: ImageBitmap?,
    alternativeText: String?,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    imageId: String? = null,
    initialTransform: BookImageZoom = BookImageZoom(),
    devicePosture: DevicePosture = DevicePosture.Unknown,
    sourceBounds: Rect? = null,
    preview: BookImagePreview? = null,
    transitionProgress: Float = 1f,
    transientPreview: Boolean = false,
    initialHeld: Boolean = false,
    externalHeld: Boolean = transientPreview,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val imageLabel = alternativeText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.reader_image_description)
    val paneLabel = stringResource(R.string.reader_image_viewer_title)
    val closeLabel = stringResource(R.string.reader_image_close)
    val zoomInLabel = stringResource(R.string.reader_image_zoom_in)
    val zoomOutLabel = stringResource(R.string.reader_image_zoom_out)
    val resetLabel = stringResource(R.string.reader_image_zoom_reset)
    val inspection = LocalInspectionMode.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing
    var viewportBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val displayFeatureInWindow = remember(devicePosture) { devicePosture.toBookDisplayFeatureInWindow() }
    var transform by remember(initialTransform) { mutableStateOf(initialTransform) }
    var isHeld by remember(initialHeld) { mutableStateOf(initialHeld) }
    var holdBase by remember(initialHeld, initialTransform) {
        mutableStateOf(if (initialHeld) initialTransform else null)
    }
    var holdPoint by remember { mutableStateOf<Offset?>(null) }
    val holdProgress = remember(initialHeld) { Animatable(if (initialHeld) 1f else 0f) }
    val gestureScope = rememberCoroutineScope()
    var holdAnimation by remember { mutableStateOf<Job?>(null) }
    val progress = transitionProgress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val originalAlpha by animateFloatAsState(if (bitmap != null) 1f else 0f,
        tween(180), label = "illustration-original")
    val ink = Color(0xFFE0E8EF)
    val blocksInput = Modifier.pointerInput(Unit) { detectTapGestures { } }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()
        .background(Color(0xFF141B23).copy(alpha = progress))
        .onGloballyPositioned { viewportBoundsInWindow = it.boundsInWindow() }
        .testTag("reader_image_viewer")
        .semantics {
            paneTitle = paneLabel
            isTraversalGroup = true
            readerImageTransition = progress
            readerImageHeld = externalHeld || isHeld
            imageId?.let { readerImageId = it }
        }.then(blocksInput)) {
        val fullSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val left = safeInsets.getLeft(density, layoutDirection).toFloat().coerceIn(0f, fullSize.width)
        val top = safeInsets.getTop(density).toFloat().coerceIn(0f, fullSize.height)
        val safe = Rect(left, top,
            (fullSize.width - safeInsets.getRight(density, layoutDirection)).coerceAtLeast(left),
            (fullSize.height - safeInsets.getBottom(density)).coerceAtLeast(top))
        val localFeature = if (inspection) {
            // Tooling starts at (0, 0) and does not deliver live window coordinates.
            displayFeatureInWindow
        } else viewportBoundsInWindow?.let { displayFeatureInWindow?.toLocalViewport(it) }
        val safeFeature = localFeature?.toLocalViewport(safe)
        val segment = singlePageViewport(safe.size, safeFeature, 0f).translate(safe.topLeft)
        val geometry = remember(segment.width, segment.height, preview?.source, bitmap?.width, bitmap?.height) {
            bookImageViewport(segment.width, segment.height, preview?.source?.width ?: bitmap?.width ?: 0,
                preview?.source?.height ?: bitmap?.height ?: 0)
        }
        val base = holdBase ?: transform
        val heldTarget = geometry.transform(base, holdPoint?.x ?: geometry.width / 2f,
            holdPoint?.y ?: geometry.height / 2f, zoomFactor = 1.8f)
        val displayedTransform = geometry.constrain(interpolateBookImageZoom(base, heldTarget, holdProgress.value))
        LaunchedEffect(geometry) {
            transform = geometry.constrain(transform)
            holdBase = holdBase?.let(geometry::constrain)
        }
        val fittedSize = IntSize(geometry.fittedWidth.roundToInt().coerceAtLeast(1),
            geometry.fittedHeight.roundToInt().coerceAtLeast(1))
        val fittedOffset = IntOffset(((segment.width - fittedSize.width) / 2f).roundToInt(),
            ((segment.height - fittedSize.height) / 2f).roundToInt())
        val fitted = Rect(segment.left + fittedOffset.x, segment.top + fittedOffset.y,
            segment.left + fittedOffset.x + fittedSize.width, segment.top + fittedOffset.y + fittedSize.height)
        val readyForPane = inspection || displayFeatureInWindow?.avoidsContent != true || viewportBoundsInWindow != null
        val drawingProgress = if (readyForPane) progress else 0f
        val target = bookImageTransitionBounds(sourceBounds,
            bookImageZoomBounds(fitted, displayedTransform, segment.center), drawingProgress)
        val transitionClip = bookImageTransitionBounds(sourceBounds, segment, drawingProgress)
        val hasImage = bitmap != null || preview != null
        val gestureEnabled = hasImage && !transientPreview && progress == 1f
        val gestures = if (!gestureEnabled) Modifier else Modifier
            // Bitmap readiness does not restart an already held gesture on the prepared crop.
            .pointerInput(geometry, segment.topLeft) {
                detectTapGestures(
                    onPress = {
                        try {
                            tryAwaitRelease()
                        } finally {
                            if (isHeld) {
                                isHeld = false
                                holdAnimation?.cancel()
                                holdAnimation = gestureScope.launch {
                                    holdProgress.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                                    holdBase = null
                                    holdPoint = null
                                }
                            }
                        }
                    },
                    onLongPress = { point ->
                        if (segment.contains(point) && bookImageZoomBounds(fitted,
                                geometry.constrain(transform), segment.center).contains(point)) {
                            holdAnimation?.cancel()
                            holdBase = geometry.constrain(transform)
                            holdPoint = point - segment.topLeft
                            isHeld = true
                            holdAnimation = gestureScope.launch {
                                holdProgress.snapTo(0f)
                                holdProgress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                            }
                        }
                    },
                    onDoubleTap = { point ->
                        transform = geometry.doubleTap(transform, point.x - segment.left, point.y - segment.top)
                    },
                )
            }
            .pointerInput(geometry, segment.topLeft) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (!isHeld && holdBase == null) transform = geometry.transform(transform,
                        centroid.x - segment.left, centroid.y - segment.top, pan.x, pan.y, zoom)
                }
            }
        Canvas(Modifier.fillMaxSize().testTag("reader_image_content")
            .semantics {
                contentDescription = imageLabel
                readerImageZoom = displayedTransform.scale
                readerImageOffset = Offset(displayedTransform.x, displayedTransform.y)
                readerImageHeld = externalHeld || isHeld
                imageId?.let { readerImageId = it }
                if (gestureEnabled && !isHeld && holdBase == null) customActions = listOf(
                    CustomAccessibilityAction(zoomInLabel) {
                        transform = geometry.transform(transform, geometry.width / 2f, geometry.height / 2f, zoomFactor = 1.5f)
                        true
                    },
                    CustomAccessibilityAction(zoomOutLabel) {
                        transform = geometry.transform(transform, geometry.width / 2f, geometry.height / 2f, zoomFactor = 1f / 1.5f)
                        true
                    },
                    CustomAccessibilityAction(resetLabel) { transform = BookImageZoom(); true },
                )
            }.then(gestures)) {
            val paintImage: DrawScope.() -> Unit = {
                val alpha = if (sourceBounds == null) drawingProgress else 1f
                fun paint(bitmap: ImageBitmap, source: IntRect?, opacity: Float) {
                    if (drawingProgress == 1f) {
                        // Preserve the established fitted-pixel sampling when the morph is settled.
                        translate(segment.left + displayedTransform.x, segment.top + displayedTransform.y) {
                            scale(displayedTransform.scale, pivot = Offset(segment.width / 2f, segment.height / 2f)) {
                                drawImage(bitmap, srcOffset = source?.topLeft ?: IntOffset.Zero,
                                    srcSize = source?.size ?: IntSize(bitmap.width, bitmap.height),
                                    dstOffset = fittedOffset, dstSize = fittedSize,
                                    alpha = opacity, filterQuality = FilterQuality.High)
                            }
                        }
                    } else drawViewerImage(bitmap, source, target, opacity)
                }
                if (originalAlpha < 1f) preview?.let { paint(it.bitmap, it.source, alpha) }
                bitmap?.let { paint(it, null, alpha * originalAlpha) }
            }
            // The viewport also moves back to the printed crop, keeping a zoomed exit from
            // suddenly exposing pixels that were outside the fullscreen pane.
            clipRect(transitionClip.left, transitionClip.top, transitionClip.right, transitionClip.bottom) {
                if (localFeature?.avoidsContent == true) {
                    val hinge = localFeature.bounds
                    clipRect(hinge.left, hinge.top, hinge.right, hinge.bottom, ClipOp.Difference) { paintImage() }
                } else paintImage()
            }
        }
        if (readyForPane) {
            Box(Modifier.absoluteOffset { IntOffset(segment.left.roundToInt(), segment.top.roundToInt()) }
                .size(with(density) { segment.width.toDp() }, with(density) { segment.height.toDp() })
                .graphicsLayer { alpha = progress }) {
                if (isLoading && !hasImage) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = ink)
                } else if (!isLoading && bitmap == null) {
                    Text(stringResource(R.string.reader_image_unavailable),
                        Modifier.align(Alignment.Center).padding(32.dp), color = ink)
                }
                if (!transientPreview) IconButton(onClick = onDismiss, enabled = progress == 1f,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
                        .background(Color(0xE62C3846), CircleShape)
                        .testTag("reader_image_close")
                        .semantics { contentDescription = closeLabel }) {
                    PaperInkIcon(PaperIcon.Close, Modifier.size(24.dp), ink)
                }
            }
        }
    }
}

/** Drawing a source crop directly avoids allocating a second bitmap during either transition. */
private fun DrawScope.drawViewerImage(bitmap: ImageBitmap, source: IntRect?, target: Rect, alpha: Float) {
    if (target.width <= 0f || target.height <= 0f || alpha <= 0f) return
    drawImage(bitmap,
        srcOffset = source?.topLeft ?: IntOffset.Zero,
        srcSize = source?.size ?: IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
        dstSize = IntSize(target.width.roundToInt().coerceAtLeast(1), target.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f), filterQuality = FilterQuality.High)
}

internal fun decodeBookViewerImage(image: BookImage): ImageBitmap? = try {
    val bytes = image.content.load()
    if (bytes == null || bytes.isEmpty()) null else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = bookImageDecodeSample(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            })?.also { it.prepareToDraw() }?.asImageBitmap()
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
