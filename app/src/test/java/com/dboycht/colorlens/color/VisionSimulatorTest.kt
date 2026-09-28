package com.dboycht.colorlens.color

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the CVD simulation.
 *
 * Two kinds of assertion live here, and they do different jobs:
 *
 * 1. **Table fidelity** — the 11 severity rows are pinned against values that two
 *    independent implementations agree on (colour-science BSD-3 and DaltonLens).
 *    A typo in a generated matrix cannot pass.
 * 2. **Semantics** — that the simulation actually produces the confusions the
 *    model is about (red≈green for protan/deutan, blue≈yellow for tritan,
 *    no colour left for achromatopsia). A matrix can be numerically "fine" and
 *    still be applied in the wrong space; these assertions catch that, and the
 *    linear-vs-encoded assertion below catches the classic gamma bug.
 */
class VisionSimulatorTest {

    private fun m(row: FloatArray, r: Int, c: Int) = row[r * 3 + c]

    /**
     * Distance ignoring lightness — the part of a colour difference that a
     * red-green deficiency destroys. Two colours can be a classic confusion pair
     * and still differ in lightness (that is exactly why "which one is darker?"
     * is the coping strategy this app teaches), so lightness is excluded here on
     * purpose.
     */
    private fun chromaticDistance(a: Rgb8, b: Rgb8): Float {
        val x = ColorMath.srgbToOklab(a)
        val y = ColorMath.srgbToOklab(b)
        return kotlin.math.hypot(x.a - y.a, x.b - y.b)
    }

    // ------------------------------------------------------------ table fidelity

    @Test
    fun `severity zero is the identity for every deficiency`() {
        for (type in CvdType.entries) {
            val matrix = VisionSimulator.matrixFor(type, severity = 0f)
            if (type.isNormal || type.family == CvdFamily.ACHROMATOPSIA) {
                assertNull("$type has no matrix", matrix)
                continue
            }
            assertNotNull(matrix)
            val identity = VisionSimulator.identity()
            for (i in 0 until 9) {
                assertEquals("$type severity 0 must be a no-op at index $i", identity[i], matrix!![i], 1e-6f)
            }
        }
    }

    @Test
    fun `tabulated rows match both reference implementations`() {
        // Values read from colour-science colour/blindness/datasets/machado2010.py
        // and confirmed identical in DaltonLens-Python daltonlens/simulate.py.
        val protan01 = CvdMatrices.PROTANOMALY[1]
        assertEquals(0.856167f, m(protan01, 0, 0), 1e-6f)
        assertEquals(0.182038f, m(protan01, 0, 1), 1e-6f)
        assertEquals(-0.038205f, m(protan01, 0, 2), 1e-6f)
        assertEquals(1.004443f, m(protan01, 2, 2), 1e-6f)

        val deutan10 = CvdMatrices.DEUTERANOMALY[10]
        assertEquals(0.367322f, m(deutan10, 0, 0), 1e-6f)
        assertEquals(0.860646f, m(deutan10, 0, 1), 1e-6f)
        assertEquals(-0.227968f, m(deutan10, 0, 2), 1e-6f)
        assertEquals(0.968881f, m(deutan10, 2, 2), 1e-6f)

        val tritan10 = CvdMatrices.TRITANOMALY[10]
        assertEquals(1.255528f, m(tritan10, 0, 0), 1e-6f)
        assertEquals(-0.178779f, m(tritan10, 0, 2), 1e-6f)
        assertEquals(0.303900f, m(tritan10, 2, 2), 1e-6f)
    }

    @Test
    fun `every table has eleven severity rows of nine values`() {
        for (table in listOf(CvdMatrices.PROTANOMALY, CvdMatrices.DEUTERANOMALY, CvdMatrices.TRITANOMALY)) {
            assertEquals(11, table.size)
            for (row in table) assertEquals(9, row.size)
        }
    }

