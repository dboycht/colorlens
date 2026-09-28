package com.dboycht.colorlens.color

import kotlin.math.roundToInt

/**
 * Answers the question the app exists for: **"这两个颜色我分得出来吗？"**
 *
 * The verdict is deliberately about *the user's* vision, not the abstract colour
 * difference. A pair can be trivially different to a trichromat and nearly
 * identical to a deuteranope; saying "区别明显" in that case would be a lie that
 * costs the user real money (buying the wrong shirt) or real safety (misreading a
 * wire colour). So the app always reports:
 *
 * 1. what **the user** will see ([Verdict.seenDistance]),
 * 2. what **other people** see ([Verdict.normalDistance]), and
 * 3. a concrete coping cue ([Verdict.advice]) — usually lightness, which almost
 *    every colour-vision deficiency preserves.
 */
object ColorCompare {

    /**
     * How far apart two colours are, in OKLab units. 0.02 is roughly one
     * just-noticeable difference for adjacent patches on a good screen, which is
     * the standard used by the thresholds below.
     */
    const val JND = 0.02f

    /** A colour difference must be at least this large before it counts as "having a hue to lose". */
    private const val CHROMA_COLLAPSE_MIN = 0.10f

    /** ...and this much of it must be gone. */
    private const val CHROMA_COLLAPSE_RATIO = 0.35f

    /** Below this chroma an OKLCh hue angle is numerical noise, so it is not used at all. */
    private const val HUE_ANGLE_MIN_CHROMA = 0.03f

    private const val HUE_ANGLE_COLLAPSE_MIN_DEG = 25f
    private const val HUE_ANGLE_COLLAPSE_MAX_DEG = 12f
    private const val HUE_ANGLE_COLLAPSE_FRACTION = 0.4f

    /** Bands of perceptual distance, coarsest to finest. */
    enum class Band(val label: String) {
        IDENTICAL("几乎一样"),
        VERY_CLOSE("很接近"),
        CLOSE("有点接近"),
        DIFFERENT("能分清"),
        VERY_DIFFERENT("差很多"),
    }

    data class Verdict(
        val first: ColorReading,
        val second: ColorReading,
        /** What each colour looks like to the user — usually a different name. */
        val seenFirst: ColorReading,
        val seenSecond: ColorReading,
        val type: CvdType,
        /** Distance a trichromat sees. */
        val normalDistance: Float,
        /** Distance the user sees under [type]. */
        val seenDistance: Float,
        /** The same two distances with lightness removed — "how different in hue". */
        val normalChromaticDistance: Float,
        val seenChromaticDistance: Float,
        /** Hue-angle difference in degrees, 0 when a colour is too grey for hue to mean anything. */
        val normalHueGapDeg: Int,
        val seenHueGapDeg: Int,
        /** Difference in lightness, in percentage points of OKLab L. */
        val lightnessGapPct: Int,
        val sameFamily: Boolean,
        val band: Band,
        /**
         * True when the two colours still differ (mostly in lightness) but their
         * *hue* has collapsed for this user. This is the red/green case: a
         * deuteranope can often still see that two patches differ, because one is
         * darker — but the difference no longer carries the colour information
         * everybody else is using.
         */
        val hueCollapsed: Boolean,
        /**
         * True when the two colours belong to different colour families for
         * everybody else, but the same family for the user — e.g. 灰棕 vs 灰橙
         * both read as 暗黄. Stronger and more concrete than [hueCollapsed]: it
         * says *which* piece of information is gone, not just that some is.
         */
        val familyHiddenFromUser: Boolean,
        /** True when other people can tell them apart much more easily than the user. */
        val hiddenFromUser: Boolean,
        /** One sentence answer to "我分得出来吗". */
        val headline: String,
        /** What to actually do: which cue to rely on. */
        val advice: String,
        val speakText: String,
        val speakTextDetailed: String,
    )

