package com.dboycht.colorlens.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crosshair is only as accurate as this mapping.
 *
 * A wrong scale still produces a crosshair that follows the finger, so the bug
 * looks like "the app is a bit off near the edges" rather than a crash — exactly
 * the kind of thing that survives until a user complains. Hence the four shapes
 * that matter: exact fit, letterboxed vertically, scaled down, and degenerate.
 */
class ImageMappingTest {

    @Test
    fun `exact fit is the identity`() {
        val mapping = ImageMapping(IntSize(200, 100), bitmapWidth = 200, bitmapHeight = 100)
        assertTrue(mapping.isUsable)
        assertEquals(1f, mapping.scale, 0.0001f)
        assertEquals(Offset(0f, 0f), mapping.toView(Offset(0f, 0f)))
        assertEquals(Offset(200f, 100f), mapping.toView(Offset(200f, 100f)))
        assertEquals(Offset(50f, 25f), mapping.toBitmap(Offset(50f, 25f)))
    }

    @Test
    fun `a wide photo in a square box is letterboxed and the bars are rejected`() {
        // 200x100 photo in a 200x200 box: full width, 50 px of black above and below.
        val mapping = ImageMapping(IntSize(200, 200), bitmapWidth = 200, bitmapHeight = 100)
        assertEquals(1f, mapping.scale, 0.0001f)
        assertEquals(Offset(0f, 50f), mapping.toView(Offset(0f, 0f)))
        assertNull("a touch on the top bar must not move the crosshair", mapping.toBitmap(Offset(100f, 10f)))
        assertNull(mapping.toBitmap(Offset(100f, 190f)))
        assertEquals(Offset(100f, 50f), mapping.toBitmap(Offset(100f, 100f)))
    }

    @Test
    fun `a large photo is scaled to the narrow side`() {
        // 200x100 photo in a 100x100 box: scale 0.5, so the drawn image is 100x50.
        val mapping = ImageMapping(IntSize(100, 100), bitmapWidth = 200, bitmapHeight = 100)
        assertEquals(0.5f, mapping.scale, 0.0001f)
        assertEquals(Offset(0f, 25f), mapping.toView(Offset(0f, 0f)))
        assertEquals(Offset(100f, 75f), mapping.toView(Offset(200f, 100f)))
        // Round trip through an interior point: an interior point is the only kind
        // that is guaranteed to survive both directions (the far edge is exclusive).
        val interior = Offset(100f, 50f)
        assertEquals(interior, mapping.toBitmap(mapping.toView(interior)))
        assertEquals(Offset(198f, 98f), mapping.toBitmap(Offset(99f, 74f)))
    }

    @Test
    fun `degenerate sizes are unusable instead of dividing by zero`() {
        val mapping = ImageMapping(IntSize.Zero, bitmapWidth = 100, bitmapHeight = 100)
        assertFalse(mapping.isUsable)
        assertNull(mapping.toBitmap(Offset.Zero))
        assertTrue(mapping.toView(Offset.Zero).x.isFinite())

        val noBitmap = ImageMapping(IntSize(100, 100), bitmapWidth = 0, bitmapHeight = 0)
        assertFalse(noBitmap.isUsable)
        assertNull(noBitmap.toBitmap(Offset(1f, 1f)))
    }

    @Test
    fun `the far edge of the bitmap is inside`() {
        val mapping = ImageMapping(IntSize(100, 100), bitmapWidth = 100, bitmapHeight = 100)
        // Last addressable pixel: x == width-1 must map, x == width must not.
        assertEquals(Offset(99f, 99f), mapping.toBitmap(Offset(99f, 99f)))
        assertNull(mapping.toBitmap(Offset(100f, 50f)))
    }

    @Test
    fun `the zoom-aware overloads reduce to the plain ones at scale one`() {
        val mapping = ImageMapping(IntSize(200, 200), bitmapWidth = 200, bitmapHeight = 100)
        val point = Offset(10f, 10f)
        val touch = Offset(100f, 100f)
        assertEquals(mapping.toView(point), mapping.toView(point, PhotoZoom.NONE))
        assertEquals(mapping.toBitmap(touch), mapping.toBitmap(touch, PhotoZoom.NONE))
        // The letterbox rule has to survive the zoom path too.
        assertNull(mapping.toBitmap(Offset(100f, 10f), PhotoZoom.NONE))
        assertNull("a zero scale must return null, not NaN", mapping.toBitmap(touch, PhotoZoom(scale = 0f)))
    }
}
