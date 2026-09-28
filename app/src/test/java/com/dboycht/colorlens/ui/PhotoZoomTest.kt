package com.dboycht.colorlens.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zoom arithmetic, which is the kind that looks right until you check it.
 *
 * The property that matters to the user is *not* "the scale changed" but "the pixel
 * I am pointing at is still under my finger": get that wrong and zooming makes the
 * crosshair slide off the subject exactly when the user is trying to be precise.
 * All cases use a 200x100 photo, which is letterboxed in a square box, because the
 * letterbox axis is where the clamping rules differ.
 */
class PhotoZoomTest {

    private val square = ImageMapping(IntSize(200, 200), bitmapWidth = 200, bitmapHeight = 100)
    private val exact = ImageMapping(IntSize(200, 200), bitmapWidth = 200, bitmapHeight = 200)
    private val viewport = IntSize(200, 200)

    @Test
    fun `the point under the centroid stays put while zooming`() {
        val centroid = Offset(100f, 100f)
        val before = square.toBitmap(centroid, PhotoZoom.NONE)
        val zoomed = PhotoZoom.NONE.zoomedBy(2f, centroid).clampedTo(square, viewport)
        assertEquals(2f, zoomed.scale, 0.0001f)
        assertEquals("the sampled pixel must not move while zooming", before, square.toBitmap(centroid, zoomed))
    }

    @Test
    fun `zooming out again returns to exactly where it started`() {
        val centroid = Offset(60f, 120f)
        val there = PhotoZoom.NONE.zoomedBy(2f, centroid).clampedTo(square, viewport)
        val back = there.zoomedBy(0.5f, centroid).clampedTo(square, viewport)
        assertEquals(PhotoZoom.MIN_SCALE, back.scale, 0.0001f)
        assertEquals(Offset.Zero, back.offset)
    }

    @Test
    fun `the scale is clamped to the supported range`() {
        assertEquals(PhotoZoom.MAX_SCALE, PhotoZoom.NONE.zoomedBy(1000f, Offset(10f, 10f)).scale, 0.0001f)
        assertEquals(PhotoZoom.MIN_SCALE, PhotoZoom.NONE.zoomedBy(0.001f, Offset(10f, 10f)).scale, 0.0001f)
        val max = PhotoZoom(scale = PhotoZoom.MAX_SCALE)
        assertTrue("already at the limit is a no-op, not a new value", max === max.zoomedBy(2f, Offset.Zero))
    }

    @Test
    fun `a photo at scale one cannot be panned at all`() {
        val panned = PhotoZoom.NONE.pannedBy(Offset(50f, -80f)).clampedTo(square, viewport)
        assertEquals(Offset.Zero, panned.offset)
        assertFalse(panned.isZoomed)
    }

    @Test
    fun `panning at scale two stops at the photo's edges`() {
        val zoomed = PhotoZoom(scale = 2f)
        // Full-width photo at 2x: the offset may run from 0 (left edge at the left of
        // the box) to -width (right edge at the right of the box), never past either.
        assertEquals(0f, zoomed.pannedBy(Offset(500f, 0f)).clampedTo(square, viewport).offset.x, 0.0001f)
        assertEquals(-200f, zoomed.pannedBy(Offset(-500f, 0f)).clampedTo(square, viewport).offset.x, 0.0001f)
        // The letterbox axis is exactly filled at 2x, so its offset is pinned to the
        // value that removes the bars rather than left free.
        assertEquals(-100f, zoomed.clampedTo(square, viewport).offset.y, 0.0001f)
    }

    @Test
    fun `clamping is idempotent`() {
        val once = PhotoZoom(scale = 3.3f, offset = Offset(-40f, 25f)).clampedTo(square, viewport)
        assertEquals(once, once.clampedTo(square, viewport))
    }

