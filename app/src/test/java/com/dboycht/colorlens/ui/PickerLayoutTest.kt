package com.dboycht.colorlens.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's vertical budget.
 *
 * ## What this is really guarding
 *
 * The user's report (2026-09-28) was "照片大小会随着下面的描述行数变化". Measured on
 * the device before the fix: the photo box was 763 px tall with a two-line
 * description and would grow by a line (~72 px) with a one-line one, because the
 * photo was `weight(1f)` — "whatever the text does not need".
 *
 * The photo's height is now a function of the page, the photo's aspect ratio and a
 * fixed [PickerLayout.reserve], and of nothing else. The device-level proof is the
 * photo node's `bounds` staying identical across two picks with different
 * description lengths (`_scratch/verify-layout.md`); these tests pin the arithmetic
 * in between, including the constant that silently breaks everything if it drifts
 * away from `ColorCard`.
 *
 * Reference device: 1080x2412 at density 3.0 (360x804 dp). The picker's usable area
 * measures 336x656 dp, the reserve is 434 dp, and the photo ends up 222 dp tall —
 * 666x888 px of photo, against 572x763 px in the old weighted layout. Most of that
 * difference is the new zoom row plus a photo box that is no longer 1.32:1 around a
 * 0.75:1 photo with 43% of its width spent on black bars, and the rest is the price
 * of the description no longer being allowed to squeeze the photo.
 */
class PickerLayoutTest {

    private val body = 24.dp
    private val label = 16.dp
    private val reserve = PickerLayout.reserve(body, fontScale = 1f)

    @Test
    fun `the reading budget is tight, not padded`() {
        // Device measurement (1080x2412, density 3.0, page 336x656 dp): the rows
        // around the photo come to 242 dp — four 48 dp rows plus their five 10 dp
        // gaps — and the card to 192 dp: a 132 dp swatch, a 12 dp gap, and two
        // description lines that measure 24 dp each, not one dp more. A reserve of
        // 422 dp (the previous guess) left the reading area scrolling by exactly
        // 12 dp; every dp above the measured total is a dp of photo given away.
        val measured = 242.dp + 192.dp
        assertTrue("reserve $reserve pads past the measured $measured", reserve <= measured)
        assertTrue(
            "reserve $reserve must cover the swatch and its description",
            reserve > PickerLayout.SWATCH_HEIGHT + body * PickerLayout.DESCRIPTION_LINES,
        )
        assertEquals(measured.value.toDouble(), reserve.value.toDouble(), 0.01)
    }

    @Test
    fun `the reserve grows with the font size so 大字号 clips nothing`() {
        val big = PickerLayout.reserve(body * 1.3f, fontScale = 1.3f)
        assertTrue("$big should be bigger than $reserve", big > reserve)
        // Two things grow at 大字号: the two reserved description lines (2 x 24 sp x
        // 0.3 = 14.4 dp) and the four rows around the photo, which the device measured
        // at 14 dp more (48 dp x 0.3). Anything less and the reading area starts
        // cutting the description again.
        assertEquals(28.8, (big - reserve).value.toDouble(), 0.2)
    }

    @Test
    fun `fullscreen gives the photo the whole screen width`() {
        // Reference phone: the normal layout is handed 336x656 dp and 222 dp of photo
        // comes out of it; fullscreen is handed the full 360 dp width and ~784 dp of
        // height (system bars and the app's own navigation bar hidden), and its 252 dp
        // of chrome leaves room for the photo to reach the width limit instead of the
        // height limit — which is the whole point of the mode.
        val normal = PickerLayout.photoBox(336.dp, 656.dp, photoAspect = 0.75f, reserve = reserve)
        val full = PickerLayout.photoBox(
            pageWidth = 360.dp,
            pageHeight = 784.dp - 16.dp,
            photoAspect = 0.75f,
            reserve = PickerLayout.fullscreenReserve(fontScale = 1f),
        )
        assertTrue("fullscreen $full must be much taller than $normal", full.height > normal.height * 2)
        assertEquals("fullscreen must use the whole page width", 360.0, full.width.value.toDouble(), 0.01)
    }

    @Test
    fun `the fullscreen budget is tight, not padded`() {
        // Four rows plus four 10 dp gaps plus the compact reading bar: 48 + 48 + 68 +
        // 48 + 40 = 252 dp. Every dp above that is a dp of photo in the one mode whose
        // entire reason to exist is a bigger photo.
        val full = PickerLayout.fullscreenReserve(fontScale = 1f)
        assertEquals(252.0, full.value.toDouble(), 0.01)
    }

