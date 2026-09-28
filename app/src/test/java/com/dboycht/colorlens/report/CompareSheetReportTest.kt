package com.dboycht.colorlens.report

import com.dboycht.colorlens.color.ColorCompare
import com.dboycht.colorlens.color.ColorNamer
import com.dboycht.colorlens.color.CvdType
import com.dboycht.colorlens.color.Rgb8
import com.dboycht.colorlens.color.VisionSimulator
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renders the two-point comparison as text and as an SVG sheet.
 *
 * The text file is the part a reviewer (human or agent) can diff and quote: it
 * lists, for the classic confusion pairs, what a trichromat sees versus what each
 * deficiency type sees, plus the sentence the app would speak. The SVG is the
 * part a colour-sighted human can check at a glance — it is the only way to
 * verify that the simulation *looks* right, since a local unit test has no image
 * library (workspace memory/24 §13).
 */
class CompareSheetReportTest {

    private data class Pair(val label: String, val a: String, val b: String)

    private val pairs = listOf(
        Pair("红/绿（最经典）", "#FF0000", "#00FF00"),
        Pair("深红/棕", "#8B0000", "#8B4513"),
        Pair("深绿/黑", "#006400", "#101010"),
        Pair("绿/棕", "#4CAF50", "#795548"),
        Pair("蓝/紫", "#1E88E5", "#8A2BE2"),
        Pair("粉/灰", "#FFC0CB", "#C0C0C0"),
        Pair("橙/黄绿", "#FF8C00", "#9ACD32"),
        Pair("青/灰", "#00CED1", "#9E9E9E"),
        Pair("紫红/蓝", "#C71585", "#4169E1"),
        Pair("两种深浅不同的绿", "#43A047", "#66BB6A"),
        Pair("白/浅黄", "#FFFFFF", "#FFF9C4"),
        Pair("藏青/黑", "#000080", "#101010"),
    )

    private val types = listOf(
        CvdType.DEUTERANOMALY,
        CvdType.PROTANOMALY,
        CvdType.TRITANOMALY,
        CvdType.DEUTERANOPIA,
        CvdType.ACHROMATOPSIA,
    )

    @Test
    fun `writes the comparison sheet as text and svg`() {
        val outDir = File("build/reports/colorlens")
        assertTrue(outDir.exists() || outDir.mkdirs())

        val txt = File(outDir, "compare-sheet.txt")
        txt.writeText(buildText(), Charsets.UTF_8)

        val svg = File(outDir, "compare-sheet.svg")
        svg.writeText(buildSvg(), Charsets.UTF_8)

        assertTrue(txt.length() > 1000)
        assertTrue(svg.length() > 1000)
        println("compare sheet: ${txt.absolutePath}")
        println("compare svg  : ${svg.absolutePath}")
    }

    private fun buildText(): String {
        val sb = StringBuilder()
        sb.append("colorlens 两点对比目检 · 常人的看法 vs 你的看法\n")
        sb.append("距离单位是 OKLab，0.02 约等于刚好能分辨；「藏」表示常人看得出、你看不出\n")
        sb.append("=".repeat(150)).append('\n')
        for (pair in pairs) {
            val a = requireNotNull(Rgb8.parseHex(pair.a))
            val b = requireNotNull(Rgb8.parseHex(pair.b))
            sb.append('\n').append(pair.label).append("   ").append(pair.a).append(" vs ").append(pair.b).append('\n')
            sb.append("  ").append(ColorNamer.read(a).primaryName.padEnd(8))
                .append(" vs ").append(ColorNamer.read(b).primaryName).append('\n')
            for (type in types) {
                val v = ColorCompare.compare(a, b, type)
                sb.append("  ").append(type.label.padEnd(5))
                    .append(" 常人 ").append(fmt(v.normalDistance))
                    .append("  你 ").append(fmt(v.seenDistance))
                    .append("  明暗差 ").append(v.lightnessGapPct.toString().padStart(3)).append("%")
                    .append("  ").append(v.band.label.padEnd(5))
                    .append(if (v.hiddenFromUser) " [藏]" else "     ")
                    .append("  ").append(v.headline).append(" — ").append(v.advice)
                    .append('\n')
                sb.append("        模拟后: ")
                    .append(VisionSimulator.simulate(a, type).toHex()).append(" / ")
                    .append(VisionSimulator.simulate(b, type).toHex()).append('\n')
            }
        }
        return sb.toString()
    }

    private fun fmt(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun buildSvg(): String {
        val rows = pairs.size * types.size
        val rowH = 46
        val headH = 60
        val width = 1180
        val height = headH + rows * rowH + 20

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
        sb.append("""viewBox="0 0 $width $height" font-family="Microsoft YaHei, sans-serif">""").append('\n')
        sb.append("""<rect width="$width" height="$height" fill="#0f1218"/>""").append('\n')
        sb.append("""<text x="16" y="26" fill="#e7eaf2" font-size="17">colorlens 两点对比目检</text>""").append('\n')
        sb.append("""<text x="16" y="48" fill="#8b93a7" font-size="13">每行：左=常人看到的两色，右=该类型看到的两色（模拟后），文字=应用会说的话</text>""").append('\n')

        var y = headH
        for (pair in pairs) {
            val a = requireNotNull(Rgb8.parseHex(pair.a))
            val b = requireNotNull(Rgb8.parseHex(pair.b))
            for (type in types) {
                val seenA = VisionSimulator.simulate(a, type)
                val seenB = VisionSimulator.simulate(b, type)
                val verdict = ColorCompare.compare(a, b, type)
                sb.append("""<g transform="translate(0,$y)">""").append('\n')
                sb.append("""<text x="16" y="28" fill="#e7eaf2" font-size="13">${escape(pair.label)}</text>""").append('\n')
                sb.append("""<text x="180" y="28" fill="#8b93a7" font-size="12">${escape(type.label)}</text>""").append('\n')
                // Original pair
                sb.append("""<rect x="250" y="8" width="60" height="28" fill="${pair.a}"/>""").append('\n')
                sb.append("""<rect x="314" y="8" width="60" height="28" fill="${pair.b}"/>""").append('\n')
                // Simulated pair
                sb.append("""<rect x="400" y="8" width="60" height="28" fill="${seenA.toHex()}"/>""").append('\n')
                sb.append("""<rect x="464" y="8" width="60" height="28" fill="${seenB.toHex()}"/>""").append('\n')
                sb.append("""<text x="540" y="28" fill="#53e1c0" font-size="12">常人 ${fmt(verdict.normalDistance)} → 你 ${fmt(verdict.seenDistance)}</text>""").append('\n')
                sb.append("""<text x="760" y="28" fill="${if (verdict.hiddenFromUser) "#ffd166" else "#8b93a7"}" font-size="12">""")
                sb.append(escape(verdict.headline)).append("</text>\n")
                sb.append("</g>\n")
                y += rowH
            }
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