    @Test
    fun `an unusable mapping collapses to no zoom instead of nonsense`() {
        val empty = ImageMapping(IntSize.Zero, bitmapWidth = 0, bitmapHeight = 0)
        assertEquals(PhotoZoom.NONE, PhotoZoom(scale = 4f, offset = Offset(9f, 9f)).clampedTo(empty, viewport))
        assertEquals(PhotoZoom.NONE, PhotoZoom(scale = 4f).clampedTo(square, IntSize.Zero))
    }

    @Test
    fun `bitmap and view coordinates survive a round trip at every zoom level`() {
        for (scale in listOf(1f, 1.5f, 2.25f, 4f, PhotoZoom.MAX_SCALE)) {
            val zoom = PhotoZoom(scale = scale).clampedTo(square, viewport)
            for (point in listOf(Offset(1f, 1f), Offset(100f, 50f), Offset(198f, 98f))) {
                val view = square.toView(point, zoom)
                val back = square.toBitmap(view, zoom)
                assertEquals("scale $scale point $point", point, back)
            }
        }
    }

    @Test
    fun `tapping a letterbox bar is rejected at scale one but maps at scale two`() {
        // 10 px from the top of the box: black bar at 1x (the photo starts at y=50).
        assertEquals(null, square.toBitmap(Offset(100f, 10f), PhotoZoom.NONE))
        val zoomed = PhotoZoom(scale = 2f).clampedTo(square, viewport)
        val mapped = square.toBitmap(Offset(100f, 10f), zoomed)
        assertTrue("zooming in must make the whole box part of the photo: $mapped", mapped != null)
        assertEquals(Offset(50f, 5f), mapped)
    }

    @Test
    fun `the button zoom keeps the crosshair still`() {
        val crosshair = Offset(120f, 40f)
        val first = PhotoZoom.NONE.zoomedBy(ZOOM_STEP, crosshair, exact, viewport)
        assertEquals(ZOOM_STEP, first.scale, 0.0001f)
        assertEquals(
            "the sampled spot must stay on screen while the buttons zoom into it",
            exact.toView(crosshair, PhotoZoom.NONE),
            exact.toView(crosshair, first),
        )
        val second = first.zoomedBy(ZOOM_STEP, crosshair, exact, viewport)
        assertEquals(ZOOM_STEP * ZOOM_STEP, second.scale, 0.0001f)
        assertEquals(exact.toView(crosshair, PhotoZoom.NONE), exact.toView(crosshair, second))
    }

    @Test
    fun `the button zoom falls back to the middle of the box`() {
        val noMarker = PhotoZoom.NONE.zoomedBy(ZOOM_STEP, null, exact, viewport)
        assertEquals(ZOOM_STEP, noMarker.scale, 0.0001f)
        assertEquals(
            "with no crosshair the middle of the box is the anchor",
            exact.toBitmap(Offset(100f, 100f), PhotoZoom.NONE),
            exact.toBitmap(Offset(100f, 100f), noMarker),
        )
        assertEquals(PhotoZoom.NONE, PhotoZoom.NONE.zoomedBy(ZOOM_STEP, null, exact, IntSize.Zero))
    }

    @Test
    fun `a crosshair in the corner cannot drag the photo off the box`() {
        // The letterboxed photo cannot keep a corner pixel still at 8x without
        // exposing background, so the clamp wins. What must hold regardless is that
        // the photo still covers the whole box afterwards.
        val corner = PhotoZoom.NONE.zoomedBy(PhotoZoom.MAX_SCALE, Offset(0f, 0f), square, viewport)
        assertEquals(PhotoZoom.MAX_SCALE, corner.scale, 0.0001f)
        val left = square.left * corner.scale + corner.offset.x
        val top = square.top * corner.scale + corner.offset.y
        assertTrue("left edge $left must not come inside the box", left <= 0.001f)
        assertTrue("top edge $top must not come inside the box", top <= 0.001f)
        assertTrue(left + square.displayWidth * corner.scale >= 199.999f)
        assertTrue(top + square.displayHeight * corner.scale >= 199.999f)
    }
}