    @Test
    fun `fullscreen also pays for the font scale`() {
        // The reading bar holds two lines of text (titleLarge + labelLarge = 48 dp at
        // 1×) and the four rows still grow: at 大字号 the mode must not start clipping
        // its own action row off the bottom of the screen.
        val full = PickerLayout.fullscreenReserve(fontScale = 1.3f)
        val tight = PickerLayout.fullscreenReserve(fontScale = 1f)
        assertEquals(28.8, (full - tight).value.toDouble(), 0.2)
        assertEquals(tight.value.toDouble(), PickerLayout.fullscreenReserve(fontScale = 0.85f).value.toDouble(), 0.01)
    }

    @Test
    fun `fullscreen always has a way out`() {
        // A mode that hides the navigation bar is a trap unless leaving it is both
        // visible and reachable by the system back gesture.
        val picker = File("src/main/java/com/dboycht/colorlens/ui/PickerScreen.kt").readText()
        assertTrue("fullscreen must size the photo from its own reserve", picker.contains("PickerLayout.fullscreenReserve("))
        assertTrue("fullscreen needs an entry point", picker.contains("onFullscreenChange(true)"))
        assertTrue("fullscreen needs a visible exit", picker.contains("退出全屏"))
        assertTrue("the back gesture must leave fullscreen first", picker.contains("BackHandler(enabled = fullscreen)"))
        assertTrue("the system bars must be restored on the way out", picker.contains("controller?.show(WindowInsetsCompat.Type.systemBars())"))
        // Device-found traps: fullscreen + a lost photo (font-scale change recreates
        // the activity) left the navigation bar hidden on the empty state, and
        // fullscreen + 对比 navigated to another page with it still hidden.
        assertTrue(
            "fullscreen must drop out when the photo is gone",
            picker.contains("if (bitmap == null) onFullscreenChange(false)"),
        )
        assertTrue(
            "the empty state must come after that check, not before it",
            picker.indexOf("if (bitmap == null) onFullscreenChange(false)") <
                picker.indexOf("EmptyPhotoState(onNeedPhoto"),
        )
        val activity = File("src/main/java/com/dboycht/colorlens/MainActivity.kt").readText()
        assertTrue(
            "leaving the picker must un-hide the navigation bar",
            activity.contains("if (entry != Tab.PICK) pickerFullscreen = false"),
        )
        assertTrue(
            "every route out of the picker must restore it",
            activity.contains("LaunchedEffect(tab) { if (tab != Tab.PICK) pickerFullscreen = false }"),
        )
        assertTrue(
            "the navigation bar must be hidden in fullscreen",
            activity.contains("if (!pickerFullscreen)"),
        )
    }

    @Test
    fun `a smaller font scale does not shrink the reserve below the measured budget`() {
        // The rows cannot go below Material's 48 dp touch target, so the reserve must
        // not pretend they can.
        assertEquals(reserve.value.toDouble(), PickerLayout.reserve(body, fontScale = 0.85f).value.toDouble(), 0.01)
    }

    @Test
    fun `a wide photo takes the full width and its own height`() {
        val box = PickerLayout.photoBox(360.dp, 700.dp, photoAspect = 1.5f, reserve = reserve)
        assertEquals(360f, box.width.value, 0.01f)
        assertEquals(240f, box.height.value, 0.01f)
    }

    @Test
    fun `a tall photo is capped by the budget and keeps its aspect`() {
        val box = PickerLayout.photoBox(360.dp, 700.dp, photoAspect = 0.75f, reserve = reserve)
        assertEquals(700f - reserve.value, box.height.value, 0.01f)
        assertTrue("the box must stay narrower than the page: ${box.width}", box.width < 360.dp)
        assertEquals("aspect must be preserved exactly", 0.75f, box.width.value / box.height.value, 0.001f)
    }

    @Test
    fun `the photo never leaves the page no matter the shape or the page`() {
        val shapes = listOf(0.4f, 0.75f, 1f, 1.5f, 3f)
        val pages = listOf(320.dp to 480.dp, 360.dp to 700.dp, 400.dp to 860.dp)
        for ((w, h) in pages) {
            for (aspect in shapes) {
                val box = PickerLayout.photoBox(w, h, aspect, reserve)
                assertTrue("$aspect in $w x $h: width ${box.width}", box.width <= w + 0.01.dp)
                assertTrue("$aspect in $w x $h: height ${box.height}", box.height <= h + 0.01.dp)
                // The floor applies to the *budget*, not to a page that is simply too
                // narrow for a 3:1 panorama to be any taller than it is.
                val floor = minOf(w / aspect, PickerLayout.MIN_PHOTO_HEIGHT)
                assertTrue("$aspect in $w x $h: ${box.width} x ${box.height}", box.height >= floor - 0.01.dp)
                assertEquals(aspect, box.width.value / box.height.value, 0.001f)
            }
        }
    }

