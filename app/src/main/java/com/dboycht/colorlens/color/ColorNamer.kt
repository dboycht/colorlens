package com.dboycht.colorlens.color

/** Warm/cool split, using the Chinese everyday convention (红橙黄暖、蓝紫冷、绿中性). */
enum class Warmth(val label: String) {
    WARM("暖色"),
    NEUTRAL("中性色"),
    COOL("冷色"),
}

/**
 * Everything the app can say about one sampled pixel.
 *
 * Two fields exist purely for the assistive use case and are worth keeping
 * distinct from [primaryName]:
 *
 * - [baseWord] — the basic word (红/蓝/灰…) with **no** modifier. This is what the
 *   user should say out loud when precision does not matter, because the listener
 *   is guaranteed to understand it.
 * - [specificHint] — a narrower word (砖红/藏青…), only filled when it is a closer
 *   match than the basic word. Never the only thing reported.
 */
data class ColorReading(
    val rgb: Rgb8,
    val hex: String,
    val primaryName: String,
    val baseWord: String,
    val modifier: String?,
    val alternatives: List<String>,
    val specificHint: String?,
    val lightnessPct: Int,
    val chromaPct: Int,
    val hueDeg: Float,
    val warmth: Warmth,
    val isNeutral: Boolean,
    /** One plain-language sentence, e.g. "偏暗，颜色比较浓 的红色". */
    val description: String,
    /** What to say to another person, e.g. "深红色（像砖红）". */
    val tellOthers: String,
    /** Short text for TTS, free of markup and of symbols TTS mis-reads. */
    val speakText: String,
    /** Longer TTS text, used when the user asks for detail. */
    val speakTextDetailed: String,
)

/**
 * Turns a pixel into a name a human would recognise.
 *
 * ## Why this is rule-based instead of plain nearest-neighbour
 *
 * The first implementation picked the nearest basic prototype in OKLab. Measured
 * results on real colours (see `ColorNamerTest`) were wrong in a consistent way:
 *
 * | pixel | nearest prototype | why that is wrong |
 * | --- | --- | --- |
 * | `#8B0000` darkred | 棕 (d 0.083 vs 红 0.180) | brown *is* dark red chromatically; only lightness differs |
 * | `#006400` darkgreen | **灰** (d 0.218 vs 绿 0.316) | white/grey sits between dark and light green in OKLab |
 * | `#00FF00` lime | 黄绿 (d 0.153 vs 绿 0.141) | one green prototype cannot cover both dark and lime green |
 *
 * The fix is to separate the two questions a name answers:
 *
 * 1. **Which family?** Decided by *hue* (and, for brown/pink/cream, by the
 *    lightness-and-chroma region that defines them) — never by lightness alone.
 * 2. **How dark/pale/vivid?** Decided by the modifier, measured against the
 *    family's own prototype.
 *
 * That is also how people talk: a dark green is a *dark green*, not a grey.
 *
 * ## Other design rules
 *
 * - **The reported name is always built from a basic word.** A colourblind user
 *   needs to tell *someone else* what they are looking at; an exotic name the
 *   listener has never heard is worse than a coarse but universal one.
 * - **Achromatic pixels go through a lightness ladder**, not the hue branch:
 *   below ~2.2% chroma the hue angle is numerically meaningless (pure grey has an
 *   arbitrary hue).
 * - **Ambiguity is reported, not hidden**: when two hue families are nearly
 *   equidistant the reading says "介于绿和黄绿之间".
 */
object ColorNamer {

    /**
     * Below this OKLCh chroma a colour is treated as grey/black/white.
     *
     * Calibrated, not guessed: 米色 `#F5F5DC` has chroma ≈ 0.032 and must NOT be
     * swallowed by the greyscale ladder ("白" would be wrong), while a true tinted
     * grey like `#E8E8E0` (≈ 0.012) must be.
     */
    const val NEUTRAL_CHROMA = 0.022f

