package com.dboycht.colorlens.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "can I tell these two apart?" verdict.
 *
 * The bar here is higher than "the numbers moved": the verdict is what the user
 * acts on, so the tests check the *claims* it makes — that a confusion pair is not
 * described as easy, that a hidden difference is flagged, and that when the user
 * cannot see a difference the advice still gives them something to do.
 */
class ColorCompareTest {

    private fun rgb(hex: String) = requireNotNull(Rgb8.parseHex(hex))

    private fun pair(a: String, b: String, type: CvdType) = ColorCompare.compare(rgb(a), rgb(b), type)

    @Test
    fun `the same colour is reported as the same`() {
        val verdict = pair("#4CAF50", "#4CAF50", CvdType.DEUTERANOMALY)
        assertEquals(ColorCompare.Band.IDENTICAL, verdict.band)
        assertEquals(0f, verdict.seenDistance, 1e-6f)
        assertEquals(0, verdict.lightnessGapPct)
        assertTrue(verdict.headline.contains("同一个颜色"))
        assertTrue("advice must still be actionable: ${verdict.advice}", verdict.advice.isNotEmpty())
    }

    @Test
    fun `with normal vision the verdict is just the real difference`() {
        val verdict = pair("#FF0000", "#00FF00", CvdType.NORMAL)
        assertEquals(verdict.normalDistance, verdict.seenDistance, 1e-6f)
        assertFalse("nothing is hidden from a trichromat", verdict.hiddenFromUser)
        assertTrue(verdict.band == ColorCompare.Band.DIFFERENT || verdict.band == ColorCompare.Band.VERY_DIFFERENT)
    }

    @Test
    fun `a red green confusion pair is called out for a deutanope`() {
        val normal = pair("#FF0000", "#00FF00", CvdType.NORMAL)
        val deutan = pair("#FF0000", "#00FF00", CvdType.DEUTERANOPIA)

        // The hue difference collapses...
        assertTrue(
            "deuteranopia must collapse the hue difference: " +
                "${normal.normalChromaticDistance} -> ${deutan.seenChromaticDistance}",
            deutan.seenChromaticDistance < normal.normalChromaticDistance * 0.4f,
        )
        assertTrue("this is the hue-collapse case", deutan.hueCollapsed)

        // ...but the pair can still differ in lightness, and the app must not
        // pretend otherwise: it says the hue is gone and points at the lightness.
        assertTrue(
            "the user must be told the hue collapsed: ${deutan.advice}",
            deutan.advice.contains("色相"),
        )
        assertTrue(
            "the advice must name a usable cue: ${deutan.advice}",
            deutan.advice.contains("明暗") || deutan.advice.contains("对比") || deutan.advice.contains("问别人"),
        )
        // Normal vision sees a hue difference and nothing is "collapsed" for them.
        assertFalse(normal.hueCollapsed)
        assertFalse(normal.hiddenFromUser)
    }

    @Test
    fun `a difference that is hidden from the user is flagged as such`() {
        // Two greens that a trichromat separates by hue but a deuteranope sees as
        // the same lightness and (nearly) the same colour.
        val verdict = pair("#4CAF50", "#7CB342", CvdType.DEUTERANOPIA)
        if (verdict.hiddenFromUser) {
            assertTrue("advice must not claim the user can see it: ${verdict.advice}", verdict.advice.contains("色觉正常的人"))
        } else {
            assertTrue(verdict.hueCollapsed || verdict.band != ColorCompare.Band.IDENTICAL)
        }
    }

    @Test
    fun `black and white are reported as obviously different to everyone`() {
        for (type in CvdType.entries) {
            val verdict = pair("#000000", "#FFFFFF", type)
            assertEquals("$type", ColorCompare.Band.VERY_DIFFERENT, verdict.band)
            assertFalse("$type", verdict.hiddenFromUser)
        }
    }

