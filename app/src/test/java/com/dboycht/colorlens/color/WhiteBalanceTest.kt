package com.dboycht.colorlens.color

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * White-balance-from-a-reference: the boundaries that decide whether a tapped
 * colour may be used as a white reference, and the correction it implies.
 */
class WhiteBalanceTest {

    private fun rgb(hex: String) = requireNotNull(Rgb8.parseHex(hex))

    private fun usable(hex: String): WhiteBalance.Gains {
        val evaluation = WhiteBalance.evaluate(rgb(hex))
        assertTrue("expected $hex to be usable, got $evaluation", evaluation is WhiteBalance.Evaluation.Usable)
        return (evaluation as WhiteBalance.Evaluation.Usable).gains
    }

    @Test
    fun `a warm white reference is turned neutral`() {
        val reference = rgb("#F2E3C8")
        val fixed = usable("#F2E3C8").applied(reference)
        assertTrue("should be neutral after correction: ${fixed.toHex()}", abs(fixed.r - fixed.g) <= 2)
        assertTrue("should be neutral after correction: ${fixed.toHex()}", abs(fixed.g - fixed.b) <= 2)
    }

    @Test
    fun `correcting a warm white keeps its brightness`() {
        // The point of using OKLab lightness for the neutral target: the photo must
        // not get brighter or darker, only less yellow.
        val reference = rgb("#F2E3C8")
        val before = ColorMath.relativeLuminance(reference)
        val after = ColorMath.relativeLuminance(usable("#F2E3C8").applied(reference))
        val drift = abs(after - before) / before
        assertTrue("luminance drifted by ${(drift * 100).toInt()}%: $before -> $after", drift < 0.05f)
    }

    @Test
    fun `a neutral grey reference needs almost no correction`() {
        val gains = usable("#B0B0B0")
        assertTrue("expected identity-ish gains, got $gains", gains.isIdentity)
    }

    @Test
    fun `a coloured reference is refused with a reason the user can act on`() {
        val evaluation = WhiteBalance.evaluate(rgb("#C0392B"))
        assertTrue("a red surface must not be usable", evaluation is WhiteBalance.Evaluation.Unusable)
        val reason = (evaluation as WhiteBalance.Evaluation.Unusable).reason
        assertTrue("reason must say what is wrong: $reason", reason.contains("颜色"))
        assertTrue("reason must say what to do: $reason", reason.contains("再点"))
    }

    @Test
    fun `a dark reference is refused`() {
        val evaluation = WhiteBalance.evaluate(rgb("#3A3A3A"))
        assertTrue("dark grey is not a white reference", evaluation is WhiteBalance.Evaluation.Unusable)
        val reason = (evaluation as WhiteBalance.Evaluation.Unusable).reason
        assertTrue("reason must mention darkness: $reason", reason.contains("暗"))
    }

    @Test
    fun `gains stay inside the clamp for every usable reference`() {
        for (hex in listOf("#FFFFFF", "#F8F8F8", "#F2E3C8", "#E8E0D0", "#D8D8D8", "#C8C4BC", "#B0B0B0", "#A8A49C")) {
            val gains = usable(hex)
            for (gain in listOf(gains.r, gains.g, gains.b)) {
                assertTrue("$hex gain $gain below clamp", gain >= WhiteBalance.MIN_GAIN)
                assertTrue("$hex gain $gain above clamp", gain <= WhiteBalance.MAX_GAIN)
            }
        }
    }

    @Test
    fun `applying gains never leaves the displayable range`() {
        val gains = usable("#F2E3C8")
        for (r in 0..255 step 51) {
            for (g in 0..255 step 51) {
                for (b in 0..255 step 51) {
                    val fixed = gains.applied(Rgb8(r, g, b))
                    assertTrue(fixed.r in 0..255 && fixed.g in 0..255 && fixed.b in 0..255)
                }
            }
        }
    }

    @Test
    fun `a grey object under warm light becomes grey again`() {
        // The whole point of the feature. White paper and a grey object under the
        // same warm lamp are both pulled towards orange; measuring the paper must
        // be enough to put the grey back.
        //
        // The two observed values must come from ONE light model, otherwise the
        // test is comparing two different lamps: the paper is white × cast, and the
        // grey is 0x80 × the same cast.
        //   cast = (255/255, 245/255, 226/255) applied to 0x80 gives #807B71.
        val observedPaper = rgb("#FFF5E2")
        val observedGrey = rgb("#807B71")
        val gains = usable("#FFF5E2")
        val fixedGrey = gains.applied(observedGrey)
        assertTrue("grey came back as ${fixedGrey.toHex()}", abs(fixedGrey.r - fixedGrey.g) <= 3)
        assertTrue("grey came back as ${fixedGrey.toHex()}", abs(fixedGrey.g - fixedGrey.b) <= 3)
        // …and the paper itself is neutral, not tinted the other way.
        val fixedPaper = gains.applied(observedPaper)
        assertTrue("paper must end up neutral: ${fixedPaper.toHex()}", abs(fixedPaper.r - fixedPaper.g) <= 2)
        assertTrue("paper must end up neutral: ${fixedPaper.toHex()}", abs(fixedPaper.g - fixedPaper.b) <= 2)
    }
}