    /**
     * @param sensitivity scales every threshold. 1.0 is the population average;
     *   >1 warns earlier (cautious), <1 complains less. See `CompareSensitivity`.
     */
    fun bandFor(distance: Float, sensitivity: Float = 1f): Band {
        val s = sensitivity.coerceIn(0.5f, 2f)
        return when {
            distance < JND * s -> Band.IDENTICAL
            distance < 0.05f * s -> Band.VERY_CLOSE
            distance < 0.10f * s -> Band.CLOSE
            distance < 0.20f * s -> Band.DIFFERENT
            else -> Band.VERY_DIFFERENT
        }
    }

    fun compare(
        first: Rgb8,
        second: Rgb8,
        type: CvdType,
        severity: Float = type.severity,
        sensitivity: Float = 1f,
    ): Verdict =
        compare(ColorNamer.read(first), ColorNamer.read(second), type, severity, sensitivity)

    fun compare(
        first: ColorReading,
        second: ColorReading,
        type: CvdType,
        severity: Float = type.severity,
        sensitivity: Float = 1f,
    ): Verdict {
        val seenA = VisionSimulator.simulate(first.rgb, type, severity)
        val seenB = VisionSimulator.simulate(second.rgb, type, severity)

        val labA = ColorMath.srgbToOklab(first.rgb)
        val labB = ColorMath.srgbToOklab(second.rgb)
        val seenLabA = ColorMath.srgbToOklab(seenA)
        val seenLabB = ColorMath.srgbToOklab(seenB)

        val normalDistance = labA.distanceTo(labB)
        val seenDistance = seenLabA.distanceTo(seenLabB)
        val normalChromatic = chromatic(labA, labB)
        val seenChromatic = chromatic(seenLabA, seenLabB)

        // Naming the simulated colours is what makes "you are seeing something
        // different" concrete instead of abstract: 灰棕/灰橙 both being read as
        // 暗黄 is a fact the user can check against their own eyes.
        val seenFirst = ColorNamer.read(seenA)
        val seenSecond = ColorNamer.read(seenB)

        val normalHueGap = hueGapOf(labA, labB)
        val seenHueGap = hueGapOf(seenLabA, seenLabB)

        val lightnessGapPct = kotlin.math.abs(first.lightnessPct - second.lightnessPct)
        val sameFamily = first.baseWord == second.baseWord
        val seenSameFamily = seenFirst.baseWord == seenSecond.baseWord
        val band = bandFor(seenDistance, sensitivity)

        // Two ways of losing the hue: the measured colour difference collapses, or
        // the hue angle itself closes up. The angle test is what catches muted
        // colours (earthy browns, olive greens), where the total colour difference
        // was never large enough to trip the measured test but the hue information
        // is just as gone.
        val lostByMeasurement = normalChromatic >= CHROMA_COLLAPSE_MIN &&
            seenChromatic < normalChromatic * CHROMA_COLLAPSE_RATIO
        val lostByAngle = normalChromatic >= HUE_ANGLE_MIN_CHROMA &&
            normalHueGap >= HUE_ANGLE_COLLAPSE_MIN_DEG &&
            seenHueGap <= maxOf(HUE_ANGLE_COLLAPSE_MAX_DEG, normalHueGap * HUE_ANGLE_COLLAPSE_FRACTION)

        // The name-based test: everybody else names two different colour families,
        // the user's own eyes produce one. This needs no threshold tuning at all.
        val familyHiddenFromUser = !type.isNormal && !sameFamily && seenSameFamily

        val hueCollapsed = !type.isNormal &&
            (lostByMeasurement || lostByAngle || familyHiddenFromUser)

        // "其它人看得到，而你看不到" — the gap that most needs saying out loud.
        val hiddenFromUser = !type.isNormal &&
            normalDistance >= JND * 2f &&
            seenDistance < JND * 2f

        val headline = headlineFor(band, hueCollapsed, familyHiddenFromUser, lightnessGapPct, type)
        val advice = adviceFor(
            band = band,
            lightnessGapPct = lightnessGapPct,
            sameFamily = sameFamily,
            hiddenFromUser = hiddenFromUser,
            hueCollapsed = hueCollapsed,
            familyHiddenFromUser = familyHiddenFromUser,
            type = type,
            first = first,
            second = second,
            seenFirst = seenFirst,
        )

        // Two speech lengths, and they must actually differ in length: the brief
        // one answers "我分得出来吗" and stops, the detailed one adds the reasoning
        // and the coping cue. Reading the whole advice out loud by default would
        // make the app unusable in public.
        val speak = buildString {
            append("A 是").append(first.primaryName).append("，B 是").append(second.primaryName).append("。")
            append(headline).append('。')
        }

        val detailed = buildString {
            append(speak)
            append(advice)
            append("明暗差 ").append(lightnessGapPct).append("%。")
        }

        return Verdict(
            first = first,
            second = second,
            seenFirst = seenFirst,
            seenSecond = seenSecond,
            type = type,
            normalDistance = normalDistance,
            seenDistance = seenDistance,
            normalChromaticDistance = normalChromatic,
            seenChromaticDistance = seenChromatic,
            normalHueGapDeg = normalHueGap.roundToInt(),
            seenHueGapDeg = seenHueGap.roundToInt(),
            lightnessGapPct = lightnessGapPct,
            sameFamily = sameFamily,
            band = band,
            hueCollapsed = hueCollapsed,
            familyHiddenFromUser = familyHiddenFromUser,
            hiddenFromUser = hiddenFromUser,
            headline = headline,
            advice = advice,
            speakText = speak,
            speakTextDetailed = detailed,
        )
    }