    /**
     * Lightness offsets (OKLab L) that trigger 深/浅 relative to a family reference.
     *
     * 0.08 rather than 0.10 is deliberate: `#8B0000` sits 0.093 below its
     * prototype's lightness. At 0.10 it came out as a bare "红", i.e. the app
     * would have failed to mention that the pixel is dark — the one thing the
     * user most needs to know.
     */
    const val DARK_DELTA = 0.08f
    const val LIGHT_DELTA = 0.10f

    /** Chroma ratios that trigger 鲜 / 灰. */
    const val VIVID_RATIO = 1.30f
    const val DULL_RATIO = 0.55f

    /** A specific word is only offered when it beats the basic word by this much. */
    const val SPECIFIC_MARGIN = 0.005f

    /**
     * A hint must not contradict the reading it accompanies. Raw OKLab distance
     * alone produced nonsense like "浅紫，更像桃红" for a lilac (#D8BFD8): 桃红's
     * prototype happens to sit at a similar lightness and chroma, so it "won" on
     * distance while pointing at a completely different hue. A hint the user
     * cannot sanity-check must at least be the same hue at a similar saturation.
     */
    const val HINT_MAX_HUE_GAP_DEG = 45f
    const val HINT_MAX_CHROMA_GAP = 0.08f

    /** Two hue families closer than this are reported as a blend. */
    const val HUE_BLEND_GAP_DEG = 18f

    // ---- region gates for the families that are not defined by hue alone ------

    /** 米: pale warm near-whites. */
    const val CREAM_MIN_L = 0.85f
    const val CREAM_MAX_C = 0.072f
    const val CREAM_HUE_MIN = 55f
    const val CREAM_HUE_MAX = 110f

    /** 粉: pale reds. The window reaches further towards orange (桃红/肉色) than
     *  towards purple, so a pale lilac (藕荷 #D8BFD8, hue 325) stays in 紫. */
    const val PINK_MIN_L = 0.78f
    const val PINK_MAX_C = 0.11f
    const val PINK_MAX_HUE = 45f
    const val PINK_MIN_HUE = 335f

    /** 棕: a warm hue that is dark or dull.
     *
     * The hue window reaches 88°, **not** 70°: wood, tan, khaki and sand live at
     * 74–86° (white oak 76.8°, oak 75.5°, khaki 80.0°, and the tan actually
     * measured off a real photo of wood grain 78.6°). With a 70° ceiling every
     * wooden surface fell through to 橙 and the app called oak 「灰橙」.
     * Vivid yellows are kept out by chroma instead of by hue (goldenrod
     * 84°/C 0.147 → 深黄, gold 95.3° → 黄).
     */
    const val BROWN_HUE_MIN = 15f
    const val BROWN_HUE_MAX = 88f
    const val BROWN_MAX_C_DARK = 0.13f
    const val BROWN_MAX_L_DARK = 0.70f
    const val BROWN_MAX_C_DULL = 0.10f
    const val BROWN_MAX_L_DULL = 0.88f

    /**
     * A colour within this many degrees of 红's hue is *red that happens to be
     * dark*, not brown. `#8B0000` is 9.2° from the red prototype while real
     * browns (sienna 45°, saddlebrown 53°) are 25°+ away.
     */
    const val BROWN_MIN_RED_DISTANCE = 12f

    /** 紫 vs 品红: same hue, split by chroma/lightness. */
    const val PURPLE_HUE_MIN = 300f
    const val PURPLE_HUE_MAX = 350f
    /** Magenta's own window: 蓝紫 (紫罗兰 #8A2BE2, hue 301) is highly chromatic
     *  too, so chroma alone would call it 品红. */
    const val MAGENTA_HUE_MIN = 313f
    const val MAGENTA_HUE_MAX = 343f
    const val MAGENTA_MIN_CHROMA = 0.23f
    const val MAGENTA_MIN_L = 0.62f
    const val MAGENTA_PALE_MIN_CHROMA = 0.16f

