package com.dboycht.colorlens.ui

import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.dboycht.colorlens.BuildConfig
import com.dboycht.colorlens.color.ColorMath
import com.dboycht.colorlens.color.ColorNamer
import com.dboycht.colorlens.color.Rgb8

/**
 * Debug-only capture diagnostics: what the camera's image processor actually did
 * with white balance, and what the app then made of the pixels.
 *
 * ## What this answers
 *
 * "Is the white balance off?" is not a matter of opinion — the ISP reports the
 * per-channel gains it applied ([CaptureResult.COLOR_CORRECTION_GAINS]). On a
 * neutral scene they sit near 1/1 in the ratio sense even though the absolute
 * numbers vary; under a warm lamp red is pulled above blue. Together with the
 * colour-correction matrix, the ISO and the exposure time, that is the ground
 * truth behind "the wood came out orange".
 *
 * ## Why it is guarded
 *
 * Every entry point returns early unless `BuildConfig.DEBUG`, so a **published
 * build neither logs nor pays for this**. The log line contains pixel values of
 * whatever the user pointed at, which is exactly the kind of thing that must not
 * end up in a release build's logcat.
 *
 * ## Caveat, stated honestly
 *
 * CameraX does not hand back the still capture's own `CaptureResult` through
 * `OnImageCapturedCallback` — the values here are the **session's** most recent
 * frames around the moment of capture, and the reported spread shows whether
 * auto white balance had converged or was still hunting.
 */
object CameraDiagnostics {

    const val TAG = "ColorLensDiag"

    private const val HISTORY = 8

    private class Sample(val gains: String, val state: Int?)

    private val history = ArrayDeque<Sample>()
    private var latest: TotalCaptureResult? = null

    /** Called from the Camera2 session callback for every completed frame. */
    fun record(result: TotalCaptureResult) {
        if (!BuildConfig.DEBUG) return
        latest = result
        val vector = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val sample = Sample(
            gains = vector?.let { "%.3f/%.3f/%.3f".format(it.red, it.greenEven, it.blue) } ?: "n/a",
            state = result.get(CaptureResult.CONTROL_AWB_STATE),
        )
        history.addLast(sample)
        while (history.size > HISTORY) history.removeFirst()
    }

    fun forget() {
        latest = null
        history.clear()
    }

    /** One line per capture. Safe to call unconditionally; it no-ops in release. */
    fun logCapture(image: Bitmap, source: String) {
        if (!BuildConfig.DEBUG) return
        val result = latest
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val ccm = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val centre = BitmapTools.averageAround(
            image,
            image.width / 2f,
            image.height / 2f,
            radius = 4,
        )
        val scene = sceneAverage(image)
        val reading = ColorNamer.read(centre)
        Log.i(
            TAG,
            buildString {
                append("capture source=").append(source)
                append(" size=").append(image.width).append('x').append(image.height)
                append(" | awbMode=").append(result?.get(CaptureResult.CONTROL_AWB_MODE))
                append(" awbState=").append(result?.get(CaptureResult.CONTROL_AWB_STATE))
                append(" gains r/g/b=").append(
                    gains?.let { "%.3f/%.3f/%.3f".format(it.red, it.greenEven, it.blue) } ?: "n/a",
                )
                append(" gainsSpread=").append(gainsSpread())
                append(" ccMode=").append(result?.get(CaptureResult.COLOR_CORRECTION_MODE))
                append(" ccm=").append(ccm?.let(::formatMatrix) ?: "n/a")
                append(" iso=").append(result?.get(CaptureResult.SENSOR_SENSITIVITY))
                append(" expNs=").append(result?.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                append(" | sceneAvg=").append(scene.toHex())
                append(" centre=").append(centre.toHex())
                append(" name=").append(reading.primaryName)
                append(" hue=").append("%.1f".format(reading.hueDeg))
                append(" C=").append("%.4f".format(reading.chromaPct / 100f * ColorMath.MAX_SRGB_CHROMA))
                append(" L=").append(reading.lightnessPct)
                append(" relLum=").append("%.4f".format(ColorMath.relativeLuminance(centre)))
            },
        )
    }

    /** How much the gains moved over the last few frames: converged or still hunting. */
    private fun gainsSpread(): String {
        if (history.size < 2) return "n/a"
        val values = history.map { it.gains }.distinct()
        return "samples=${history.size} distinct=${values.size} states=${history.map { it.state }.distinct()}"
    }

    private fun formatMatrix(matrix: android.hardware.camera2.params.ColorSpaceTransform): String =
        (0..2).joinToString("|") { row ->
            (0..2).joinToString(",") { col ->
                "%.3f".format(matrix.getElement(row, col).toFloat())
            }
        }

    /** Coarse average of the whole frame: the overall colour cast of the scene. */
    private fun sceneAverage(image: Bitmap): Rgb8 {
        val steps = 16
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (image.width - 1) * i / (steps - 1)
                val y = (image.height - 1) * j / (steps - 1)
                val pixel = image.getPixel(x, y)
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                n++
            }
        }
        return if (n == 0) Rgb8(0, 0, 0) else Rgb8((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
