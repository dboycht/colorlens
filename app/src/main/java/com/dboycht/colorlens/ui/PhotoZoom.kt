package com.dboycht.colorlens.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Zoom and pan of the photo, held in **view pixels**.
 *
 * ## The convention (one formula, used by both drawing and hit-testing)
 *
 * A point that the fitted image would draw at `f` is drawn at `f * scale + offset`.
 * `ImageMapping` produces `f`; this class produces the rest. Drawing uses
 * `graphicsLayer(scale, translation, transformOrigin = 0,0)`, which applies exactly
 * that formula, and `toBitmap` inverts it — so the crosshair, the loupe and the
 * sampled colour all stay glued to the same photo pixel while zooming.
 *
 * Kept as a data class of pure functions because zoom arithmetic is the kind that
 * looks right and is off by a factor of `scale`: `PhotoZoomTest` pins the four
 * properties that matter (the point under the fingers stays put, the limits hold,
 * the photo always covers the box, and clamping is idempotent).
 */
data class PhotoZoom(val scale: Float = MIN_SCALE, val offset: Offset = Offset.Zero) {

    val isZoomed: Boolean get() = scale > MIN_SCALE + 1e-3f

    /**
     * Multiplies the zoom by [factor] while keeping whatever sits under [centroid]
     * exactly where it is — the behaviour the fingers expect from a pinch, and also
     * what the ＋/－ buttons want (they pass the crosshair, so the sampled spot does
     * not run off the screen while the user is zooming in on it).
     */
    fun zoomedBy(factor: Float, centroid: Offset): PhotoZoom {
        val target = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (target == scale) return this
        val ratio = target / scale
        return copy(scale = target, offset = centroid - (centroid - offset) * ratio)
    }

    fun pannedBy(delta: Offset): PhotoZoom = copy(offset = offset + delta)

    fun reset(): PhotoZoom = NONE

    /**
     * Forces the photo to keep covering the box: panning stops at the photo's edge
     * instead of letting the user drag it away and stare at empty background. The
     * axis in which the photo is narrower than the box (the letterbox axis at
     * scale 1) is pinned to zero.
     */
    fun clampedTo(mapping: ImageMapping, viewport: IntSize): PhotoZoom {
        if (!mapping.isUsable || viewport.width <= 0 || viewport.height <= 0) return NONE
        val s = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        return PhotoZoom(
            scale = s,
            offset = Offset(
                clampAxis(offset.x, mapping.left * s, mapping.displayWidth * s, viewport.width.toFloat()),
                clampAxis(offset.y, mapping.top * s, mapping.displayHeight * s, viewport.height.toFloat()),
            ),
        )
    }

    companion object {
        const val MIN_SCALE = 1f

        /**
         * 8x on a 2048-px photo shows roughly the same 48-px neighbourhood as the
         * magnifier, which is as fine as picking a colour ever needs to be.
         */
        const val MAX_SCALE = 8f

        val NONE = PhotoZoom()

        /**
         * `offset` such that the content starting at [contentStart] with scaled size
         * [contentSize] still covers a viewport of [viewSize]: its leading edge must
         * not come in past 0, its trailing edge must not come in past the far side.
         * When the content is smaller than the viewport (a letterboxed axis) the only
         * honest answer is "do not move".
         */
        private fun clampAxis(offset: Float, contentStart: Float, contentSize: Float, viewSize: Float): Float {
            val hi = -contentStart
            val lo = viewSize - (contentStart + contentSize)
            // `+ 0f` normalises negative zero: `-contentStart` with a flush-left photo
            // produces -0.0f, which prints and compares as 0 but is a *different*
            // packed value, so `PhotoZoom.NONE == clamped` would quietly be false.
            return (if (lo > hi) 0f else offset.coerceIn(lo, hi)) + 0f
        }
    }
}

/**
 * The one step a ＋/－ tap takes. 1.5 keeps the number of taps to reach maximum
 * reasonable (six) while still feeling like a step rather than a jump.
 */
const val ZOOM_STEP = 1.5f

/**
 * Zoom by a fixed factor the way the buttons want it: anchored on [anchor] (the
 * crosshair, in **bitmap** coordinates) when there is one, so the spot the user is
 * studying stays where it is while it grows, and on the middle of the box
 * otherwise. The result is clamped to [viewport] so a zoom can never leave the
 * photo half off-screen.
 */
fun PhotoZoom.zoomedBy(factor: Float, anchor: Offset?, mapping: ImageMapping, viewport: IntSize): PhotoZoom {
    if (!mapping.isUsable || viewport.width <= 0 || viewport.height <= 0) return this
    val centre = Offset(viewport.width / 2f, viewport.height / 2f)
    val raw = anchor?.let { mapping.toView(it, this) } ?: centre
    val centroid = Offset(
        raw.x.coerceIn(0f, viewport.width.toFloat()),
        raw.y.coerceIn(0f, viewport.height.toFloat()),
    )
    return zoomedBy(factor, centroid).clampedTo(mapping, viewport)
}
