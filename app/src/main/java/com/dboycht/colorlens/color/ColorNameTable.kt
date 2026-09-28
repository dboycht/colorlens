package com.dboycht.colorlens.color

/**
 * How a colour word is selected. Each basic word has exactly one role, and the
 * partition is enforced by `ColorNamerTest.every basic word has exactly one role`
 * — that test is what stops a word from silently becoming unreachable when
 * someone edits the selection rules.
 */
enum class FamilyRole {
    /** Selected by hue angle alone: 红 橙 黄 黄绿 绿 青绿 天蓝 蓝. */
    HUE,

    /** Brown: a red/orange hue that is dark or dull. Brown has no hue of its own. */
    BROWN,

    /** 米 — pale warm near-white. */
    CREAM,

    /** 粉 — pale red. */
    PINK,

    /** 紫 — 300°..350° with low chroma. */
    PURPLE,

    /** 品红 — same hue as 紫, but bright and highly chromatic. */
    MAGENTA,

    /** 灰/白/黑 — selected by the lightness ladder, never by hue. */
    GREYSCALE,
}

/**
 * The colour vocabulary the app names things with.
 *
 * ## What this table is, and what it is not
 *
 * Each entry is a **prototype**: a representative colour for a Chinese colour
 * word, and (for the modifier logic) the reference lightness/chroma of that word.
 *
 * ## Where the hex values come from
 *
 * Two citable sources only, tagged per line:
 *
 * - `CSS` — W3C "CSS Color Module Level 4" §6.1 named colours
 *   (https://www.w3.org/TR/css-color-4/#named-colors). Reproducible and standard.
 * - `ZGS` — 中国色名录 / 色谱 dataset (https://zhongguose.com/colors.json), whose
 *   page states the spectrum comes from 中科院科技情报编委会名词室《色谱》,
 *   科学出版社 1957. Used where a *Chinese* standard value is more prototypical
 *   than any CSS name (朱红, 酒红, 桃红, 米色 …).
 *
 * ⚠️ Honest caveats:
 * 1. **The prototype is chosen for naming accuracy, not because the standard
 *    "owns" the word.** E.g. 红 uses `crimson`, not the CSS extreme `red`: with
 *    pure red as the reference every dark red is numerically nearer to brown.
 *    Measured, not guessed — see DEVELOPMENT.md §"色名依据".
 * 2. **The Chinese names are this project's own choice**, informed by the
 *    linguistic sources below, not a standard.
 *
 * ## Vocabulary design (evidence-based, see DEVELOPMENT.md §"色名依据")
 *
 * - [NameTier.BASIC] = 汉语基本颜色词 plus 黄绿/青绿/天蓝, which Chinese speakers
 *   use constantly. Literature: 李红印「九色说」红黄绿蓝紫褐黑白灰 (2001);
 *   Sun & Chen 2018 (PLoS ONE) — the categories match English's eleven, but
 *   **word forms vary between speakers**, so this app converges on one preferred
 *   word per category. Any word here is safe to say out loud to a stranger.
 * - [NameTier.SPECIFIC] = 次基本层 words (朱红/藏青/翠绿…), offered only as a
 *   "更像 X" hint *in addition to* a basic word.
 * - 描述/次生层 words (藕荷/绛紫/群青/孔雀蓝/月白/钢蓝…) are **deliberately
 *   excluded**: the same research found listeners often do not share them, and a
 *   name the listener does not know is useless to someone who cannot verify the
 *   colour themselves.
 * - 青 is **not** used standalone: 《臺灣台語常用詞辭典》「青色」条 ("綠色、藍色
 *   合稱青色") and classical usage (青丝 = black hair) make it ambiguous, so the
 *   cyan/teal category is reported as 青绿.
 * - 金/银 are material colours rather than hues and are the least stable category
 *   for colour-blind users: 金 lives in SPECIFIC ("黄，更像金色") and 银 is dropped
 *   (the greyscale ladder already covers it).
 */
enum class NameTier {
    /** Safe to say to anyone. */
    BASIC,

    /** Nice to have, always reported alongside a basic word. */
    SPECIFIC,
}

/**
 * One prototype.
 *
 * @param word the Chinese name
 * @param hex prototype sRGB value, tagged `CSS` or `ZGS` in the table below
 * @param tier see [NameTier]
 * @param role how this word is selected; irrelevant for [NameTier.SPECIFIC]
 * @param aliases other names people use for the same colour (never reported)
 */
data class NameEntry(
    val word: String,
    val hex: String,
    val tier: NameTier,
    val role: FamilyRole = FamilyRole.HUE,
    val aliases: List<String> = emptyList(),
) {
    val rgb: Rgb8 by lazy {
        requireNotNull(Rgb8.parseHex(hex)) { "bad hex in colour table: $word=$hex" }
    }
    val oklab: Oklab by lazy { ColorMath.srgbToOklab(rgb) }
    val oklch: Oklch by lazy { oklab.toOklch() }
}

object ColorNameTable {

