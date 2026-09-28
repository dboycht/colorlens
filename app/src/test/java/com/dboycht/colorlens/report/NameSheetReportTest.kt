package com.dboycht.colorlens.report

import com.dboycht.colorlens.color.ColorNamer
import com.dboycht.colorlens.color.ColorReading
import com.dboycht.colorlens.color.Rgb8
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renders the app's naming decisions as a **sheet of swatches** so a human can
 * check them at a glance.
 *
 * Why this exists: local Android unit tests run with `-no-jdk` and have no
 * `java.awt`/`javax.imageio`, so a test cannot write a PNG (workspace memory/24
 * §13). The test therefore emits **text (SVG + HTML)** into
 * `app/build/reports/colorlens/`, and the host turns it into a PNG:
 *
 * ```
 * chrome --headless=new --disable-gpu --force-device-scale-factor=2 \
 *        --window-size=1280,<h> --screenshot=name-sheet.png \
 *        file:///<project>/app/build/reports/colorlens/name-sheet.svg
 * ```
 *
 * Every colour the app names must be checkable this way — that is the whole point
 * of the artifact: it is the only evidence a human can audit without a phone.
 */
class NameSheetReportTest {

    /** Colours chosen to cover every basic word plus every classic CVD confusion. */
    private val samples: List<Pair<String, String>> = listOf(
        "纯红" to "#FF0000",
        "暗红" to "#8B0000",
        "砖红" to "#CD6227",
        "酒红" to "#62102E",
        "粉" to "#FFC0CB",
        "桃红" to "#F0ADA0",
        "旧粉" to "#B08080",
        "品红" to "#FF00FF",
        "橙" to "#FFA500",
        "橘红" to "#FF4500",
        "琥珀" to "#FFBF00",
        "金黄" to "#FFD700",
        "黄" to "#FFFF00",
        "土黄" to "#D6A01D",
        "卡其" to "#F0E68C",
        "黄绿" to "#9ACD32",
        "亮绿" to "#00FF00",
        "翠绿" to "#20A162",
        "墨绿" to "#2E8B57",
        "深绿" to "#006400",
        "军绿" to "#556B2F",
        "橄榄" to "#6B8E23",
        "青绿" to "#00CED1",
        "湖蓝" to "#00BFFF",
        "天蓝" to "#87CEEB",
        "宝蓝" to "#4169E1",
        "蓝" to "#0000FF",
        "藏青" to "#000080",
        "靛蓝" to "#4B0082",
        "紫" to "#800080",
        "紫罗兰" to "#8A2BE2",
        "藕荷" to "#D8BFD8",
        "棕" to "#A52A2A",
        "巧克力" to "#D2691E",
        "米" to "#F5F5DC",
        "肉色" to "#F7C173",
        "浅灰" to "#E0E0E0",
        "中灰" to "#808080",
        "深灰" to "#303030",
        "炭黑" to "#101010",
        "白" to "#FFFFFF",
    )

    @Test
    fun `writes the name sheet as svg and html`() {
        val outDir = File("build/reports/colorlens")
        assertTrue("could not create ${outDir.absolutePath}", outDir.exists() || outDir.mkdirs())

        val svg = buildSvg()
        val svgFile = File(outDir, "name-sheet.svg")
        svgFile.writeText(svg, Charsets.UTF_8)

        val htmlFile = File(outDir, "index.html")
        htmlFile.writeText(
            """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <title>colorlens 色名表目检</title>
            <style>
              body { background:#0f1218; color:#e7eaf2; font-family: "Microsoft YaHei", sans-serif; padding:16px; }
              h1 { font-size:18px } a { color:#53e1c0 } li { margin:4px 0 }
            </style></head><body>
            <h1>colorlens 色名表目检</h1>
            <ul>
              <li><a href="name-sheet.svg">name-sheet.svg</a> — 命名结果色卡（本页截图就是它）</li>
            </ul>
            <img src="name-sheet.svg" width="1280">
            </body></html>
            """.trimIndent(),
            Charsets.UTF_8,
        )

        assertTrue("svg was not written", svgFile.length() > 500)
        println("name sheet: ${svgFile.absolutePath}")

        // A plain-text mirror of the sheet. The SVG/PNG is for human eyes; this is
        // what a *reader* (agent or reviewer) can actually diff and quote, and it
        // is the artifact that makes regressions in naming visible in review.
        val txtFile = File(outDir, "name-sheet.txt")
        txtFile.writeText(buildTextReport(), Charsets.UTF_8)
        assertTrue("text report was not written", txtFile.length() > 500)
        println("name sheet text: ${txtFile.absolutePath}")
    }

