package com.dboycht.colorlens.color

import java.util.Locale

/**
 * An 8-bit sRGB triple.
 *
 * This is a **pure value type on purpose**: the naming and simulation logic must
 * be unit-testable on the plain JVM, and `android.graphics.Color` is not usable
 * from local unit tests (every method throws "not mocked"). So the core never
 * touches `android.graphics`; the UI layer converts at the boundary.
 */
data class Rgb8(val r: Int, val g: Int, val b: Int) {

    init {
        require(r in 0..255) { "r out of range: $r" }
        require(g in 0..255) { "g out of range: $g" }
        require(b in 0..255) { "b out of range: $b" }
    }

    /** `#RRGGBB`, uppercase — what a user would read out or type elsewhere. */
    fun toHex(): String = String.format(Locale.US, "#%02X%02X%02X", r, g, b)

    /** Opaque ARGB, for handing to `android.graphics.Color` / Compose. */
    fun toArgb(): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** `R 139 G 0 B 0` — the form other people can look up. */
    fun toRgbText(): String = "R $r  G $g  B $b"

    fun isDark(): Boolean = ColorMath.relativeLuminance(this) < 0.35f

    companion object {
        fun fromArgb(argb: Int): Rgb8 = Rgb8(
            r = (argb shr 16) and 0xFF,
            g = (argb shr 8) and 0xFF,
            b = argb and 0xFF,
        )

        /** Accepts `#RGB`, `#RRGGBB`, `RRGGBB`, with or without a leading `#`. */
        fun parseHex(text: String): Rgb8? {
            val body = text.trim().removePrefix("#")
            return when (body.length) {
                3 -> {
                    val r = body[0].digitToIntOrNull(16) ?: return null
                    val g = body[1].digitToIntOrNull(16) ?: return null
                    val b = body[2].digitToIntOrNull(16) ?: return null
                    Rgb8(r * 17, g * 17, b * 17)
                }
                6 -> {
                    val v = body.toLongOrNull(16) ?: return null
                    if (v < 0 || v > 0xFFFFFF) return null
                    Rgb8(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
                }
                else -> null
            }
        }
    }
}