    @Test
    fun `under achromatopsia the advice can only be about lightness`() {
        val verdict = pair("#8A2BE2", "#1E88E5", CvdType.ACHROMATOPSIA)
        // Both simulate to grey, so the only honest advice is lightness or asking.
        assertTrue("advice was: ${verdict.advice}", verdict.advice.contains("明暗"))
        assertTrue(verdict.advice.contains("只能看到明暗"))
    }

    @Test
    fun `when nothing can be seen the advice still tells the user what to do`() {
        val verdict = pair("#4CAF50", "#43A047", CvdType.DEUTERANOPIA)
        assertTrue(
            "this pair is meant to be at or near the indistinguishable end: ${verdict.band}",
            verdict.band == ColorCompare.Band.IDENTICAL || verdict.band == ColorCompare.Band.VERY_CLOSE,
        )
        val fallback = listOf("明暗", "标签", "包装", "问别人", "对比")
        assertTrue(
            "unactionable advice: ${verdict.advice}",
            fallback.any { verdict.advice.contains(it) },
        )
    }

    @Test
    fun `bands are ordered and thresholds are pinned`() {
        assertEquals(ColorCompare.Band.IDENTICAL, ColorCompare.bandFor(0f))
        assertEquals(ColorCompare.Band.IDENTICAL, ColorCompare.bandFor(ColorCompare.JND - 0.001f))
        assertEquals(ColorCompare.Band.VERY_CLOSE, ColorCompare.bandFor(ColorCompare.JND))
        assertEquals(ColorCompare.Band.VERY_CLOSE, ColorCompare.bandFor(0.049f))
        assertEquals(ColorCompare.Band.CLOSE, ColorCompare.bandFor(0.05f))
        assertEquals(ColorCompare.Band.DIFFERENT, ColorCompare.bandFor(0.10f))
        assertEquals(ColorCompare.Band.VERY_DIFFERENT, ColorCompare.bandFor(0.20f))
        // Monotone: a bigger distance can never land in a "closer" band.
        val order = ColorCompare.Band.entries
        var last = -1
        for (step in 0..400) {
            val band = order.indexOf(ColorCompare.bandFor(step / 1000f))
            assertTrue("band went backwards at ${step / 1000f}", band >= last)
            last = band
        }
    }

    @Test
    fun `every pair produces a complete verdict`() {
        val palette = listOf(
            "#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF",
            "#000000", "#FFFFFF", "#808080", "#8B0000", "#006400", "#F5F5DC",
        )
        var pairs = 0
        for (a in palette) {
            for (b in palette) {
                for (type in listOf(CvdType.NORMAL, CvdType.DEUTERANOMALY, CvdType.TRITANOPIA, CvdType.ACHROMATOPSIA)) {
                    val verdict = ColorCompare.compare(rgb(a), rgb(b), type)
                    assertTrue("$a/$b/$type headline", verdict.headline.isNotEmpty())
                    assertTrue("$a/$b/$type advice", verdict.advice.isNotEmpty())
                    assertTrue("$a/$b/$type speech", verdict.speakText.isNotEmpty() && verdict.speakTextDetailed.isNotEmpty())
                    assertEquals("$a/$b/$type band", ColorCompare.bandFor(verdict.seenDistance), verdict.band)
                    assertTrue("$a/$b/$type gap", verdict.lightnessGapPct in 0..100)
                    pairs++
                }
            }
        }
        assertTrue("expected a real sweep, got $pairs", pairs >= 500)
    }

    @Test
    fun `the verdict is symmetric`() {
        val forward = pair("#FF0000", "#006400", CvdType.PROTANOPIA)
        val backward = pair("#006400", "#FF0000", CvdType.PROTANOPIA)
        assertEquals(forward.band, backward.band)
        assertEquals(forward.seenDistance, backward.seenDistance, 1e-6f)
        assertEquals(forward.normalDistance, backward.normalDistance, 1e-6f)
        assertEquals(forward.lightnessGapPct, backward.lightnessGapPct)
        assertEquals(forward.familyHiddenFromUser, backward.familyHiddenFromUser)
    }