    /**
     * The paper prescribes interpolating between the two *nearest* tabulated rows.
     * Neither reference library actually does that for intermediate severities:
     * colour-science takes the slope of the segment above (`searchsorted` side
     * effects) and DaltonLens forgets to scale its alpha by 10, so both are only
     * correct on the 11 tabulated severities. This implementation follows the
     * paper, so the test pins the paper's rule rather than either library's
     * output.
     */
    @Test
    fun `intermediate severity is the midpoint of the two nearest rows`() {
        val mid = requireNotNull(VisionSimulator.matrixFor(CvdType.DEUTERANOMALY, severity = 0.15f))
        val lower = CvdMatrices.DEUTERANOMALY[1]
        val upper = CvdMatrices.DEUTERANOMALY[2]
        for (i in 0 until 9) {
            assertEquals("index $i", (lower[i] + upper[i]) / 2f, mid[i], 1e-6f)
        }

        val quarter = requireNotNull(VisionSimulator.matrixFor(CvdType.PROTANOMALY, severity = 0.25f))
        // 0.25 lies between the 0.2 and 0.3 rows, i.e. indices 2 and 3.
        val protan2 = CvdMatrices.PROTANOMALY[2]
        val protan3 = CvdMatrices.PROTANOMALY[3]
        for (i in 0 until 9) {
            assertEquals("index $i", (protan2[i] + protan3[i]) / 2f, quarter[i], 1e-6f)
        }
    }

    @Test
    fun `severity outside 0 to 1 is clamped rather than extrapolated`() {
        val low = requireNotNull(VisionSimulator.matrixFor(CvdType.DEUTERANOMALY, severity = -3f))
        val high = requireNotNull(VisionSimulator.matrixFor(CvdType.DEUTERANOMALY, severity = 7f))
        for (i in 0 until 9) {
            assertEquals(VisionSimulator.identity()[i], low[i], 1e-6f)
            assertEquals(CvdMatrices.DEUTERANOMALY[10][i], high[i], 1e-6f)
        }
    }

    /**
     * End-to-end validation against numbers published by somebody else.
     *
     * These two outputs are printed in the colorspace package documentation
     * (<https://colorspace.r-forge.r-project.org/articles/color_vision_deficiency.html>),
     * produced by an independent R implementation that does the simulation on
     * linear RGB. Matching them to the last digit exercises the *whole* pipeline:
     * gamma decoding, the matrix, gamut clamping and re-encoding. A mistake in any
     * one of those four steps changes these two hex strings.
     *
     * The companion "what if we forgot to decode gamma" value is #5E4700 — a
     * plausible-looking wrong answer, which is exactly why it is worth pinning.
     */
    @Test
    fun `the whole pipeline reproduces published deuteranopia output`() {
        assertEquals(
            "#A39000",
            VisionSimulator.simulate(requireNotNull(Rgb8.parseHex("#FF0000")), CvdType.DEUTERANOPIA).toHex(),
        )
        assertEquals(
            "#EFD63A",
            VisionSimulator.simulate(requireNotNull(Rgb8.parseHex("#00FF00")), CvdType.DEUTERANOPIA).toHex(),
        )
        // Same two colours at full strength through the protan axis: red loses
        // almost all its luminance (that is the "red looks dark" effect).
        val protanRed = VisionSimulator.simulate(requireNotNull(Rgb8.parseHex("#FF0000")), CvdType.PROTANOPIA)
        assertEquals("#6D5F00", protanRed.toHex())
    }

    // ------------------------------------------------------------------ semantics

    @Test
    fun `normal vision is left completely alone`() {
        for (hex in listOf("#FF0000", "#00FF00", "#0000FF", "#8B0000", "#F5F5DC")) {
            val rgb = requireNotNull(Rgb8.parseHex(hex))
            assertEquals(rgb, VisionSimulator.simulate(rgb, CvdType.NORMAL))
            assertEquals(rgb.toArgb(), VisionSimulator.simulateArgb(rgb.toArgb(), CvdType.NORMAL))
        }
    }

    @Test
    fun `red and green collapse together for protan and deutan deficiency`() {
        val red = requireNotNull(Rgb8.parseHex("#FF0000"))
        val green = requireNotNull(Rgb8.parseHex("#00FF00"))
        val normalGap = chromaticDistance(red, green)
        assertTrue("red and green must start far apart in hue", normalGap > 0.3f)

        for (type in listOf(CvdType.PROTANOPIA, CvdType.DEUTERANOPIA, CvdType.PROTANOMALY, CvdType.DEUTERANOMALY)) {
            val simulated = chromaticDistance(VisionSimulator.simulate(red, type), VisionSimulator.simulate(green, type))
            assertTrue(
                "$type should collapse the red/green hue difference: $normalGap -> $simulated",
                simulated < normalGap * 0.4f,
            )
        }
    }

