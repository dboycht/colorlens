package com.dboycht.colorlens.color

/** Which cone system a deficiency affects. */
enum class CvdFamily(val label: String) {
    NORMAL("正常色觉"),
    PROTAN("红色觉异常"),
    DEUTAN("绿色觉异常"),
    TRITAN("蓝色觉异常"),
    ACHROMATOPSIA("全色盲"),
}

/**
 * The colour-vision types the user can pick from.
 *
 * Eight entries, the set people actually get diagnosed with: 正常 + 红/绿/蓝 各
 * 「弱/盲」+ 全色盲. 「弱」(anomalous trichromacy) keeps some of the cone response
 * and is by far the most common — about 6% of men are 绿色弱 and about 1% 红色弱,
 * while the complete 盲 forms are roughly 1% and 0.01%. 蓝色觉异常 is rare (<0.01%)
 * and is included for completeness.
 */
enum class CvdType(
    val label: String,
    val shortLabel: String,
    val hint: String,
    val family: CvdFamily,
    /** Severity passed to the Machado matrices: 0 = normal, 1 = dichromacy. */
    val severity: Float,
) {
    NORMAL(
        label = "正常色觉",
        shortLabel = "正常",
        hint = "三色觉，不模拟",
        family = CvdFamily.NORMAL,
        severity = 0f,
    ),
    PROTANOMALY(
        label = "红色弱",
        shortLabel = "红弱",
        hint = "红色发暗、红绿容易混",
        family = CvdFamily.PROTAN,
        severity = 0.6f,
    ),
    PROTANOPIA(
        label = "红色盲",
        shortLabel = "红盲",
        hint = "红绿几乎看不出差别，红色很暗",
        family = CvdFamily.PROTAN,
        severity = 1f,
    ),
    DEUTERANOMALY(
        label = "绿色弱",
        shortLabel = "绿弱",
        hint = "最常见的类型，红绿容易混",
        family = CvdFamily.DEUTAN,
        severity = 0.6f,
    ),
    DEUTERANOPIA(
        label = "绿色盲",
        shortLabel = "绿盲",
        hint = "红绿几乎看不出差别",
        family = CvdFamily.DEUTAN,
        severity = 1f,
    ),
    TRITANOMALY(
        label = "蓝色弱",
        shortLabel = "蓝弱",
        hint = "蓝绿、黄蓝容易混",
        family = CvdFamily.TRITAN,
        severity = 0.5f,
    ),
    TRITANOPIA(
        label = "蓝色盲",
        shortLabel = "蓝盲",
        hint = "蓝黄几乎看不出差别",
        family = CvdFamily.TRITAN,
        severity = 1f,
    ),
    ACHROMATOPSIA(
        label = "全色盲",
        shortLabel = "全色盲",
        hint = "只剩明暗",
        family = CvdFamily.ACHROMATOPSIA,
        severity = 1f,
    ),
    ;

    /** True when this type needs no simulation at all. */
    val isNormal: Boolean get() = family == CvdFamily.NORMAL

    companion object {
        /**
         * Stable ids for persistence; the enum name is exactly that.
         *
         * An unreadable id falls back to [NORMAL], not to a deficiency: the app
         * must never *invent* a colour weakness the user did not choose, because
         * the comparison screen would then tell them they cannot tell colours
         * apart. A wrong-but-honest "normal" is recoverable; a silent wrong
         * diagnosis is not.
         */
        fun fromId(id: String?): CvdType = entries.firstOrNull { it.name == id } ?: NORMAL

        /** Order shown in settings: most common first. */
        val CHOICES: List<CvdType> = listOf(
            NORMAL, DEUTERANOMALY, PROTANOMALY, TRITANOMALY,
            DEUTERANOPIA, PROTANOPIA, TRITANOPIA, ACHROMATOPSIA,
        )
    }
}

/**
 * Simulates how a colour looks to someone with a given colour-vision deficiency.
 *
 * Model: **Machado, Oliveira & Fernandes (2009)**, "A Physiologically-based Model
 * for Simulation of Color Vision Deficiency", IEEE TVCG 15(6):1291-1298. The
 * published 3×3 matrices are tabulated at 11 severities and interpolated
 * linearly, exactly as the reference implementations do
 * (colour-science `matrix_cvd_Machado2009`, DaltonLens `Simulator_Machado2009`).
 *
 * Three details that are easy to get wrong and are handled here:
 *
 * 1. **The matrices act on linear-light RGB**, not on gamma-encoded sRGB.
 *    Applying them to encoded values is the classic bug: results come out too dark.
 * 2. **Severities between table rows are interpolated**, so a user at 0.6 绿色弱
 *    gets a real 0.6, not the nearest tabulated 0.6/0.7 step.
 * 3. **Output is clamped to the sRGB gamut.** At high severity the matrices can
 *    push a channel below 0 or above 1 (e.g. Protanomaly 1.0 has +1.0526 in a row
 *    sum), and an unclamped result would wrap around.
 *
 * Known limitation, stated openly: **全色盲 is approximated by linear-light
 * luminance greyscale**. True achromatopsia (rod monochromacy) is blue-shifted,
 * loses acuity, and comes with photophobia and nystagmus — this app simulates
 * only the colour dimension, and the paper itself does not model achromatopsia.
 */