    @Test
    fun `a cramped page keeps a usable photo instead of collapsing it`() {
        // Landscape phone: 393 dp of height cannot afford the whole reserve.
        val box = PickerLayout.photoBox(700.dp, 300.dp, photoAspect = 0.75f, reserve = reserve)
        assertEquals(PickerLayout.MIN_PHOTO_HEIGHT, box.height)
    }

    @Test
    fun `degenerate inputs return an empty box rather than a negative one`() {
        assertEquals(DpSize.Zero, PickerLayout.photoBox(0.dp, 700.dp, 0.75f, reserve))
        assertEquals(DpSize.Zero, PickerLayout.photoBox(360.dp, 0.dp, 0.75f, reserve))
        assertEquals(DpSize.Zero, PickerLayout.photoBox(360.dp, 700.dp, 0f, reserve))
    }

    @Test
    fun `the reserve's swatch height still matches ColorCard`() {
        // A silent mismatch here is exactly the bug class this file exists to stop:
        // the reserve would be too small, the card would overflow, and the info area
        // would scroll — or worse, someone would "fix" it by shrinking the photo.
        val source = File("src/main/java/com/dboycht/colorlens/ui/ColorCard.kt")
        assertTrue("ColorCard.kt must be readable from ${source.absolutePath}", source.isFile)
        val heights = Regex("""\.height\((\d+)\.dp\)""").findAll(source.readText()).map { it.groupValues[1] }.toList()
        assertEquals("expected exactly one fixed height (the swatch block) in ColorCard: $heights", 1, heights.size)
        assertEquals(PickerLayout.SWATCH_HEIGHT.value, heights.first().toFloat(), 0.01f)
    }

    @Test
    fun `the layout the picker uses is the layout this class describes`() {
        // The screen must not go back to a weighted photo: `weight(1f)` on the photo
        // is the original bug, and it is one word away from coming back.
        val source = File("src/main/java/com/dboycht/colorlens/ui/PickerScreen.kt").readText()
        val photoCall = source.substringAfter("PhotoCanvas(").substringBefore("ZoomRow(")
        assertTrue("the photo must be sized from PickerLayout.photoBox", source.contains("PickerLayout.photoBox("))
        assertTrue("the photo must not be weighted again: $photoCall", !photoCall.contains(".weight("))
        assertTrue("the info area must keep scrolling inside its own box", source.contains(".verticalScroll(rememberScrollState())"))
        assertTrue("the photo box must be pinned to the computed size", photoCall.contains("photoBox.height"))
    }

    @Test
    fun `no control hides inside the scrolling region`() {
        // Measured on the device 2026-09-28: with the white-balance row inside the
        // scrolling card region, its button's bounds came back clipped at the region's
        // bottom edge — invisible and untappable without a scroll the user has no
        // reason to expect. Buttons belong in the fixed chrome; only reading matter
        // scrolls. This finds the scrolling Column's lambda by brace matching and
        // checks what lives inside it: the card yes, the calibration row no.
        val source = File("src/main/java/com/dboycht/colorlens/ui/PickerScreen.kt").readText()
        val scrollAt = source.indexOf(".verticalScroll(")
        assertTrue("the picker must keep a scrolling reading region", scrollAt > 0)
        val region = lambdaRange(source, source.indexOf('{', scrollAt))
        assertTrue("the card must be inside the scrolling region", source.indexOf("ColorCard(") in region)
        assertTrue(
            "the calibration row (a button) must not be inside the scrolling region",
            source.indexOf("CalibrationRow(") !in region,
        )
        assertTrue("the zoom row (buttons) must be outside the scrolling region", source.indexOf("ZoomRow(") !in region)
    }

    @Test
    fun `the extras hint never shares the swatch with the colour numbers`() {
        // Measured at 大字号 on the device: with the "更像…/介于…之间" line inside the
        // swatch, the name's box (y 1472..1644) overlapped the hex numbers' box
        // (y 1423..1504) by 32 px, because both are anchored to opposite edges of a
        // 132 dp box. The swatch is the one place where two texts must never collide,
        // so the hint travels with the description below it instead.
        val card = File("src/main/java/com/dboycht/colorlens/ui/ColorCard.kt").readText()
        val swatchAt = card.indexOf(".height(132.dp)")
        val readingAt = card.indexOf("Column(modifier = Modifier.padding(top = 12.dp)")
        assertTrue("the swatch box must come first", swatchAt in 1 until readingAt)
        val swatch = card.substring(swatchAt, readingAt)
        assertTrue("the swatch hosts the name and the numbers, nothing else", !swatch.contains("extras"))
        assertTrue("the extra colour hint belongs with the description", card.substring(readingAt).contains("extras"))
    }

    /** The range of the lambda that opens at [open], its braces included. */
    private fun lambdaRange(source: String, open: Int): IntRange {
        var depth = 0
        for (i in open..source.lastIndex) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return open..i
                }
            }
        }
        return open..source.lastIndex
    }
}
