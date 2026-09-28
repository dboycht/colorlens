package com.dboycht.colorlens.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/**
 * Maps between the photo's own pixel coordinates and the on-screen box that shows
 * it with `ContentScale.Fit`.
 *
 * This is the kind of arithmetic that is silently wrong for a long time: the
 * crosshair still moves, it is just a little off, and on a letterboxed photo the
 * error changes with orientation. Kept as a pure function so `ImageMappingTest`
 * can pin the four cases that matter (exact fit, letterboxed horizontally,
 * letterboxed vertically, and outside-the-image taps).
 */
class ImageMapping(
    containerSize: IntSize,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
) {
    /** Screen pixels per bitmap pixel. */
    val scale: Float

    /** Where the drawn image starts inside the container. */
    val left: Float
    val top: Float

    val displayWidth: Float
    val displayHeight: Float

    val isUsable: Boolean

    init {
        if (containerSize.width <= 0 || containerSize.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            scale = 0f
            left = 0f
            top = 0f
            displayWidth = 0f
            displayHeight = 0f
            isUsable = false
        } else {
            scale = min(
                containerSize.width / bitmapWidth.toFloat(),
                containerSize.height / bitmapHeight.toFloat(),
            )
            displayWidth = bitmapWidth * scale
            displayHeight = bitmapHeight * scale
            left = (containerSize.width - displayWidth) / 2f
            top = (containerSize.height - displayHeight) / 2f
            isUsable = true
        }
    }

    /** Bitmap point -> position inside the container. */
    fun toView(point: Offset): Offset = Offset(left + point.x * scale, top + point.y * scale)

    /**
     * Container position -> bitmap point, or null when the touch landed on the
     * letterbox bars rather than on the photo. Returning null (instead of
     * clamping) is what stops a drag off the edge from teleporting the crosshair
     * to the nearest edge pixel.
     */
    fun toBitmap(position: Offset): Offset? {
        if (!isUsable) return null
        val x = (position.x - left) / scale
        val y = (position.y - top) / scale
        if (x < 0f || y < 0f || x >= bitmapWidth || y >= bitmapHeight) return null
        return Offset(x, y)
    }
}