    private val byWord: Map<String, NameEntry> = ColorNameTable.BASIC.associateBy { it.word }

    private fun basic(word: String): NameEntry =
        requireNotNull(byWord[word]) { "colour table is missing the basic word '$word'" }

    private val GREYSCALE = basic("灰")
    private val WHITE = basic("白")
    private val BLACK = basic("黑")
    private val CREAM = basic("米")
    private val PINK = basic("粉")
    private val BROWN = basic("棕")
    private val PURPLE = basic("紫")
    private val MAGENTA = basic("品红")

    private val hueFamilies: List<Pair<NameEntry, Float>> = ColorNameTable.BASIC
        .filter { it.role == FamilyRole.HUE }
        .map { it to it.oklch.hDeg }

    private val redHue: Float = basic("红").oklch.hDeg

    private val specificLabs: List<Pair<NameEntry, Oklab>> =
        ColorNameTable.SPECIFIC.map { it to it.oklab }

    fun read(rgb: Rgb8): ColorReading {
        val lab = ColorMath.srgbToOklab(rgb)
        val lch = lab.toOklch()
        val lightnessPct = ColorMath.percent(lch.l)
        val chromaPct = ColorMath.percent(lch.c / ColorMath.MAX_SRGB_CHROMA)

        if (lch.c < NEUTRAL_CHROMA) {
            return greyscaleReading(rgb, lch, lightnessPct, chromaPct)
        }

        val chosen = selectFamily(lch)
        val familyRef = chosen.entry.oklch
        val modifier = modifierFor(lch, familyRef)
        val primaryName = (modifier ?: "") + chosen.entry.word

        val familyDist = lab.distanceTo(chosen.entry.oklab)
        val specific = specificLabs
            .map { (entry, ref) ->
                val refLch = ref.toOklch()
                Hint(
                    word = entry.word,
                    distance = lab.distanceTo(ref),
                    hueGap = ColorMath.hueDeltaDegrees(lch.hDeg, refLch.hDeg),
                    chromaGap = kotlin.math.abs(lch.c - refLch.c),
                )
            }
            .filter {
                it.distance + SPECIFIC_MARGIN < familyDist &&
                    it.hueGap <= HINT_MAX_HUE_GAP_DEG &&
                    it.chromaGap <= HINT_MAX_CHROMA_GAP
            }
            .minByOrNull { it.distance }
            ?.word

        val alternatives = chosen.blendWith?.let { listOf(it.word) } ?: emptyList()

        val description = buildDescription(lch, familyRef, chosen.entry.word)
        val tonePrefix = tonePrefixFor(lch, familyRef)
        val tellOthers = buildString {
            append(tonePrefix)
            append(chosen.entry.word)
            if (chosen.entry.word !in setOf("黑", "白")) append("色")
            if (specific != null) append("（像").append(specific).append("）")
        }

        val speak = buildString {
            // 色 is always appended: a bare "粉" or "灰" is ambiguous in speech
            // (powder? dust?), and TTS reads them as isolated words.
            append(primaryName).append("色")
            if (specific != null) append("，更像").append(specific)
            if (alternatives.isNotEmpty()) {
                append("，介于").append(chosen.entry.word).append("和")
                    .append(alternatives.first()).append("之间")
            }
            append("。")
        }

        val speakDetailed = buildString {
            append(speak)
            append("明度 ").append(lightnessPct).append("%，")
            append("鲜艳度 ").append(chromaPct).append("%。")
            append(warmthOf(lch.hDeg).label).append("。")
        }

        return ColorReading(
            rgb = rgb,
            hex = rgb.toHex(),
            primaryName = primaryName,
            baseWord = chosen.entry.word,
            modifier = modifier,
            alternatives = alternatives,
            specificHint = specific,
            lightnessPct = lightnessPct,
            chromaPct = chromaPct,
            hueDeg = lch.hDeg,
            warmth = warmthOf(lch.hDeg),
            isNeutral = false,
            description = description,
            tellOthers = tellOthers,
            speakText = speak,
            speakTextDetailed = speakDetailed,
        )
    }

