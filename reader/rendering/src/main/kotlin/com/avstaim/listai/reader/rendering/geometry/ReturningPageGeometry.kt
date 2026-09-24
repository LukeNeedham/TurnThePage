package com.avstaim.listai.reader.rendering.geometry

import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection

/**
 * Returns a previously turned right-hand leaf onto the single visible page.
 *
 * Input follows the ordinary rightward BACKWARD gesture, preserving its drag/settle endpoints.
 * The physical leaf follows the FORWARD path in reverse: it starts beyond the left binding and
 * ends flat over the current page. Translating the pointer by one width retains vertical hand
 * movement; reducing it to a progress scalar would discard the tilt and jump on release.
 * [PageCurlGeometry.progress] is the remaining forward curl, so return progress is `1 - progress`.
 */
fun PageCurlGeometryEngine.calculateReturningPage(
    pageBounds: Rect,
    pointer: Point,
    origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
    edgeAnchorY: Double? = null,
    customAnchor: Point? = null,
): PageCurlGeometry = calculate(
    pageBounds = pageBounds,
    pointer = Point(pointer.x - pageBounds.width, pointer.y),
    direction = TurnDirection.FORWARD,
    origin = origin,
    edgeAnchorY = edgeAnchorY,
    customAnchor = customAnchor?.let { anchor ->
        Point(pageBounds.left + (pageBounds.right - anchor.x), anchor.y)
    },
)
