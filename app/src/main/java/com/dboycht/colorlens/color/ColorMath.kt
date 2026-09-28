package com.dboycht.colorlens.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * OKLab — a perceptually uniform colour space (Björn Ottosson, 2020).
 *
 * Why not plain RGB or HSV: two colours with the same RGB distance can look
 * wildly different, and HSV "value" is not perceived lightness. Naming a colour
 * and judging whether two colours are distinguishable both need a space where
 * *equal numeric distance ≈ equal perceived difference*.
 *
 * Coefficients are the published OKLab ones. They are exercised by
 * `ColorMathTest` (white/black/primaries round-trip) so a typo cannot hide.
 */
data class Oklab(val l: Float, val a: Float, val b: Float) {

    /** Euclidean distance. Roughly: 0.02 ≈ just noticeable, 0.10 ≈ clearly different. */
    fun distanceTo(other: Oklab): Float {
        val dl = l - other.l
        val da = a - other.a
        val db = b - other.b
        return sqrt(dl * dl + da * da + db * db)
    }

    fun toOklch(): Oklch {
        val c = sqrt(a * a + b * b)
        val h = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()
        return Oklch(l = l, c = c, hDeg = if (h < 0f) h + 360f else h)
    }
}

/** Cylindrical form of [Oklab]: lightness, chroma, hue angle in degrees [0,360). */
data class Oklch(val l: Float, val c: Float, val hDeg: Float)

object ColorMath {

    /** Highest chroma reachable in sRGB (about #FF00FF) — used to show a 0-100% scale. */
    const val MAX_SRGB_CHROMA = 0.32f

    fun srgbToLinear(channel8: Int): Float {
        val c = channel8 / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(x: Float): Float {
        val c = if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return c
    }

    fun srgbToOklab(rgb: Rgb8): Oklab {
        val r = srgbToLinear(rgb.r)
        val g = srgbToLinear(rgb.g)
        val b = srgbToLinear(rgb.b)
        return linearToOklab(r, g, b)
    }

    /** Linear-light sRGB (0..1 per channel, may exceed 1 after simulation) to OKLab. */
    fun linearToOklab(r: Float, g: Float, b: Float): Oklab {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val l_ = cbrt(l)
        val m_ = cbrt(m)
        val s_ = cbrt(s)

        return Oklab(
            l = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            a = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            b = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_,
        )
    }

    /** OKLab back to 8-bit sRGB, clamping to the gamut. */
    fun oklabToSrgb(lab: Oklab): Rgb8 = oklabToLinear(lab).let { (r, g, b) ->
        Rgb8(
            r = channelTo8(r),
            g = channelTo8(g),
            b = channelTo8(b),
        )
    }

    /** OKLab to linear-light sRGB (no clamping) — the simulation pipeline works here. */
    fun oklabToLinear(lab: Oklab): Triple<Float, Float, Float> {
        val l_ = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
        val m_ = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
        val s_ = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b

        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        return Triple(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
        )
    }

    fun channelTo8(linear: Float): Int {
        val encoded = linearToSrgb(linear)
        return (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** WCAG relative luminance (used only for on-screen legibility, not naming). */
    fun relativeLuminance(rgb: Rgb8): Float =
        0.2126f * srgbToLinear(rgb.r) + 0.7152f * srgbToLinear(rgb.g) + 0.0722f * srgbToLinear(rgb.b)

    /** WCAG contrast ratio between two opaque colours, 1.0 .. 21.0. */
    fun contrastRatio(a: Rgb8, b: Rgb8): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** Circular hue difference in degrees, always 0..180. */
    fun hueDeltaDegrees(h1: Float, h2: Float): Float {
        val d = abs(h1 - h2) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Convenience for the UI: "0.51" -> 51 (percent), clamped. */
    fun percent(value: Float): Int = (value * 100f + 0.5f).toInt().coerceIn(0, 100)
}