    /** A candidate "更像 X" hint with the distances that decide whether it survives. */
    private data class Hint(
        val word: String,
        val distance: Float,
        val hueGap: Float,
        val chromaGap: Float,
    )

    /** The family decision, kept in one place so every rule is auditable. */
    private data class FamilyChoice(val entry: NameEntry, val blendWith: NameEntry? = null)

    private fun selectFamily(lch: Oklch): FamilyChoice {
        val hue = lch.hDeg
        val chroma = lch.c
        val light = lch.l

        // 米 — pale warm near-white. Checked before brown, otherwise light tans
        // (wheat #F5DEB3) fall into the brown branch and read as "浅棕".
        if (light > CREAM_MIN_L && chroma < CREAM_MAX_C &&
            hue >= CREAM_HUE_MIN && hue <= CREAM_HUE_MAX
        ) {
            return FamilyChoice(CREAM)
        }

        // 粉 — pale red.
        if (light > PINK_MIN_L && chroma < PINK_MAX_C &&
            (hue <= PINK_MAX_HUE || hue >= PINK_MIN_HUE)
        ) {
            return FamilyChoice(PINK)
        }

        // 棕 — brown has no hue of its own: it is a warm hue made dark or dull.
        // The distance from 红's hue is what keeps dark *reds* red. Two ways in,
        // both measured (tests pin each boundary):
        //  - dull: low chroma below L 0.88 — wood, tan, khaki, greige. This is
        //    the clause that keeps 木纹 out of 橙;
        //  - dark: darker but more chromatic — sienna, leather, peru. The
        //    lightness ceiling is what keeps carrots (L 0.72+) and pumpkins 橙.
        val farFromRed = ColorMath.hueDeltaDegrees(hue, redHue) > BROWN_MIN_RED_DISTANCE
        val dullBrown = chroma < BROWN_MAX_C_DULL && light < BROWN_MAX_L_DULL
        val darkBrown = light < BROWN_MAX_L_DARK && chroma < BROWN_MAX_C_DARK
        if (hue >= BROWN_HUE_MIN && hue <= BROWN_HUE_MAX && farFromRed && (dullBrown || darkBrown)) {
            return FamilyChoice(BROWN)
        }

        // 紫 / 品红 — identical hue, separated by chroma (and brightness).
        if (hue >= PURPLE_HUE_MIN && hue <= PURPLE_HUE_MAX) {
            val inMagentaWindow = hue >= MAGENTA_HUE_MIN && hue <= MAGENTA_HUE_MAX
            val isMagenta = inMagentaWindow && (
                chroma >= MAGENTA_MIN_CHROMA ||
                    (light >= MAGENTA_MIN_L && chroma >= MAGENTA_PALE_MIN_CHROMA)
                )
            return FamilyChoice(if (isMagenta) MAGENTA else PURPLE)
        }

        // Everything else: nearest by hue angle. Lightness is deliberately absent
        // here — it is what the modifier is for.
        val ranked = hueFamilies
            .sortedBy { (_, familyHue) -> ColorMath.hueDeltaDegrees(hue, familyHue) }
        val best = ranked[0]
        val runnerUp = ranked[1]
        val gap = ColorMath.hueDeltaDegrees(hue, runnerUp.second) -
            ColorMath.hueDeltaDegrees(hue, best.second)
        val blend = if (gap < HUE_BLEND_GAP_DEG) runnerUp.first else null
        return FamilyChoice(best.first, blend)
    }

