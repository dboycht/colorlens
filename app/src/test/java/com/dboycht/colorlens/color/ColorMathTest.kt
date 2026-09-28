package com.dboycht.colorlens.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour math is the foundation everything else stands on: a wrong
 * coefficient would mis-name and mis-compare every pixel without ever throwing.
 * These tests round-trip through the published conversions so a typo cannot hide.
 */
class ColorMathTest {

    private val eps = 0.005f

    @Test
    fun `white maps to L=1 with zero chroma`() {
        val lab = ColorMath.srgbToOklab(Rgb8(255, 255, 255))
        assertEquals(1.0f, lab.l, eps)
        assertEquals(0.0f, lab.a, eps)
        assertEquals(0.0f, lab.b, eps)
    }

    @Test
    fun `black maps to L=0 with zero chroma`() {
        val lab = ColorMath.srgbToOklab(Rgb8(0, 0, 0))
        assertEquals(0.0f, lab.l, eps)
        assertEquals(0.0f, lab.a, eps)
        assertEquals(0.0f, lab.b, eps)
    }

    @Test
    fun `sRGB red matches the published OKLab value`() {
        // Ottosson's reference numbers for #FF0000.
        val lab = ColorMath.srgbToOklab(Rgb8(255, 0, 0))
        assertEquals(0.6279f, lab.l, 0.002f)
        assertEquals(0.2249f, lab.a, 0.002f)
        assertEquals(0.1258f, lab.b, 0.002f)
    }

    @Test
    fun `oklab round-trips every channel exactly`() {
        var checked = 0
        for (r in 0..255 step 15) {
            for (g in 0..255 step 15) {
                for (b in 0..255 step 15) {
                    val rgb = Rgb8(r, g, b)
                    val back = ColorMath.oklabToSrgb(ColorMath.srgbToOklab(rgb))
                    assertEquals("r of $rgb", rgb.r, back.r)
                    assertEquals("g of $rgb", rgb.g, back.g)
                    assertEquals("b of $rgb", rgb.b, back.b)
                    checked++
                }
            }
        }
        assertTrue("expected a meaningful number of samples, got $checked", checked > 4000)
    }

    @Test
    fun `opposite lightness endpoints really are opposite`() {
        val white = ColorMath.srgbToOklab(Rgb8(255, 255, 255))
        val black = ColorMath.srgbToOklab(Rgb8(0, 0, 0))
        assertEquals(1.0f, white.distanceTo(black), 0.01f)
    }

    @Test
    fun `contrast ratio endpoints match WCAG`() {
        assertEquals(21.0f, ColorMath.contrastRatio(Rgb8(255, 255, 255), Rgb8(0, 0, 0)), 0.05f)
        assertEquals(1.0f, ColorMath.contrastRatio(Rgb8(18, 52, 86), Rgb8(18, 52, 86)), 0.001f)
    }

    @Test
    fun `hue delta wraps around the circle`() {
        assertEquals(20f, ColorMath.hueDeltaDegrees(350f, 10f), 0.001f)
        assertEquals(180f, ColorMath.hueDeltaDegrees(0f, 180f), 0.001f)
        assertEquals(90f, ColorMath.hueDeltaDegrees(90f, 180f), 0.001f)
        // Never more than half a turn, in either order.
        assertEquals(ColorMath.hueDeltaDegrees(10f, 350f), ColorMath.hueDeltaDegrees(350f, 10f), 0.001f)
    }

    @Test
    fun `oklch chroma and hue agree with the cartesian form`() {
        val lab = ColorMath.srgbToOklab(Rgb8(255, 0, 0))
        val lch = lab.toOklch()
        assertEquals(lab.l, lch.l, 0.0001f)
        assertEquals(kotlin.math.sqrt(lab.a * lab.a + lab.b * lab.b), lch.c, 0.0001f)
        assertTrue("hue must be normalised to 0..360, was ${lch.hDeg}", lch.hDeg >= 0f && lch.hDeg < 360f)
    }
}