    /** OKLab distance with lightness removed: the part a hue confusion destroys. */
    private fun chromatic(a: Oklab, b: Oklab): Float =
        kotlin.math.hypot(a.a - b.a, a.b - b.b)

    /**
     * Hue-angle difference in degrees, or 0 when either colour is too close to
     * grey for a hue angle to be meaningful — the angle of a near-neutral colour is
     * numerical noise, and reporting "180° apart" for two greys would be absurd.
     */
    private fun hueGapOf(a: Oklab, b: Oklab): Float {
        val lchA = a.toOklch()
        val lchB = b.toOklch()
        if (lchA.c < HUE_ANGLE_MIN_CHROMA || lchB.c < HUE_ANGLE_MIN_CHROMA) return 0f
        return ColorMath.hueDeltaDegrees(lchA.hDeg, lchB.hDeg)
    }

    /**
     * The one-line answer.
     *
     * A plain "差别很明显" would be **wrong** for the most common case there is:
     * a deuteranope looking at red and green. They are not close colours to that
     * user — red is much darker than lime green — so a distance-based headline
     * says "obviously different", while the actual colour information (the hue)
     * is gone. Saying both things in one line is the whole job of this function.
     */
    private fun headlineFor(
        band: Band,
        hueCollapsed: Boolean,
        familyHiddenFromUser: Boolean,
        lightnessGapPct: Int,
        type: CvdType,
    ): String {
        if (type.family == CvdFamily.ACHROMATOPSIA) {
            return if (band >= Band.CLOSE) "你只能靠明暗分开这两个" else "明暗也几乎一样，很难分开"
        }
        if (familyHiddenFromUser) {
            // The most useful sentence the app can produce: it names the piece of
            // information the user is missing, instead of only saying that the
            // colours differ. "分得开" and "看得出颜色种类" are two different
            // things, and confusing them is how people end up mis-describing
            // something they saw perfectly well.
            return when {
                band >= Band.DIFFERENT -> "你能分清这两个，但颜色种类的区别看不出来"
                band == Band.CLOSE -> "勉强分得开，颜色种类的区别看不出来"
                else -> "分不太开，颜色种类也看不出来"
            }
        }
        if (hueCollapsed) {
            // Only promise "靠明暗分开" when the lightness gap can actually carry it;
            // otherwise the headline would contradict the advice right below it.
            return when {
                lightnessGapPct >= 12 -> "色相上几乎一样，你主要靠明暗分开"
                band >= Band.CLOSE -> "色相上几乎一样，只能勉强分开"
                else -> "色相一样、明暗也接近，很难分开"
            }
        }
        return when (band) {
            Band.IDENTICAL -> "你看起来几乎是同一个颜色"
            Band.VERY_CLOSE -> "很接近，你可能分不出来"
            Band.CLOSE -> "有点接近，仔细看才能分辨"
            Band.DIFFERENT -> "你能分清这两个颜色"
            Band.VERY_DIFFERENT -> "这两个颜色差别很明显"
        }
    }

