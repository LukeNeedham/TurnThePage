package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBookLayoutTest {
    @Test
    fun `generic spread accepts exact width and leaf boundaries`() {
        assertEquals(
            BookPresentation.SPREAD,
            resolve(canvasSize = Size(GENERIC_SPREAD_WIDTH, 240f)),
        )
        assertEquals(
            BookPresentation.SINGLE,
            resolve(canvasSize = Size(GENERIC_SPREAD_WIDTH - 0.01f, 600f)),
        )
    }

    @Test
    fun `spread still requires a useful reflowable leaf height`() {
        assertEquals(
            BookPresentation.SPREAD,
            resolve(canvasSize = Size(GENERIC_SPREAD_WIDTH, 240f)),
        )
        assertEquals(
            BookPresentation.SINGLE,
            resolve(canvasSize = Size(GENERIC_SPREAD_WIDTH, 239.99f)),
        )
    }

    @Test
    fun `padding participates in generic breakpoint and usable leaf size`() {
        assertEquals(
            BookPresentation.SPREAD,
            resolve(
                canvasSize = Size(752f, 600f),
                outerPaddingPx = 16f,
            ),
        )
        assertEquals(
            BookPresentation.SINGLE,
            resolve(
                canvasSize = Size(751.99f, 600f),
                outerPaddingPx = 16f,
            ),
        )
    }

    @Test
    fun `equivalent dp geometry resolves identically at different densities`() {
        val oneX = resolveAdaptiveBookPresentation(
            canvasSize = Size(1_200f, 800f),
            density = 1f,
            outerPaddingPx = 16f,
            displayFeature = verticalFold(x = 600f, canvasHeight = 800f),
        )
        val threeX = resolveAdaptiveBookPresentation(
            canvasSize = Size(3_600f, 2_400f),
            density = 3f,
            outerPaddingPx = 48f,
            displayFeature = verticalFold(x = 1_800f, canvasHeight = 2_400f),
        )

        assertEquals(BookPresentation.SPREAD, oneX)
        assertEquals(oneX, threeX)
    }

    @Test
    fun `centered vertical fold creates two usable leaves`() {
        assertEquals(
            BookPresentation.SPREAD,
            resolveAdaptiveBookPresentation(
                canvasSize = Size(1_200f, 800f),
                density = 1f,
                outerPaddingPx = 16f,
                displayFeature = verticalFold(x = 600f, canvasHeight = 800f),
            ),
        )
    }

    @Test
    fun `vertical fold accepts exact leaf boundary and rejects either smaller dimension`() {
        val padding = 16f
        val exactCanvasWidth = MINIMUM_LEAF_WIDTH * 2f + padding * 2f
        val exactCanvasHeight = 272f
        val centerX = exactCanvasWidth * 0.5f
        val exactFold = verticalFold(x = centerX, canvasHeight = exactCanvasHeight)

        assertEquals(
            BookPresentation.SPREAD,
            resolveAdaptiveBookPresentation(
                canvasSize = Size(exactCanvasWidth, exactCanvasHeight),
                density = 1f,
                outerPaddingPx = padding,
                displayFeature = exactFold,
            ),
        )
        assertEquals(
            BookPresentation.SINGLE,
            resolveAdaptiveBookPresentation(
                canvasSize = Size(exactCanvasWidth, exactCanvasHeight),
                density = 1f,
                outerPaddingPx = padding,
                displayFeature = exactFold.copy(
                    bounds = Rect(centerX - 0.01f, 0f, centerX - 0.01f, exactCanvasHeight),
                ),
            ),
        )
    }

    @Test
    fun `off-center vertical fold rejects a narrow leaf even on a wide canvas`() {
        assertEquals(
            BookPresentation.SINGLE,
            resolveAdaptiveBookPresentation(
                canvasSize = Size(1_600f, 900f),
                density = 1f,
                outerPaddingPx = 16f,
                displayFeature = verticalFold(x = 330f, canvasHeight = 900f),
            ),
        )
    }

    @Test
    fun `vertical hinge width is excluded from both leaf candidates`() {
        val hinge = BookDisplayFeature(
            bounds = Rect(580f, 0f, 620f, 800f),
            orientation = BookFoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
            halfOpened = false,
        )

        assertTrue(hinge.avoidsContent)
        assertEquals(
            BookPresentation.SPREAD,
            resolveAdaptiveBookPresentation(
                canvasSize = Size(1_200f, 800f),
                density = 1f,
                outerPaddingPx = 16f,
                displayFeature = hinge,
            ),
        )
    }

    @Test
    fun `unsafe or half-opened horizontal feature forces a single page`() {
        val horizontalHinge = BookDisplayFeature(
            bounds = Rect(0f, 390f, 1_200f, 410f),
            orientation = BookFoldOrientation.HORIZONTAL,
            separating = true,
            occluding = false,
            halfOpened = false,
        )
        val halfOpenedFold = horizontalHinge.copy(
            separating = false,
            halfOpened = true,
        )

        assertEquals(BookPresentation.SINGLE, resolve(displayFeature = horizontalHinge))
        assertEquals(BookPresentation.SINGLE, resolve(displayFeature = halfOpenedFold))
    }

    @Test
    fun `safe flat horizontal line leaves generic wide-screen policy in control`() {
        val safeFold = BookDisplayFeature(
            bounds = Rect(0f, 400f, 1_200f, 400f),
            orientation = BookFoldOrientation.HORIZONTAL,
            separating = false,
            occluding = false,
            halfOpened = false,
        )

        assertFalse(safeFold.avoidsContent)
        assertEquals(BookPresentation.SPREAD, resolve(displayFeature = safeFold))
    }

    @Test
    fun `forced presentation has priority over adaptive safety policy`() {
        val unsafeHinge = BookDisplayFeature(
            bounds = Rect(0f, 390f, 1_200f, 410f),
            orientation = BookFoldOrientation.HORIZONTAL,
            separating = true,
            occluding = true,
            halfOpened = true,
        )

        assertEquals(
            BookPresentation.SPREAD,
            resolveAdaptiveBookPresentation(
                canvasSize = DEFAULT_CANVAS,
                density = 1f,
                outerPaddingPx = 0f,
                displayFeature = unsafeHinge,
                forcedPresentation = BookPresentation.SPREAD,
            ),
        )
    }

    @Test
    fun `window feature is clipped and translated to viewport coordinates`() {
        val windowFeature = BookDisplayFeature(
            bounds = Rect(480f, 100f, 520f, 1_100f),
            orientation = BookFoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
            halfOpened = false,
        )

        val local = windowFeature.toLocalViewport(
            viewportBoundsInWindow = Rect(100f, 200f, 900f, 1_000f),
        )

        assertEquals(Rect(380f, 0f, 420f, 800f), requireNotNull(local).bounds)
        assertEquals(windowFeature.copy(bounds = local.bounds), local)
    }

    @Test
    fun `zero-width and zero-height fold lines survive local translation`() {
        val viewport = Rect(100f, 200f, 900f, 1_000f)
        val vertical = verticalFold(x = 500f, canvasHeight = 1_200f)
            .toLocalViewport(viewport)
        val horizontal = BookDisplayFeature(
            bounds = Rect(0f, 700f, 1_100f, 700f),
            orientation = BookFoldOrientation.HORIZONTAL,
            separating = false,
            occluding = false,
            halfOpened = false,
        ).toLocalViewport(viewport)

        assertEquals(Rect(400f, 0f, 400f, 800f), requireNotNull(vertical).bounds)
        assertEquals(Rect(0f, 500f, 800f, 500f), requireNotNull(horizontal).bounds)
    }

    @Test
    fun `feature outside viewport does not produce local geometry`() {
        val outside = verticalFold(x = 950f, canvasHeight = 1_200f)

        assertNull(
            outside.toLocalViewport(
                viewportBoundsInWindow = Rect(100f, 200f, 900f, 1_000f),
            ),
        )
    }

    private fun resolve(
        canvasSize: Size = DEFAULT_CANVAS,
        outerPaddingPx: Float = 0f,
        displayFeature: BookDisplayFeature? = null,
    ): BookPresentation = resolveAdaptiveBookPresentation(
        canvasSize = canvasSize,
        density = 1f,
        outerPaddingPx = outerPaddingPx,
        displayFeature = displayFeature,
    )

    private fun verticalFold(x: Float, canvasHeight: Float) = BookDisplayFeature(
        bounds = Rect(x, 0f, x, canvasHeight),
        orientation = BookFoldOrientation.VERTICAL,
        separating = false,
        occluding = false,
        halfOpened = false,
    )

    private companion object {
        const val MINIMUM_LEAF_WIDTH = 320f
        const val GENERIC_SPREAD_WIDTH = 720f
        val DEFAULT_CANVAS = Size(1_200f, 800f)
    }
}