    private fun buildTextReport(): String {
        val sb = StringBuilder()
        sb.append("colorlens 色名表目检 · 每个采样色的完整读数\n")
        sb.append("格式: 标签 | #HEX | 主名 | 基础词 | 修饰 | 更像 | 介于 | 明度% 鲜艳度% 色相° | 冷暖 | 告诉别人\n")
        sb.append("-".repeat(150)).append('\n')
        for ((label, hex) in samples) {
            val rgb = requireNotNull(Rgb8.parseHex(hex))
            val r = ColorNamer.read(rgb)
            sb.append(label.padEnd(6)).append(" | ")
                .append(hex).append(" | ")
                .append(r.primaryName.padEnd(6)).append(" | ")
                .append(r.baseWord.padEnd(4)).append(" | ")
                .append((r.modifier ?: "-").padEnd(3)).append(" | ")
                .append((r.specificHint ?: "-").padEnd(8)).append(" | ")
                .append((r.alternatives.joinToString("、").ifEmpty { "-" }).padEnd(8)).append(" | ")
                .append(r.lightnessPct).append("% ").append(r.chromaPct).append("% ")
                .append(r.hueDeg.toInt()).append("° | ")
                .append(r.warmth.label).append(" | ")
                .append(r.tellOthers).append('\n')
            sb.append("       说: ").append(r.speakText).append('\n')
            sb.append("       述: ").append(r.description).append('\n')
        }
        return sb.toString()
    }

    private fun buildSvg(): String {
        val cols = 4
        val cellW = 320
        val cellH = 108
        val rows = (samples.size + cols - 1) / cols
        val width = cols * cellW
        val height = rows * cellH + 44

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
        sb.append("""viewBox="0 0 $width $height" font-family="Microsoft YaHei, sans-serif">""").append('\n')
        sb.append("""<rect width="$width" height="$height" fill="#0f1218"/>""").append('\n')
        sb.append("""<text x="16" y="28" fill="#e7eaf2" font-size="18">""")
        sb.append("colorlens 命名表目检 · 每格：色块 / 应用报出的名字 / hex 与实际像素</text>").append('\n')

        samples.forEachIndexed { index, (label, hex) ->
            val rgb = requireNotNull(Rgb8.parseHex(hex))
            val reading = ColorNamer.read(rgb)
            val x = (index % cols) * cellW
            val y = 44 + (index / cols) * cellH
            val textColor = if (rgb.isDark()) "#FFFFFF" else "#101319"
            val subColor = if (rgb.isDark()) "#D8DEE9" else "#333A46"

            sb.append("""<g transform="translate($x,$y)">""").append('\n')
            sb.append("""<rect x="8" y="8" width="${cellW - 16}" height="${cellH - 16}" rx="10" fill="$hex"/>""")
            sb.append('\n')
            sb.append("""<text x="22" y="42" fill="$textColor" font-size="24" font-weight="bold">""")
            sb.append(escape(reading.primaryName)).append("</text>\n")
            sb.append("""<text x="22" y="66" fill="$subColor" font-size="14">""")
            sb.append(escape(hintOf(reading))).append("</text>\n")
            sb.append("""<text x="22" y="86" fill="$subColor" font-size="12">""")
            sb.append(escape("$label $hex  明度${reading.lightnessPct}% 鲜艳度${reading.chromaPct}%"))
            sb.append("</text>\n")
            sb.append("</g>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun hintOf(reading: ColorReading): String {
        val parts = mutableListOf<String>()
        reading.specificHint?.let { parts.add("更像$it") }
        if (reading.alternatives.isNotEmpty()) {
            parts.add("介于${reading.baseWord}和${reading.alternatives.joinToString("、")}之间")
        }
        if (parts.isEmpty()) parts.add(reading.description)
        return parts.joinToString("；")
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