    /**
     * Basic vocabulary — the words the app may say on its own.
     *
     * Prototypes are **mid-range on purpose**: with pure `#FF0000` as 红 and pure
     * `#00FF00` as 绿, `#8B0000` was nearest to brown and `#006400` (dark green)
     * was nearest to *grey* — both measured failures, both pinned by tests.
     */
    val BASIC: List<NameEntry> = listOf(
        NameEntry("红", "#DC143C", NameTier.BASIC, FamilyRole.HUE, listOf("红色", "大红")), // CSS crimson
        NameEntry("橙", "#FFA500", NameTier.BASIC, FamilyRole.HUE, listOf("橙色", "橘色")), // CSS orange
        NameEntry("黄", "#FCD337", NameTier.BASIC, FamilyRole.HUE, listOf("黄色")), // ZGS 柠檬黄
        NameEntry("黄绿", "#9ACD32", NameTier.BASIC, FamilyRole.HUE, listOf("草绿", "橄榄绿")), // CSS yellowgreen
        NameEntry("绿", "#32CD32", NameTier.BASIC, FamilyRole.HUE, listOf("绿色")), // CSS limegreen
        NameEntry("青绿", "#00CED1", NameTier.BASIC, FamilyRole.HUE, listOf("青色", "蓝绿")), // CSS darkturquoise
        NameEntry("天蓝", "#87CEEB", NameTier.BASIC, FamilyRole.HUE, listOf("浅蓝", "蔚蓝")), // CSS skyblue
        NameEntry("蓝", "#4169E1", NameTier.BASIC, FamilyRole.HUE, listOf("蓝色")), // CSS royalblue
        NameEntry("紫", "#800080", NameTier.BASIC, FamilyRole.PURPLE, listOf("紫色")), // CSS purple
        NameEntry("品红", "#FF00FF", NameTier.BASIC, FamilyRole.MAGENTA, listOf("洋红", "玫红")), // CSS magenta
        NameEntry("粉", "#FFC0CB", NameTier.BASIC, FamilyRole.PINK, listOf("粉色", "淡红")), // CSS pink
        NameEntry("棕", "#A0522D", NameTier.BASIC, FamilyRole.BROWN, listOf("棕色", "褐色", "咖啡色")), // CSS sienna
        NameEntry("米", "#F9E9CD", NameTier.BASIC, FamilyRole.CREAM, listOf("米色", "米黄", "象牙白")), // ZGS 米色
        NameEntry("灰", "#808080", NameTier.BASIC, FamilyRole.GREYSCALE, listOf("灰色")), // CSS gray
        NameEntry("白", "#FFFFFF", NameTier.BASIC, FamilyRole.GREYSCALE, listOf("白色")), // CSS white
        NameEntry("黑", "#000000", NameTier.BASIC, FamilyRole.GREYSCALE, listOf("黑色")), // CSS black
    )

    /**
     * 次基本层 words, shown as "更像 X". Every hex is sourced, and every entry is
     * proven reachable by `ColorNamerTest.the reference colours name themselves`.
     */
    val SPECIFIC: List<NameEntry> = listOf(
        NameEntry("朱红", "#ED5126", NameTier.SPECIFIC, aliases = listOf("大红", "鲜红")), // ZGS 朱红
        NameEntry("橘红", "#F97D1C", NameTier.SPECIFIC, aliases = listOf("橙红")), // ZGS 橘橙
        NameEntry("砖红", "#CD6227", NameTier.SPECIFIC, aliases = listOf("火砖红")), // ZGS 火砖红
        NameEntry("酒红", "#62102E", NameTier.SPECIFIC, aliases = listOf("勃艮第红", "枣红")), // ZGS 葡萄酒红
        NameEntry("玫瑰红", "#D2357D", NameTier.SPECIFIC, aliases = listOf("玫红")), // ZGS 玫瑰红
        NameEntry("桃红", "#F0ADA0", NameTier.SPECIFIC), // ZGS 桃红
        NameEntry("紫红", "#C71585", NameTier.SPECIFIC), // CSS mediumvioletred
        NameEntry("土黄", "#D6A01D", NameTier.SPECIFIC), // ZGS 土黄
        NameEntry("卡其色", "#F0E68C", NameTier.SPECIFIC, aliases = listOf("卡其")), // CSS khaki
        // NOTE: 金色/银色 are deliberately absent — material colours, not hues,
        // and the least stable category for colour-blind users. 金 is reported as
        // "黄" plus its lightness instead (DEVELOPMENT.md §"色名依据").
        NameEntry("肉色", "#F7C173", NameTier.SPECIFIC, aliases = listOf("肤色")), // ZGS 肉色
        NameEntry("翠绿", "#20A162", NameTier.SPECIFIC), // ZGS 翠绿
        NameEntry("军绿", "#556B2F", NameTier.SPECIFIC, aliases = listOf("军绿色")), // CSS darkolivegreen
        NameEntry("橄榄绿", "#6B8E23", NameTier.SPECIFIC, aliases = listOf("橄榄色")), // CSS olivedrab
        NameEntry("墨绿", "#2E8B57", NameTier.SPECIFIC, aliases = listOf("海绿")), // CSS seagreen
        NameEntry("湖蓝", "#00BFFF", NameTier.SPECIFIC, aliases = listOf("深天蓝")), // CSS deepskyblue
        NameEntry("藏青", "#000080", NameTier.SPECIFIC, aliases = listOf("海军蓝", "深蓝")), // CSS navy
        NameEntry("靛蓝", "#4B0082", NameTier.SPECIFIC, aliases = listOf("靛青")), // CSS indigo
        NameEntry("巧克力", "#D2691E", NameTier.SPECIFIC, aliases = listOf("巧克力色")), // CSS chocolate
    )

    /** Everything, for the nearest-prototype search of the specific hints. */
    val ALL: List<NameEntry> = BASIC + SPECIFIC

    fun basicWords(): List<String> = BASIC.map { it.word }
}