    /** The greyscale ladder. Kept separate so the thresholds are auditable at a glance. */
    private fun greyscaleReading(
        rgb: Rgb8,
        lch: Oklch,
        lightnessPct: Int,
        chromaPct: Int,
    ): ColorReading {
        val l = lch.l
        val baseEntry: NameEntry
        val modifier: String?
        when {
            l < 0.18f -> { baseEntry = BLACK; modifier = null }
            l < 0.40f -> { baseEntry = GREYSCALE; modifier = "深" }
            l < 0.72f -> { baseEntry = GREYSCALE; modifier = null }
            // 0.94, not 0.90: #E0E0E0 has OKLab L = 0.906 and is plainly 浅灰,
            // not 白. Both sides of this line are pinned by tests.
            l < 0.94f -> { baseEntry = GREYSCALE; modifier = "浅" }
            else -> { baseEntry = WHITE; modifier = null }
        }
        val baseWord = baseEntry.word
        val primaryName = (modifier ?: "") + baseWord
        val tellOthers = when {
            baseWord == "黑" -> "黑色"
            baseWord == "白" -> "白色"
            modifier == null -> "灰色"
            else -> modifier + "灰色"
        }
        val speak = tellOthers + "。"
        val description = when (baseWord) {
            "黑" -> "很暗，几乎没有颜色"
            "白" -> "很亮，几乎没有颜色"
            else -> "几乎没有颜色，$tellOthers"
        }
        return ColorReading(
            rgb = rgb,
            hex = rgb.toHex(),
            primaryName = primaryName,
            baseWord = baseWord,
            modifier = modifier,
            alternatives = emptyList(),
            specificHint = null,
            lightnessPct = lightnessPct,
            chromaPct = chromaPct,
            hueDeg = lch.hDeg,
            warmth = Warmth.NEUTRAL,
            isNeutral = true,
            description = description,
            tellOthers = tellOthers,
            speakText = speak,
            speakTextDetailed = "$speak" + "明度 $lightnessPct%。",
        )
    }

    private fun modifierFor(lch: Oklch, ref: Oklch): String? {
        val vivid = lch.c >= ref.c * 0.8f
        return when {
            lch.l < ref.l - DARK_DELTA -> if (vivid) "深" else "暗"
            lch.l > ref.l + LIGHT_DELTA -> if (vivid) "亮" else "浅"
            lch.c > ref.c * VIVID_RATIO -> "鲜"
            lch.c < ref.c * DULL_RATIO -> "灰"
            else -> null
        }
    }

    /** A word that can be spoken as "深红色" / "偏亮的绿色". */
    private fun tonePrefixFor(lch: Oklch, ref: Oklch): String = when {
        lch.l < ref.l - DARK_DELTA -> if (lch.c >= ref.c * 0.8f) "深" else "暗"
        lch.l > ref.l + LIGHT_DELTA -> if (lch.c >= ref.c * 0.8f) "亮" else "浅"
        lch.c > ref.c * VIVID_RATIO -> "鲜艳的"
        lch.c < ref.c * DULL_RATIO -> "发灰的"
        else -> ""
    }

    private fun buildDescription(lch: Oklch, ref: Oklch, word: String): String {
        val light = when {
            lch.l < 0.30f -> "很暗"
            lch.l < 0.45f -> "偏暗"
            lch.l < 0.65f -> "明暗适中"
            lch.l < 0.82f -> "偏亮"
            else -> "很亮"
        }
        val vivid = when {
            lch.c < 0.05f -> "几乎没有颜色"
            lch.c < 0.11f -> "颜色很淡"
            lch.c < 0.19f -> "颜色适中"
            lch.c < 0.26f -> "颜色比较浓"
            else -> "颜色非常浓"
        }
        val note = if (lch.c < ref.c * DULL_RATIO) "，比常见的${word}色灰一些" else ""
        return "$light，$vivid 的${word}色$note".replace(" 的", "的")
    }

    fun warmthOf(hueDeg: Float): Warmth = when {
        hueDeg < 70f || hueDeg >= 330f -> Warmth.WARM
        hueDeg < 170f -> Warmth.NEUTRAL
        else -> Warmth.COOL
    }
}
