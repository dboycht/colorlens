package com.dboycht.colorlens.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming expectations, each one traceable to a human judgement rather than to
 * whatever the code happened to do. Where the honest answer is "it depends", the
 * test asserts the *blend* instead of silently accepting either word.
 */
class ColorNamerTest {

    private fun read(hex: String) = ColorNamer.read(requireNotNull(Rgb8.parseHex(hex)))

    // ---------------------------------------------------------------- neutrals

    @Test
    fun `greyscale ladder names the ends and the middles`() {
        assertEquals("黑", read("#000000").primaryName)
        assertEquals("深灰", read("#303030").primaryName)
        assertEquals("灰", read("#808080").primaryName)
        assertEquals("浅灰", read("#E0E0E0").primaryName)
        assertEquals("白", read("#FFFFFF").primaryName)
    }

    @Test
    fun `white starts where it really stops being grey`() {
        // #F5F5F5 reads as white to anyone; #E0E0E0 does not. The boundary is
        // OKLab L ≈ 0.94 and both sides are pinned here so a threshold tweak
        // cannot quietly move the line.
        assertEquals("白", read("#F5F5F5").primaryName)
        assertEquals("浅灰", read("#E0E0E0").primaryName)
    }

    @Test
    fun `near-neutral greys stay grey instead of stealing a hue`() {
        val grey = read("#E8E8E0")
        assertTrue("a faint warm tint must not become a hue", grey.isNeutral)
        assertEquals("灰", grey.baseWord)
    }

    @Test
    fun `beige is a colour not a white`() {
        // #F5F5DC (CSS beige) has chroma just above the neutral threshold: naming
        // it 白 would be wrong, and this is the test that pins the threshold.
        val beige = read("#F5F5DC")
        assertEquals(false, beige.isNeutral)
        assertEquals("米", beige.baseWord)
    }

    // --------------------------------------------------------------- primaries

    @Test
    fun `the reference colours name themselves with no modifier`() {
        for (entry in ColorNameTable.ALL) {
            val reading = ColorNamer.read(entry.rgb)
            when (entry.tier) {
                NameTier.BASIC -> {
                    assertEquals("basic ${entry.word}", entry.word, reading.baseWord)
                    assertNull("basic ${entry.word} must not be modified", reading.modifier)
                }
                NameTier.SPECIFIC -> assertEquals(
                    "specific ${entry.word} is unreachable: no colour ever reports it as the closest " +
                        "specific word, so it is dead weight in the table",
                    entry.word,
                    reading.specificHint,
                )
            }
        }
    }

    @Test
    fun `dark red reads as a dark red not as brown`() {
        // This is the test that forced the prototypes away from the CSS extremes.
        // With #FF0000 as the 红 reference, #8B0000 was numerically closer to 棕
        // (0.139 vs 0.307 in OKLab) and the app said "棕" about a pure dark red.
        val r = read("#8B0000")
        assertEquals("红", r.baseWord)
        assertTrue("modifier was ${r.modifier}", r.modifier in listOf("深", "暗"))
    }

    @Test
    fun `bright green reads as bright not as pale`() {
        // #00FF00 is very light and fully saturated: "亮绿" is what people say,
        // "浅绿" would imply washed out. It also sits between 绿 and 黄绿, so a
        // blend note is expected rather than a bare 绿.
        val r = read("#00FF00")
        assertEquals("绿", r.baseWord)
        assertEquals("亮", r.modifier)
        assertTrue("expected a 黄绿 blend note, got ${r.alternatives}", r.alternatives.contains("黄绿"))
    }

    @Test
    fun `washed out red is reported as greyed out`() {
        // #B08080 is a dusty rose. The important part is that the app says the
        // colour is muted — calling it "鲜红" would be actively misleading.
        val r = read("#B08080")
        assertEquals("红", r.baseWord)
        assertEquals("灰", r.modifier)
    }

    @Test
    fun `dark green is a dark green not a black`() {
        // 深绿≈黑 is a classic red-green colour blindness confusions; the name must
        // still come from the green family with a darkness modifier.
        val r = read("#006400")
        assertEquals("绿", r.baseWord)
        assertTrue("modifier was ${r.modifier}", r.modifier in listOf("深", "暗"))
    }

    // ------------------------------------------------------------ assistive bits

    @Test
    fun `a narrow word is offered in addition to a basic word`() {
        // Indigo: no basic word fits well, so the user gets 靛蓝 *plus* whichever
        // basic word is closest. A specific word alone would be useless to a
        // listener who does not know it.
        val r = read("#4B0082")
        assertEquals("靛蓝", r.specificHint)
        assertTrue(
            "a basic word must always be reported as well, got '${r.baseWord}'",
            ColorNameTable.basicWords().contains(r.baseWord),
        )
    }

    @Test
    fun `a hint never points at a different hue`() {
        // Regression: raw OKLab distance once produced "浅紫，更像桃红" for lilac
        // #D8BFD8 and "灰红，更像砖红" for dusty rose #B08080 — hints that point
        // somewhere else entirely are worse than no hint, because the user cannot
        // check them.
        val lilac = read("#D8BFD8")
        assertEquals("紫", lilac.baseWord)
        assertTrue("lilac must not be hinted as 桃红", lilac.specificHint != "桃红")
    }