object VisionSimulator {

    /**
     * 0.0 is the identity, so a "weak" simulation at severity 0 must be a no-op.
     * Used by tests and as the neutral element for interpolation.
     */
    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** Decoding LUT: 8-bit sRGB -> linear light. */
    private val DECODE = FloatArray(256) { ColorMath.srgbToLinear(it) }

    /**
     * Encoding LUT: linear light -> 0..255, sampled at 1/1024 and linearly
     * interpolated. `linearToSrgb` uses `pow`, and a full-screen preview calls
     * this three times per pixel; the LUT keeps that off the hot path.
     * `VisionSimulatorTest` checks it against [ColorMath.linearToSrgb].
     */
    private const val ENCODE_STEPS = 1024
    private val ENCODE = IntArray(ENCODE_STEPS + 1) {
        ColorMath.channelTo8(it.toFloat() / ENCODE_STEPS)
    }

    /** Row-major 3×3 for [type] and [severity]; null when no simulation is needed. */
    fun matrixFor(type: CvdType, severity: Float = type.severity): FloatArray? {
        if (type.isNormal) return null
        if (type.family == CvdFamily.ACHROMATOPSIA) return null
        val table = when (type.family) {
            CvdFamily.PROTAN -> CvdMatrices.PROTANOMALY
            CvdFamily.DEUTAN -> CvdMatrices.DEUTERANOMALY
            CvdFamily.TRITAN -> CvdMatrices.TRITANOMALY
            CvdFamily.NORMAL, CvdFamily.ACHROMATOPSIA -> return null
        }
        val clamped = severity.coerceIn(0f, 1f)
        val scaled = clamped * 10f
        val lower = scaled.toInt().coerceIn(0, 10)
        val upper = (lower + 1).coerceAtMost(10)
        val alpha = scaled - lower
        val m1 = table[lower]
        val m2 = table[upper]
        if (lower == upper || alpha <= 0f) return m1.copyOf()
        val out = FloatArray(9)
        for (i in 0 until 9) {
            out[i] = m1[i] + alpha * (m2[i] - m1[i])
        }
        return out
    }

    /** Linear-light RGB after simulation (not clamped to the gamut). */
    fun simulateLinear(r: Float, g: Float, b: Float, type: CvdType, severity: Float = type.severity): Triple<Float, Float, Float> {
        if (type.isNormal) return Triple(r, g, b)
        if (type.family == CvdFamily.ACHROMATOPSIA) {
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            return Triple(y, y, y)
        }
        val m = matrixFor(type, severity) ?: return Triple(r, g, b)
        return Triple(
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b,
        )
    }

    /** The colour as [type] would see it, back in sRGB. */
    fun simulate(rgb: Rgb8, type: CvdType, severity: Float = type.severity): Rgb8 {
        if (type.isNormal) return rgb
        val (r, g, b) = simulateLinear(
            DECODE[rgb.r],
            DECODE[rgb.g],
            DECODE[rgb.b],
            type,
            severity,
        )
        return Rgb8(encode(r), encode(g), encode(b))
    }

    /** Same as [simulate] for packed ARGB — the per-pixel path used for previews. */
    fun simulateArgb(argb: Int, type: CvdType, severity: Float = type.severity): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (type.isNormal) return argb
        val (lr, lg, lb) = simulateLinear(DECODE[r], DECODE[g], DECODE[b], type, severity)
        return (argb and 0xFF000000.toInt()) or (encode(lr) shl 16) or (encode(lg) shl 8) or encode(lb)
    }

    /** Linear light -> 8-bit, gamut-clamped, via the LUT. */
    fun encode(linear: Float): Int {
        if (linear <= 0f) return 0
        if (linear >= 1f) return 255
        val scaled = linear * ENCODE_STEPS
        val index = scaled.toInt()
        val frac = scaled - index
        val lo = ENCODE[index]
        val hi = ENCODE[index + 1]
        return if (frac <= 0f) lo else (lo + ((hi - lo) * frac) + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Exposed for tests: the identity matrix, to prove severity 0 changes nothing. */
    internal fun identity(): FloatArray = IDENTITY.copyOf()
}
