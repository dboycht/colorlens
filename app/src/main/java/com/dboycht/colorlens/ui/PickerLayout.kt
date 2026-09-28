package com.dboycht.colorlens.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The picker's vertical budget as pure arithmetic, so "the photo must not resize
 * when the description runs to another line" is a property of a function that can
 * be unit-tested rather than a hope about how Compose measures things.
 *
 * ## The bug this exists for
 *
 * The photo used to be `weight(1f)`, i.e. "whatever the text does not need". A
 * two-line description therefore stole a line's height from the photo, and the photo
 * visibly rescaled while the user moved the crosshair (reported 2026-09-28). The fix
 * is to size the photo from the page and the photo's own aspect ratio, and to give
 * the reading area a **fixed reserve** instead: short descriptions leave slack,
 * long ones scroll inside their own box, and the photo never moves.
 *
 * [reserve] deliberately reserves the *worst case* number of description lines
 * regardless of what the current colour actually says — that is the whole point.
 * All the numbers are derived from the theme's line heights so the 大字号 setting
 * scales the reserve too, instead of clipping text.
 */
object PickerLayout {

    /** The colour swatch block inside `ColorCard`. Kept in sync by `PickerLayoutTest`. */
    val SWATCH_HEIGHT = 132.dp

    /**
     * Lines of description the reading area always reserves.
     *
     * Two, not three: measured on the reference device, descriptions are one or two
     * lines, and every line reserved here is a line the photo above does not get.
     * The rare three-line description (or 大字号) simply scrolls the reading area by
     * a few dp — invisible, because the only thing that ends up below the fold is
     * the calibration row's own padding, and scrolling is what `verticalScroll` is
     * there for. What must *not* happen is the photo changing size, and it cannot.
     */
    const val DESCRIPTION_LINES = 2

    /** Space between the picker's rows (must match the Column's `spacedBy`). */
    val ROW_GAP = 10.dp

    /**
     * The rows around the photo, at the heights the device actually reports: four
     * 48 dp rows. Every one of them is 48 dp — the A/B chips, the ＋/－ buttons, the
     * white-balance button and the 朗读/对比/换照片 buttons alike — because Material
     * enforces a 48 dp interactive target on all of them. Two earlier versions of
     * these constants guessed 32 and 40 dp for the rows that *look* small, and each
     * time the reading area came up short and clipped the description's second line.
     */
    private val MARKER_ROW = 48.dp
    private val ZOOM_ROW = 48.dp
    private val CALIBRATION_ROW = 48.dp
    private val BUTTON_ROW = 48.dp

    /**
     * How much the four rows grow per unit of extra font scale: measured at 大字号
     * (1.3×), where the rows above the reading area took 14 dp more than at 1× —
     * 48 dp × 0.3. Material's 48 dp minimum touch target absorbs most of that growth
     * but not all of it, and the reading area pays for whatever the rows take.
     */
    private val ROW_GROWTH_PER_SCALE = 48.dp

    /** Smallest photo worth showing; below this the info area just scrolls. */
    val MIN_PHOTO_HEIGHT = 140.dp

    /**
     * Height kept for everything that is not the photo: the rows around the photo,
     * plus the reading area's worst case. Subtracting this from the page height is
     * what makes the photo independent of the text below it.
     *
     * The hex/RGB numbers are *not* in here: they live on the swatch now, which is
     * 22 dp of photo that the numbers used to cost.
     *
     * Verified on the device (1080x2412, density 3.0, page 336x656 dp): at 434 dp the
     * two-line description is fully visible and the reading area does not scroll. At
     * 422 dp it scrolled by exactly 12 dp and cut the second line's descenders. At
     * 大字号 the rows take 14 dp more, which this term gives back to the reading area.
     */
    fun reserve(bodyLine: Dp, fontScale: Float): Dp {
        val reading = SWATCH_HEIGHT + 12.dp + bodyLine * DESCRIPTION_LINES
        val rows = MARKER_ROW + ZOOM_ROW + CALIBRATION_ROW + BUTTON_ROW + ROW_GAP * 5 +
            ROW_GROWTH_PER_SCALE * (fontScale - 1f).coerceAtLeast(0f)
        return rows + reading
    }

    /**
     * The compact reading bar fullscreen mode uses instead of the card: a 40 dp
     * swatch chip beside two lines of text.
     */
    val FULLSCREEN_READING = 68.dp

    /**
     * The text inside that bar: titleLarge (28 dp) + labelLarge (20 dp) at 1×. It is
     * here because the bar is text, so unlike the card's swatch it grows with the
     * font scale — at 大字号 it needs 14 dp more, and the photo must pay for it rather
     * than the action row falling off the bottom of the screen.
     */
    private val FULLSCREEN_READING_TEXT = 48.dp

    /**
     * The chrome kept in fullscreen mode: the marker row, the zoom row, the compact
     * reading bar and the action row. Four rows, 252 dp at 1× — everything else
     * stands aside, including the description: fullscreen exists to look closely,
     * and 朗读 reads the description out anyway.
     *
     * On the reference phone hiding the system bars and the app's own navigation bar
     * hands the picker about 784 dp of height, so 252 dp of chrome leaves the photo
     * 480 dp — the full screen width at this photo's 3:4 aspect, 1080x1440 px, against
     * 500x667 px in the normal layout.
     */
    fun fullscreenReserve(fontScale: Float): Dp {
        val growth = (fontScale - 1f).coerceAtLeast(0f)
        val rows = MARKER_ROW + ZOOM_ROW + BUTTON_ROW + ROW_GAP * 4 + ROW_GROWTH_PER_SCALE * growth
        val reading = FULLSCREEN_READING + FULLSCREEN_READING_TEXT * growth
        return rows + reading
    }

    /**
     * The photo box: exactly the photo's aspect ratio (so there are no dead black
     * bars eating the width, which is what the first version did), as wide as the
     * page allows, never taller than the page can afford.
     */
    fun photoBox(pageWidth: Dp, pageHeight: Dp, photoAspect: Float, reserve: Dp): DpSize {
        if (pageWidth <= 0.dp || pageHeight <= 0.dp || photoAspect <= 0f) return DpSize(0.dp, 0.dp)
        val affordable = (pageHeight - reserve).coerceAtLeast(MIN_PHOTO_HEIGHT)
        val height = minOf(pageWidth / photoAspect, affordable)
        return DpSize(height * photoAspect, height)
    }
}