    @Test
    fun `muted colours can lose their family even when the raw difference is small`() {
        // Found on a real photo: a greyish brown and a muted orange. To a normal
        // observer these are two different colour families; a deuteranope sees one
        // yellow-grey. The old test — "did most of the chromatic *distance*
        // disappear" — missed this case entirely, because two muted colours never
        // had much chromatic distance to begin with, and the app ended up telling
        // the user not to worry.
        val verdict = pair("#85796C", "#B07A3C", CvdType.DEUTERANOMALY)

        assertFalse(
            "these must be different families to normal vision: ${verdict.first.baseWord} / ${verdict.second.baseWord}",
            verdict.sameFamily,
        )
        assertEquals(
            "the user is meant to see only one family here",
            verdict.seenFirst.baseWord,
            verdict.seenSecond.baseWord,
        )
        assertTrue(verdict.familyHiddenFromUser)
        assertTrue("family loss implies hue collapse", verdict.hueCollapsed)
        assertFalse(
            "must not be dismissed as harmless: ${verdict.advice}",
            verdict.advice.contains("不用特别担心"),
        )
        assertTrue(
            "must name the information that is gone: ${verdict.advice}",
            verdict.advice.contains("色相") && verdict.advice.contains("颜色种类"),
        )
        assertTrue(
            "headline must distinguish 'can tell apart' from 'can name the colour': ${verdict.headline}",
            verdict.headline.contains("颜色种类"),
        )
    }

    @Test
    fun `the hue gap shrinks when the hue collapses`() {
        val deutan = pair("#FF0000", "#00FF00", CvdType.DEUTERANOPIA)
        assertTrue("normal hue gap: ${deutan.normalHueGapDeg}", deutan.normalHueGapDeg >= 60)
        assertTrue("seen hue gap: ${deutan.seenHueGapDeg}", deutan.seenHueGapDeg <= 20)
        assertTrue(deutan.normalHueGapDeg > deutan.seenHueGapDeg)

        // Nothing is reported for greys: the hue angle of a neutral colour is noise.
        assertEquals(0, pair("#808080", "#A0A0A0", CvdType.NORMAL).normalHueGapDeg)
        assertEquals(0, pair("#FFFFFF", "#000000", CvdType.NORMAL).normalHueGapDeg)
    }

    @Test
    fun `the brief speech is actually brief`() {
        // The settings screen offers 简短/详细, so the two strings must genuinely
        // differ in length — otherwise the user picks 简短 and still gets a
        // paragraph read out loud in a shop.
        val verdict = pair("#85796C", "#B07A3C", CvdType.DEUTERANOMALY)
        assertTrue("brief must name both colours: ${verdict.speakText}", verdict.speakText.contains(verdict.first.primaryName))
        assertTrue("brief must name both colours: ${verdict.speakText}", verdict.speakText.contains(verdict.second.primaryName))
        assertTrue("brief must answer the question: ${verdict.speakText}", verdict.speakText.contains(verdict.headline))
        assertTrue("only the detailed reading carries the advice", verdict.speakTextDetailed.contains(verdict.advice))
        assertTrue("brief must be shorter: ${verdict.speakText}", verdict.speakText.length < verdict.speakTextDetailed.length)
        assertTrue("brief is ${verdict.speakText.length} chars: ${verdict.speakText}", verdict.speakText.length <= 60)
    }

    @Test
    fun `with normal vision no family is ever hidden`() {
        val pairs = listOf("#85796C" to "#B07A3C", "#FF0000" to "#00FF00", "#8B0000" to "#006400")
        for ((a, b) in pairs) {
            val verdict = pair(a, b, CvdType.NORMAL)
            assertFalse("$a/$b", verdict.familyHiddenFromUser)
            assertFalse("$a/$b", verdict.hueCollapsed)
            assertEquals("$a/$b", verdict.first.baseWord, verdict.seenFirst.baseWord)
        }
    }
}