    /**
     * The most valuable sentence in the app: not "how different are these", but
     * "what do I do about it". Advice must be actionable and must not pretend the
     * user can see something they cannot.
     *
     * Structure: first say what other people see that the user does not (if
     * anything), then give one concrete cue. Lightness is offered first because it
     * survives almost every deficiency.
     */
    private fun adviceFor(
        band: Band,
        lightnessGapPct: Int,
        sameFamily: Boolean,
        hiddenFromUser: Boolean,
        hueCollapsed: Boolean,
        familyHiddenFromUser: Boolean,
        type: CvdType,
        first: ColorReading,
        second: ColorReading,
        seenFirst: ColorReading,
    ): String {
        val lighter = if (first.lightnessPct >= second.lightnessPct) first else second
        val darker = if (first.lightnessPct >= second.lightnessPct) second else first

        if (type.family == CvdFamily.ACHROMATOPSIA) {
            return when {
                lightnessGapPct >= 12 ->
                    "你只能看到明暗，好在它们差得明显（差 $lightnessGapPct%）：" +
                        "${lighter.primaryName}更亮，${darker.primaryName}更暗，靠这个分辨。"
                lightnessGapPct >= 5 ->
                    "你只能看到明暗，它们差一点点（差 $lightnessGapPct%）：" +
                        "${lighter.primaryName}稍亮，仔细看能分辨。"
                else ->
                    "你只能看到明暗，而它们的明暗也几乎一样，很可能完全分不出来，建议看标签或问别人。"
            }
        }

        val othersSee = when {
            hiddenFromUser -> "色觉正常的人能一眼分开，你看起来却很像。"
            familyHiddenFromUser ->
                "色相上对你几乎没有区别——别人看到的是${first.primaryName}和${second.primaryName}（两种不同的颜色），" +
                    "你眼里它们更像同一类（都偏${seenFirst.baseWord}）。"
            hueCollapsed -> "色相上对你几乎没有区别（色觉正常的人能分清）。"
            else -> ""
        }

        val lightnessCue = when {
            lightnessGapPct >= 12 ->
                "明暗差得比较明显（差 $lightnessGapPct%）：${lighter.primaryName}更亮，${darker.primaryName}更暗，可以靠这个分辨。"
            lightnessGapPct >= 5 ->
                "明暗差一点点（差 $lightnessGapPct%）：${lighter.primaryName}稍亮一些，仔细看能靠明暗分辨。"
            else -> ""
        }

        // The cue offered depends on what the user actually lost. When the hue is
        // gone, lightness is the only thing left worth naming; when it is not,
        // pointing at lightness for two obviously different colours is noise.
        val cue = when {
            // Checked before the generic hue-collapse branch: losing the *family* is
            // the more specific finding, and it comes with its own advice (the two
            // names are what to say out loud, not a cue for the eyes).
            familyHiddenFromUser ->
                lightnessCue.ifEmpty { "明暗也接近，放在一起还看得出一点差别。" } +
                    "分得开不代表看得出颜色种类——要告诉别人颜色，可以用上面的名字：" +
                    "${first.primaryName}、${second.primaryName}。"
            hueCollapsed || band <= Band.VERY_CLOSE ->
                lightnessCue.ifEmpty { "明暗也一样，建议看标签、包装上的文字，或者问别人确认。" }
            band == Band.CLOSE ->
                lightnessCue.ifEmpty { "放在一起对比着看会更明显，也可以看标签确认。" }
            sameFamily ->
                "它们都属于${first.baseWord}色系，只是深浅或鲜灰不同，正常使用中不用特别担心。"
            else ->
                "颜色种类也不一样（${first.primaryName} 和 ${second.primaryName}），正常使用中不用特别担心。"
        }

        return othersSee + cue
    }
}