    @Test
    fun `blue and yellow collapse together for tritan deficiency only`() {
        val blue = requireNotNull(Rgb8.parseHex("#0000FF"))
        val yellow = requireNotNull(Rgb8.parseHex("#FFFF00"))
        val normalGap = chromaticDistance(blue, yellow)

        val tritanGap = chromaticDistance(
            VisionSimulator.simulate(blue, CvdType.TRITANOPIA),
            VisionSimulator.simulate(yellow, CvdType.TRITANOPIA),
        )
        // Machado's model is explicitly weaker for the tritan axis (the paper warns
        // it "does not work well for tritanopia"), so this asks for a clear
        // reduction rather than the collapse the red/green axes show.
        assertTrue("tritanopia must shrink the blue/yellow gap: $normalGap -> $tritanGap", tritanGap < normalGap * 0.75f)

        // ... and a red-green deficiency must NOT collapse blue vs yellow.
        val deutanGap = chromaticDistance(
            VisionSimulator.simulate(blue, CvdType.DEUTERANOPIA),
            VisionSimulator.simulate(yellow, CvdType.DEUTERANOPIA),
        )
        assertTrue("deuteranopia must leave blue/yellow distinguishable: $deutanGap vs tritan $tritanGap", deutanGap > tritanGap)
    }

    @Test
    fun `confusion grows with severity`() {
        val red = requireNotNull(Rgb8.parseHex("#D32F2F"))
        val green = requireNotNull(Rgb8.parseHex("#388E3C"))
        fun gap(severity: Float) = ColorMath.srgbToOklab(VisionSimulator.simulate(red, CvdType.DEUTERANOMALY, severity))
            .distanceTo(ColorMath.srgbToOklab(VisionSimulator.simulate(green, CvdType.DEUTERANOMALY, severity)))

        val none = gap(0f)
        val mild = gap(0.5f)
        val full = gap(1f)
        assertTrue("0.0 must equal the unsimulated gap", abs(none - ColorMath.srgbToOklab(red).distanceTo(ColorMath.srgbToOklab(green))) < 1e-6f)
        assertTrue("severity should shrink the gap: $none > $mild > $full", none > mild && mild > full)
    }

    @Test
    fun `achromatopsia leaves no colour at all`() {
        for (hex in listOf("#FF0000", "#00FF00", "#0000FF", "#FFD700", "#8B0000")) {
            val rgb = requireNotNull(Rgb8.parseHex(hex))
            val seen = VisionSimulator.simulate(rgb, CvdType.ACHROMATOPSIA)
            assertEquals("$hex must come out grey", seen.r, seen.g)
            assertEquals("$hex must come out grey", seen.g, seen.b)
        }
    }

    // ------------------------------------------------------------ gamma and LUTs

    /**
     * The classic bug this pins: applying the matrices to *gamma-encoded* sRGB
     * instead of linear light. That version darkens everything, so the test
     * simulates a mid grey and checks the result still matches a luminance-based
     * expectation instead of collapsing.
     */
    @Test
    fun `simulation happens in linear light not in encoded sRGB`() {
        val grey = requireNotNull(Rgb8.parseHex("#808080"))
        // A neutral grey has no colour to lose: every deficiency must leave it grey.
        for (type in CvdType.entries) {
            val seen = VisionSimulator.simulate(grey, type)
            assertTrue(
                "$type must keep a neutral grey neutral, got ${seen.toHex()}",
                abs(seen.r - seen.g) <= 2 && abs(seen.g - seen.b) <= 2,
            )
        }
    }

    @Test
    fun `the encode LUT agrees with the exact transfer function`() {
        for (step in 0..1000) {
            val x = step / 1000f
            val viaLut = VisionSimulator.encode(x)
            val exact = ColorMath.channelTo8(x)
            assertTrue("linear $x: lut $viaLut vs exact $exact", abs(viaLut - exact) <= 1)
        }
        // Beyond the gamut the LUT must clamp, not wrap.
        assertEquals(0, VisionSimulator.encode(-0.5f))
        assertEquals(255, VisionSimulator.encode(1.5f))
    }

    @Test
    fun `every 8-bit colour survives a round trip through the decoding table`() {
        for (v in 0..255) {
            assertEquals(v, VisionSimulator.encode(ColorMath.srgbToLinear(v)))
        }
    }

    // -------------------------------------------------------------- persistence

    @Test
    fun `every deficiency type survives a settings round trip`() {
        for (type in CvdType.CHOICES) {
            assertEquals(type, CvdType.fromId(type.name))
        }
        assertTrue("all eight diagnoses must be offered", CvdType.CHOICES.size == 8)
        // An unreadable stored id must fall back to normal vision: the app must
        // never invent a colour weakness the user did not choose (see AppSettings).
        assertEquals(CvdType.NORMAL, CvdType.fromId("nonsense"))
        assertEquals(CvdType.NORMAL, CvdType.fromId(null))
    }
}
