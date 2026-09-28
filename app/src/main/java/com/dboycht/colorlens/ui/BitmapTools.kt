package com.dboycht.colorlens.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import com.dboycht.colorlens.color.CvdType
import com.dboycht.colorlens.color.Rgb8
import com.dboycht.colorlens.color.VisionSimulator
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Bitmap plumbing: decode, sample, simulate, magnify.
 *
 * Everything here is deliberately boring, but three points are load-bearing:
 *
 * 1. **`ALLOCATOR_SOFTWARE`.** A hardware bitmap throws on `getPixel`; the whole
 *    app is about reading one pixel.
 * 2. **EXIF orientation is handled by `ImageDecoder`** rather than by hand, so a
 *    photo taken in portrait is not analysed sideways.
 * 3. **Sampling happens at decode time**, not afterwards, so a 50-megapixel phone
 *    photo never allocates 200 MB just to be downscaled.
 */
object BitmapTools {

    /**
     * Longest edge kept in memory. 2048 px is ~12 MB as ARGB_8888 and is far more
     * detail than a colour reading needs; a smaller bitmap also makes the
     * per-pixel simulation preview fast enough to feel instant.
     */
    const val MAX_EDGE = 2048

    /** Longest edge of a simulated preview — big enough to judge, small enough to be instant. */
    const val PREVIEW_EDGE = 900

    fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // A hardware bitmap cannot be read pixel by pixel, which is all this
            // app does with it.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_EDGE) {
                decoder.setTargetSampleSize(ceil(longest / MAX_EDGE.toFloat()).toInt())
            }
        }
    }.getOrNull()

    /** Reads one pixel. Coordinates are clamped, so a marker on the edge is safe. */
    fun pixelAt(bitmap: Bitmap, x: Float, y: Float): Rgb8 {
        val px = x.toInt().coerceIn(0, bitmap.width - 1)
        val py = y.toInt().coerceIn(0, bitmap.height - 1)
        return Rgb8.fromArgb(bitmap.getPixel(px, py))
    }

    /**
     * Averages a small square instead of trusting a single pixel.
     *
     * Camera sensors are noisy and a single pixel can be an unlucky one; a 3×3
     * average is what a person means by "the colour of that spot". The crosshair
     * in the UI shows the exact pixel, so the two views agree closely enough.
     */
    fun averageAround(bitmap: Bitmap, x: Float, y: Float, radius: Int = 1): Rgb8 {
        val cx = x.toInt().coerceIn(0, bitmap.width - 1)
        val cy = y.toInt().coerceIn(0, bitmap.height - 1)
        val left = (cx - radius).coerceAtLeast(0)
        val top = (cy - radius).coerceAtLeast(0)
        val right = (cx + radius).coerceAtMost(bitmap.width - 1)
        val bottom = (cy + radius).coerceAtMost(bitmap.height - 1)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (py in top..bottom) {
            for (px in left..right) {
                val argb = bitmap.getPixel(px, py)
                sumR += (argb shr 16) and 0xFF
                sumG += (argb shr 8) and 0xFF
                sumB += argb and 0xFF
                count++
            }
        }
        if (count == 0) return Rgb8.fromArgb(bitmap.getPixel(cx, cy))
        return Rgb8(
            r = (sumR / count).toInt().coerceIn(0, 255),
            g = (sumG / count).toInt().coerceIn(0, 255),
            b = (sumB / count).toInt().coerceIn(0, 255),
        )
    }

    /**
     * A square crop centred on a point, used by the magnifier. The crop is taken
     * at full resolution and then scaled up by the caller, so the loupe shows real
     * pixels rather than a blurry upscale of the already-downscaled photo.
     */
    fun cropAround(bitmap: Bitmap, x: Float, y: Float, sizePx: Int): Bitmap {
        val half = sizePx / 2
        val left = (x.toInt() - half).coerceIn(0, max(0, bitmap.width - sizePx))
        val top = (y.toInt() - half).coerceIn(0, max(0, bitmap.height - sizePx))
        val width = min(sizePx, bitmap.width - left)
        val height = min(sizePx, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * The whole photo as [type] would see it, downscaled for speed.
     *
     * Done on a [PREVIEW_EDGE]-bounded copy: the transformation is per-pixel
     * arithmetic on linear light, and running it over 12 megapixels would freeze
     * the UI for a second. The preview is for recognising a colour, not for
     * reading text.
     */
    fun simulate(
        bitmap: Bitmap,
        type: CvdType,
        severity: Float,
        maxEdge: Int = PREVIEW_EDGE,
    ): Bitmap {
        val scaled = scaleToFit(bitmap, maxEdge)
        if (type.isNormal) return scaled

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            pixels[i] = VisionSimulator.simulateArgb(pixels[i], type, severity)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    /** Scales down to fit [maxEdge] on the longest side; returns the input if small enough. */
    fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** Natural size in pixels, for the [Size] the camera gives us. */
    fun sizeOf(bitmap: Bitmap): Size = Size(bitmap.width, bitmap.height)
}
