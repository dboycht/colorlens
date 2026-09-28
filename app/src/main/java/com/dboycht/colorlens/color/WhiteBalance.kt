package com.dboycht.colorlens.color

/**
 * White balance from a reference the user supplies — "point at a sheet of white
 * paper and tap it".
 *
 * ## Why this exists
 *
 * Auto white balance is the camera *guessing* what the light looks like. Under a
 * warm indoor lamp it guesses "the scene is warm" and the whole photo shifts
 * towards orange, which is exactly the failure mode of every tap-to-sample
 * colour app. Letting the user tap something they *know* is white replaces the
 * guess with a measurement.
 *
 * ## What the correction does
 *
 * The reference is turned into a neutral grey with **the same perceived
 * lightness** (OKLab L, so the photo does not get brighter or darker overall),
 * and the per-channel ratios that achieve that are applied to the whole image.
 *
 * Two guard rails, both measured in tests:
 *
 * - a reference that is too dark or too colourful is rejected with a reason,
 *   because stretching a coloured surface to neutral would repaint the photo;
 * - the gains themselves are clamped, so a pathological reference (one channel
 *   almost zero) cannot blow the image apart.
 *
 * Gains are applied in **display sRGB**, which is what Android's `ColorMatrix`
 * works in — deliberately not the linear-light space used for the CVD matrices.
 */
object WhiteBalance {

    /** Below this OKLab lightness the reference is too dark to be trusted. */
    const val MIN_LIGHTNESS = 0.55f

    /** Above this OKLab chroma the reference is a *colour*, not a white/grey. */
    const val MAX_REFERENCE_CHROMA = 0.085f

    const val MIN_GAIN = 0.60f
    const val MAX_GAIN = 1.70f

    /** Per-channel multipliers applied to the photo's sRGB values. */
    data class Gains(val r: Float, val g: Float, val b: Float) {

        /** True when the reference needed no correction at all. */
        val isIdentity: Boolean
            get() = kotlin.math.abs(r - 1f) < 1e-3f &&
                kotlin.math.abs(g - 1f) < 1e-3f &&
                kotlin.math.abs(b - 1f) < 1e-3f

        /** The same correction for a single colour, clamped into the displayable range. */
        fun applied(rgb: Rgb8): Rgb8 = Rgb8(
            (rgb.r * r).toInt().coerceIn(0, 255),
            (rgb.g * g).toInt().coerceIn(0, 255),
            (rgb.b * b).toInt().coerceIn(0, 255),
        )
    }

    sealed interface Evaluation {
        /** Usable: these gains make the reference neutral. */
        data class Usable(val gains: Gains) : Evaluation

        /** Not usable; [reason] is written for the user, not for the log. */
        data class Unusable(val reason: String) : Evaluation
    }

    /**
     * Decides whether [reference] can serve as a white/grey reference, and what
     * correction it implies. Pure logic — no Android types — so the boundaries
     * are pinned by unit tests.
     */
    fun evaluate(reference: Rgb8): Evaluation {
        val lch = ColorMath.srgbToOklab(reference).toOklch()
        // Chroma is checked first on purpose: a coloured surface is unusable as a
        // white reference whatever its lightness, and "this point is a colour, not
        // white" is the more useful diagnosis than "this point is dark" (a mid red
        // is both, and the user needs to hear the first one).
        if (lch.c > MAX_REFERENCE_CHROMA) {
            return Evaluation.Unusable("这个点颜色太重，不是白色或浅灰色，换一处再点。")
        }
        if (lch.l < MIN_LIGHTNESS) {
            return Evaluation.Unusable("这个点太暗了，找白纸或浅灰色的地方再点一次。")
        }
        val neutral = ColorMath.oklabToSrgb(Oklab(lch.l, 0f, 0f))
        if (neutral.r == 0 || neutral.g == 0 || neutral.b == 0) {
            return Evaluation.Unusable("这个点太暗了，找白纸或浅灰色的地方再点一次。")
        }
        val gains = Gains(
            r = (neutral.r.toFloat() / reference.r).coerceIn(MIN_GAIN, MAX_GAIN),
            g = (neutral.g.toFloat() / reference.g).coerceIn(MIN_GAIN, MAX_GAIN),
            b = (neutral.b.toFloat() / reference.b).coerceIn(MIN_GAIN, MAX_GAIN),
        )
        return Evaluation.Usable(gains)
    }
}