    @Test
    fun `every hint stays in the same hue and saturation neighbourhood`() {
        val probes = listOf(
            "#FF0000", "#8B0000", "#CD6227", "#62102E", "#FFC0CB", "#F0ADA0", "#B08080",
            "#FF00FF", "#FFA500", "#FF4500", "#FFD700", "#FFFF00", "#D6A01D", "#F0E68C",
            "#9ACD32", "#00FF00", "#20A162", "#2E8B57", "#006400", "#556B2F", "#6B8E23",
            "#00CED1", "#00BFFF", "#87CEEB", "#4169E1", "#0000FF", "#000080", "#4B0082",
            "#800080", "#8A2BE2", "#D8BFD8", "#A52A2A", "#D2691E", "#F5F5DC", "#F7C173",
        )
        var hints = 0
        for (hex in probes) {
            val r = read(hex)
            val hint = r.specificHint ?: continue
            hints++
            val entry = ColorNameTable.SPECIFIC.first { it.word == hint }
            val hueGap = ColorMath.hueDeltaDegrees(r.hueDeg, entry.oklch.hDeg)
            val chromaGap = kotlin.math.abs(ColorMath.srgbToOklab(requireNotNull(Rgb8.parseHex(hex))).toOklch().c - entry.oklch.c)
            assertTrue("$hex → 更像$hint crosses ${hueGap.toInt()}° of hue", hueGap <= ColorNamer.HINT_MAX_HUE_GAP_DEG + 0.01f)
            assertTrue("$hex → 更像$hint differs in chroma by $chromaGap", chromaGap <= ColorNamer.HINT_MAX_CHROMA_GAP + 0.001f)
        }
        assertTrue("this probe list is supposed to produce hints, got $hints", hints >= 8)
    }

    @Test
    fun `tell-others text is always a word a stranger understands`() {
        val basics = ColorNameTable.basicWords()
        for (hex in listOf("#8B0000", "#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#808080", "#FFFFFF", "#F9E9CD", "#4B0082")) {
            val r = read(hex)
            assertTrue(
                "tellOthers of $hex was '${r.tellOthers}' and must contain a basic word",
                basics.any { r.tellOthers.contains(it) },
            )
        }
    }

    @Test
    fun `spoken text carries no markup and ends a sentence`() {
        for (hex in listOf("#8B0000", "#FF0000", "#404040", "#FFFFFF", "#00CED1", "#4B0082")) {
            val r = read(hex)
            assertTrue(
                "speakText must not contain markdown: ${r.speakText}",
                !r.speakText.contains("*") && !r.speakText.contains("#") && !r.speakText.contains("`"),
            )
            assertTrue("speakText should end a sentence: ${r.speakText}", r.speakText.endsWith("。"))
        }
    }

    // ------------------------------------------------- semantic full coverage

    /**
     * The "structurally legal, but it does not work" guard (workspace memory/24
     * §12): a hand-written vocabulary can silently contain words no input ever
     * produces, and nothing else notices. This sweeps a coarse RGB cube to prove
     * every basic word is reachable by *some* real colour;
     * `the reference colours name themselves` above covers the specific words.
     */
    @Test
    fun `every basic word is produced by some colour in the cube`() {
        val seen = mutableSetOf<String>()
        var samples = 0
        for (r in 0..255 step 16) {
            for (g in 0..255 step 16) {
                for (b in 0..255 step 16) {
                    seen.add(ColorNamer.read(Rgb8(r, g, b)).baseWord)
                    samples++
                }
            }
        }
        assertTrue("expected a real sweep, only $samples samples", samples > 4000)
        val missing = ColorNameTable.basicWords().filterNot { it in seen }
        assertEquals("basic words never produced by any colour: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `every family role is used by at least one word`() {
        // A role with no word means a whole selection branch can never fire, i.e.
        // dead code that no other test would notice.
        val empty = FamilyRole.entries.filter { role -> ColorNameTable.BASIC.none { it.role == role } }
        assertEquals("family roles with no word: $empty", emptyList<FamilyRole>(), empty)
    }

    @Test
    fun `basic words are unique and include every word the selector needs`() {
        val words = ColorNameTable.BASIC.map { it.word }
        val duplicates = words.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertEquals("duplicate basic words: $duplicates", words.size, words.toSet().size)
        // ColorNamer looks these up by name and fails loudly if one is missing;
        // this test is the cheap early warning.
        for (required in listOf("红", "橙", "黄", "黄绿", "绿", "青绿", "天蓝", "蓝", "紫", "品红", "粉", "棕", "米", "灰", "白", "黑")) {
            assertNotNull("basic word missing from the table: $required", ColorNameTable.BASIC.firstOrNull { it.word == required })
        }
    }

    @Test
    fun `every colour gets a complete reading with all display fields populated`() {
        for (r in 0..255 step 51) {
            for (g in 0..255 step 51) {
                for (b in 0..255 step 51) {
                    val reading = ColorNamer.read(Rgb8(r, g, b))
                    assertTrue("lightness out of range", reading.lightnessPct in 0..100)
                    assertTrue("chroma out of range", reading.chromaPct in 0..100)
                    assertTrue("empty description", reading.description.isNotEmpty())
                    assertTrue("empty tellOthers", reading.tellOthers.isNotEmpty())
                    assertTrue("empty warmth", reading.warmth.label.isNotEmpty())
                }
            }
        }
    }
}
