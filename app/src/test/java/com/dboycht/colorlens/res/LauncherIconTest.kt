package com.dboycht.colorlens.res

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry guard for the launcher icon, plus a text report of it.
 *
 * ## Why this is a test and not a glance at the artwork
 *
 * An adaptive icon is a 108×108 canvas of which the launcher may show only the
 * middle 66×66 circle (radius 33 around 54,54). Art that runs outside that circle
 * looks completely reasonable in the XML and gets visibly sliced on a phone — which
 * is what 1.0.1 shipped: the top tick reached y=5, the colour chips reached y=82 and
 * x=22/86, and the whole composition sat at y≈43 instead of 54, so a round mask cut
 * it into pieces (user report "图标显示不全", 2026-09-28).
 *
 * Nobody can eyeball that difference in a source file, and this project's rule is
 * that verification has to be text. So the constraints are asserted here:
 *
 * 1. every drawn element fits inside the safe circle, with the extents printed to
 *    `app/build/reports/colorlens/icon-report.txt` so a reader can audit the numbers;
 * 2. the mark is centred on the canvas (the user's explicit request: "符号在中间");
 * 3. any path the parser does not recognise is a **failure**, so a new shape type
 *    cannot silently escape the check;
 * 4. `<group>` is rejected outright — a scale or translate there would invalidate
 *    every number below.
 *
 * The maths is deliberately conservative (strokes count their full width, round caps
 * count twice), so a pass is a stronger statement than the drawing needs.
 */
class LauncherIconTest {

    private data class Shape(
        val what: String,
        val centreX: Float,
        val centreY: Float,
        val maxRadius: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    )

    @Test
    fun `the icon mark stays inside the adaptive-icon safe circle and is centred`() {
        val layers = listOf(
            "src/main/res/drawable/ic_launcher_foreground.xml",
            "src/main/res/drawable/ic_launcher_monochrome.xml",
        )
        val report = StringBuilder()
        report.append("colorlens 启动图标几何 · 画布 108×108，圆心 (54,54)，安全圆半径 33\n")
        report.append("判据：每个元素的 maxRadius（含描边，保守取值）必须 ≤ 33；整个标记的包围盒中心必须落在 (54,54) ±1.5\n")
        report.append("-".repeat(104)).append('\n')
        report.append("层".padEnd(26)).append("元素".padEnd(34)).append("maxRadius".padEnd(12))
            .append("包围盒 x".padEnd(16)).append("包围盒 y\n")

        for (layer in layers) {
            val file = File(layer)
            assertTrue(
                "icon layer not found at ${file.absolutePath} — unit tests run with app/ as the working directory",
                file.isFile,
            )
            val text = file.readText()
            val paths = Regex("<path\\b[^>]*>").findAll(text).map { it.value }.toList()
            assertTrue("$layer: no <path> elements found", paths.isNotEmpty())
            assertTrue(
                "$layer: a <group> transform would silently invalidate this check",
                !text.contains("<group"),
            )
            assertVectorCanvas(layer, text)

            val shapes = paths.flatMap { path ->
                val data = attribute(path, "android:pathData")
                assertTrue("$layer: <path> without pathData", data != null)
                val stroke = attribute(path, "android:strokeWidth")?.toFloat() ?: 0f
                val roundCap = path.contains("strokeLineCap=\"round\"")
                shapesOf(data!!, stroke, roundCap, layer)
            }
            assertTrue("$layer: nothing was parsed", shapes.isNotEmpty())

            for (shape in shapes) {
                assertTrue(
                    "$layer: ${shape.what} reaches radius ${shape.maxRadius}, outside the safe circle " +
                        "(limit $SAFE_RADIUS) — the launcher mask will cut it",
                    shape.maxRadius <= SAFE_RADIUS,
                )
                report.append(layer.substringAfterLast('/').padEnd(26))
                report.append(shape.what.padEnd(34))
                report.append("%-12s".format("%.2f".format(shape.maxRadius)))
                report.append("%-16s".format("[%.2f,%.2f]".format(shape.minX, shape.maxX)))
                report.append("[%.2f,%.2f]".format(shape.minY, shape.maxY)).append('\n')
            }

            val minX = shapes.minOf { it.minX }
            val maxX = shapes.maxOf { it.maxX }
            val minY = shapes.minOf { it.minY }
            val maxY = shapes.maxOf { it.maxY }
            val worst = shapes.maxOf { it.maxRadius }
            report.append(
                "  -> 包围盒 [%.2f,%.2f]×[%.2f,%.2f] 中心 (%.2f,%.2f) 最大半径 %.2f（余量 %.2f）\n"
                    .format(minX, maxX, minY, maxY, (minX + maxX) / 2f, (minY + maxY) / 2f, worst, SAFE_RADIUS - worst),
            )

            assertEquals(
                "$layer: the mark is not centred horizontally (bbox $minX…$maxX)",
                CENTRE,
                (minX + maxX) / 2f,
                1.5f,
            )
            assertEquals(
                "$layer: the mark is not centred vertically (bbox $minY…$maxY)",
                CENTRE,
                (minY + maxY) / 2f,
                1.5f,
            )
            assertTrue(
                "$layer: the mark is much smaller than the safe area (max radius $worst) — " +
                    "it would look lost on the launcher",
                worst >= 24f,
            )
        }

        report.append('\n').append(describeIconWiring())
        val outDir = File("build/reports/colorlens")
        assertTrue("could not create ${outDir.absolutePath}", outDir.exists() || outDir.mkdirs())
        val out = File(outDir, "icon-report.txt")
        out.writeText(report.toString(), Charsets.UTF_8)
        println("icon report: ${out.absolutePath}")
        println(report)
    }

    /** The icon must be wired up end to end, not just drawn. */
    private fun describeIconWiring(): String {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("manifest must declare android:icon", manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(
            "manifest must declare android:roundIcon (some launchers ask for it)",
            manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""),
        )

        val lines = StringBuilder()
        lines.append("图标接线\n")
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val adaptive = File("src/main/res/mipmap-anydpi-v26/$name.xml")
            assertTrue("$name.xml is missing from mipmap-anydpi-v26", adaptive.isFile)
            val text = adaptive.readText()
            assertTrue("$name.xml is not an <adaptive-icon>", text.contains("<adaptive-icon"))
            for (tag in listOf("background", "foreground", "monochrome")) {
                assertTrue("$name.xml has no <$tag>", text.contains("<$tag"))
            }
            // Every drawable it points at must exist as a file.
            Regex("@drawable/([a-z0-9_]+)").findAll(text).forEach {
                val drawable = File("src/main/res/drawable/${it.groupValues[1]}.xml")
                assertTrue("$name.xml references a missing drawable: ${drawable.name}", drawable.isFile)
            }
            lines.append("  mipmap-anydpi-v26/$name.xml  ->  ").append(adaptive.length()).append(" bytes, OK\n")
        }

        // An adaptive-icon background must be opaque, or the launcher shows through.
        val colors = File("src/main/res/values/colors.xml").readText()
        val background = Regex("name=\"ic_launcher_background\">(#[0-9A-Fa-f]{8})<").find(colors)
        assertTrue("colors.xml has no ic_launcher_background", background != null)
        val argb = background!!.groupValues[1].uppercase()
        assertTrue("icon background $argb must be fully opaque", argb.startsWith("#FF"))
        lines.append("  background ").append(argb).append(" (opaque)\n")
        return lines.toString()
    }

    /** Coordinates only mean anything against the 108-unit canvas they were drawn for. */
    private fun assertVectorCanvas(layer: String, text: String) {
        assertTrue("$layer must declare a 108dp square", text.contains("android:width=\"108dp\""))
        assertTrue("$layer must declare a 108dp square", text.contains("android:height=\"108dp\""))
        assertTrue("$layer viewport must be 108 wide", text.contains("android:viewportWidth=\"108\""))
        assertTrue("$layer viewport must be 108 tall", text.contains("android:viewportHeight=\"108\""))
    }

    private fun attribute(element: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(element)?.groupValues?.get(1)

    /**
     * Turns one pathData into shapes. Three idioms are accepted — the circle idiom,
     * circular arcs, and straight lines — and *anything left over fails the test*, so
     * adding a curve or a relative line to the icon forces whoever does it to extend
     * this parser (and therefore to re-check the safe zone) instead of quietly
     * escaping it.
     *
     * Arcs are the interesting case: the path only gives two endpoints and a radius,
     * so the circle they belong to is **derived** (the two candidate centres are
     * solved for and the one nearer the canvas centre is taken). That keeps the
     * centring assertion meaningful — a wheel nudged off-centre moves the derived
     * centre and fails — and the extents are then taken from the full ring, which is
     * conservative for a 120° arc.
     */
    private fun shapesOf(pathData: String, strokeWidth: Float, roundCap: Boolean, where: String): List<Shape> {
        val half = strokeWidth / 2f
        val shapes = mutableListOf<Shape>()
        var rest = pathData

        for (match in CIRCLE.findAll(pathData)) {
            val cx = match.groupValues[1].toFloat()
            val cy = match.groupValues[2].toFloat()
            val offset = match.groupValues[3].toFloat()
            val rx = match.groupValues[4].toFloat()
            // The idiom is `m-r,0 a r,r … 2r,0 a r,r … -2r,0`: two half-circles of
            // the same radius, so the two sweep lengths must be +2r and -2r.
            val firstSweep = match.groupValues[6].toFloat()
            val secondSweep = match.groupValues[7].toFloat()
            assertEquals("$where: circle idiom must start at m-r,0", -rx, offset, 1e-3f)
            assertEquals("$where: first sweep must be +2r", 2f * rx, firstSweep, 1e-3f)
            assertEquals("$where: second sweep must be -2r", -2f * rx, secondSweep, 1e-3f)
            val extent = rx + half
            shapes += Shape(
                what = "circle r=$rx",
                centreX = cx,
                centreY = cy,
                maxRadius = hypot(cx - CENTRE, cy - CENTRE) + extent,
                minX = cx - extent,
                maxX = cx + extent,
                minY = cy - extent,
                maxY = cy + extent,
            )
            rest = rest.replace(match.value, " ")
        }

        for (match in ARC.findAll(rest)) {
            val x1 = match.groupValues[1].toFloat()
            val y1 = match.groupValues[2].toFloat()
            val rx = match.groupValues[3].toFloat()
            val ry = match.groupValues[4].toFloat()
            val x2 = match.groupValues[7].toFloat()
            val y2 = match.groupValues[8].toFloat()
            assertEquals("$where: arcs must be circular (rx == ry)", rx, ry, 1e-3f)
            val (cx, cy) = arcCentre(x1, y1, x2, y2, rx, where)
            val extent = rx + half
            shapes += Shape(
                what = "arc r=$rx around (%.2f,%.2f)".format(cx, cy),
                centreX = cx,
                centreY = cy,
                maxRadius = hypot(cx - CENTRE, cy - CENTRE) + extent,
                minX = cx - extent,
                maxX = cx + extent,
                minY = cy - extent,
                maxY = cy + extent,
            )
            rest = rest.replace(match.value, " ")
        }

        for (match in LINE.findAll(rest)) {
            val x1 = match.groupValues[1].toFloat()
            val y1 = match.groupValues[2].toFloat()
            val x2 = match.groupValues[3].toFloat()
            val y2 = match.groupValues[4].toFloat()
            // Conservative: a round cap sticks out half the stroke *past* the endpoint,
            // and the stroke's own half-width can add a little more. Counting the full
            // stroke width is an over-estimate, which is the safe direction.
            val extent = strokeWidth
            val reach = max(hypot(x1 - CENTRE, y1 - CENTRE), hypot(x2 - CENTRE, y2 - CENTRE)) +
                if (roundCap) extent else half
            shapes += Shape(
                what = "line (%.0f,%.0f)-(%.0f,%.0f)".format(x1, y1, x2, y2),
                centreX = (x1 + x2) / 2f,
                centreY = (y1 + y2) / 2f,
                maxRadius = reach,
                minX = min(x1, x2) - half,
                maxX = max(x1, x2) + half,
                minY = min(y1, y2) - half,
                maxY = max(y1, y2) + half,
            )
            rest = rest.replace(match.value, " ")
        }

        val leftover = rest.replace(Regex("[\\sM]"), "")
        assertTrue("$where: unrecognised path data left over: '$leftover' (extend this parser and re-check the safe zone)", leftover.isEmpty())
        return shapes
    }

    /**
     * The circle an arc belongs to, solved from its two endpoints and its radius.
     * Two circles fit; the one nearer the canvas centre is the intended ring, so a
     * ring dragged off-centre cannot hide by picking the other solution.
     */
    private fun arcCentre(x1: Float, y1: Float, x2: Float, y2: Float, r: Float, where: String): Pair<Float, Float> {
        val dx = x2 - x1
        val dy = y2 - y1
        val chord = hypot(dx, dy)
        assertTrue("$where: arc endpoints are wider apart than its diameter", chord > 1e-3f)
        assertTrue("$where: arc endpoints are wider apart than its diameter", chord <= 2f * r + 1e-3f)
        val mx = (x1 + x2) / 2f
        val my = (y1 + y2) / 2f
        val h = sqrt(max(0f, r * r - (chord / 2f) * (chord / 2f)))
        val px = -dy / chord
        val py = dx / chord
        val cand = listOf(mx + px * h to my + py * h, mx - px * h to my - py * h)
        return cand.minByOrNull { hypot(it.first - CENTRE, it.second - CENTRE) }!!
    }

    private companion object {
        /** Canvas centre and the radius of the guaranteed-visible circle (66dp across). */
        const val CENTRE = 54f
        const val SAFE_RADIUS = 33f

        /** `M cx,cy m-r,0 a r,r 0 1,0 2r,0 a r,r 0 1,0 -2r,0` — the circle idiom used in both layers. */
        val CIRCLE = Regex(
            "M([-\\d.]+),([-\\d.]+)\\s+m([-\\d.]+),0\\s+a([-\\d.]+),([-\\d.]+)\\s+0\\s+1,0\\s+" +
                "([-\\d.]+),0\\s+a\\4,\\5\\s+0\\s+1,0\\s+([-\\d.]+),0",
        )

        /** `M x,y L x,y` straight segments. */
        val LINE = Regex("M([-\\d.]+),([-\\d.]+)\\s+L([-\\d.]+),([-\\d.]+)")

        /** `M x,y A r,r 0 0,1 x,y` circular arc (the colour wheel is drawn as three of these). */
        val ARC = Regex(
            "M([-\\d.]+),([-\\d.]+)\\s+A([-\\d.]+),([-\\d.]+)\\s+0\\s+([01]),([01])\\s+" +
                "([-\\d.]+),([-\\d.]+)",
        )
    }
}
