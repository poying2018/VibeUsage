package ai.vibecafe.usage.ui.theme

import ai.vibecafe.usage.R
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃主题的设计令牌。
 * 数值与 Web 原型（VibeUsage-redesign/index.html）逐项对齐：
 * CSS px -> dp，rgba(...) -> 0xAARRGGBB。
 *
 * 自 v2.7.0 起支持亮/暗双主题：所有颜色收敛进 [GlassPalette]，
 * 通过 [LocalGlassPalette] 在组合期解析，绘制层与文字层统一取色。
 */

/** 全局字体：思源黑体 / Source Han Sans（Noto Sans SC 开源版）。
 * 6 个静态字重（400-900），已修正 fsSelection/macStyle/name 元数据，避免厂商 ROM
 * （如 ColorOS/Realme UI 的 OPPO Sans）替换系统字体导致文字偏细；
 * 自带字体与系统 ROM 无关，MuMu 与真机渲染一致。 */
val HanSansFamily: FontFamily = FontFamily(
    Font(R.font.source_han_sans_400, FontWeight.Normal),
    Font(R.font.source_han_sans_500, FontWeight.Medium),
    Font(R.font.source_han_sans_600, FontWeight.SemiBold),
    Font(R.font.source_han_sans_700, FontWeight.Bold),
    Font(R.font.source_han_sans_800, FontWeight.ExtraBold),
    Font(R.font.source_han_sans_900, FontWeight.Black)
)

/**
 * 全局默认排版：所有未显式指定 style 的文字（裸 Text、TextField 占位/输入、
 * Material 组件内部文本）都统一使用思源黑体，且整体偏粗，保证任何文字都不会回退到系统细体。
 */
val HanSansTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        displayMedium = base.displayMedium.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        displaySmall = base.displaySmall.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        titleSmall = base.titleSmall.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        bodyMedium = base.bodyMedium.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.ExtraBold),
        bodySmall = base.bodySmall.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.Bold),
        labelSmall = base.labelSmall.copy(fontFamily = HanSansFamily, fontWeight = FontWeight.Bold)
    )
}

/**
 * 液态玻璃调色板 —— 亮/暗两套，随主题切换整体替换。
 * 绘制组件（glassCard / glassRow / 背景 / 图表）与文字统一从这里取色，
 * 保证暗色模式下玻璃质感、可读性与光效全部协调。
 */
data class GlassPalette(
    // --- 墨色 ---
    val InkHi: Color,          // 主文字
    val InkStrong: Color,      // 选中 / 强调文字
    val InkMid: Color,         // 次级文字
    val InkLo: Color,          // 弱化 / 占位

    // --- 强调色 ---
    val Accent: Color,
    val AccentInk: Color,
    val AccentWash: Color,
    val AccentRim: Color,
    val Down: Color,           // 下跌 / 警告
    val DownWash: Color,
    val DownRim: Color,

    // --- 页面与玻璃 ---
    val Page: Color,           // 页面底色
    val Surface: Color,        // 玻璃卡片底
    val SurfaceSoft: Color,    // 玻璃行 / 选择条底
    val PillTint: Color,       // 滑块玻璃
    val Rim: Color,            // 玻璃描边
    val SelectorSurface: Color, // 时间选择条底（比行更透，内容色透出）

    // --- 阴影（亮色用蓝灰，暗色用纯黑）---
    val ShadowSoft: Color,
    val ShadowBar: Color,
    val ShadowPill: Color,
    val ShadowRow: Color,
    val InnerTint: Color,      // 内阴影 / 顶部内高光

    // --- 背景光斑 ---
    val OrbPink: Color,
    val OrbBlue: Color,
    val OrbTeal: Color,
    val OrbAmber: Color,

    // --- 背景色雾 ---
    val WashPink: Color,
    val WashViolet: Color,
    val WashMint: Color,
    val WashCream: Color,

    // --- 质感细节 ---
    val SheenTop: Color,       // 玻璃卡片顶部高光（竖直渐变起始色）
    val OrbBlend: BlendMode,   // 光斑混合模式：亮色 Multiply / 暗色 Plus(发光)
    val VeilEdge: Color,       // 页面边缘晕罩（亮色提亮 / 暗色压暗）
    val VeilImage: Color       // 自定义背景图上的纱罩（保证玻璃可折射且文字可读）
)

/** 亮色液态玻璃（默认，与 Web 原型逐项对齐）。 */
val LightGlassPalette = GlassPalette(
    // --- 墨色 ---
    InkHi = Color(0xFF1A1F2B),
    InkStrong = Color(0xFF10151F),
    InkMid = Color(0xCC1E2839),         // rgba(30,40,60,.80) 保证远距可读
    InkLo = Color(0x591E2839),          // rgba(30,40,60,.35)

    // --- 强调色 ---
    Accent = Color(0xFF0BBFB0),
    AccentInk = Color(0xFF0A9B8F),
    AccentWash = Color(0x240BBFB0),     // rgba(11,191,176,.14)
    AccentRim = Color(0x4D0BBFB0),      // rgba(11,191,176,.30)
    Down = Color(0xFFD2537A),
    DownWash = Color(0x24E65A78),
    DownRim = Color(0x4DE65A78),

    // --- 页面与玻璃 ---
    Page = Color(0xFFEEF2F7),
    Surface = Color(0x8CFFFFFF),        // rgba(255,255,255,.55)
    SurfaceSoft = Color(0x80FFFFFF),    // rgba(255,255,255,.50)
    PillTint = Color(0x52FFFFFF),       // rgba(255,255,255,.32)
    Rim = Color(0x52FFFFFF),            // rgba(255,255,255,.32) 描边克制，避免生硬轮廓
    SelectorSurface = Color(0x52FFFFFF), // rgba(255,255,255,.32) 时间选择条：更透，内容色不被白化

    // --- 阴影（rgb(70,90,130) = 0x465A82）---
    ShadowSoft = Color(0x2E465A82),     // 0 30px 80px rgba(70,90,130,.18)
    ShadowBar = Color(0x29465A82),      // 0 12px 32px rgba(70,90,130,.16)
    ShadowPill = Color(0x42465A82),     // 0 8px 24px rgba(70,90,130,.22)
    ShadowRow = Color(0x1A465A82),
    InnerTint = Color(0x1F50648C),      // inset 0 2px 6px rgba(80,100,140,.12)

    // --- 背景光斑 ---
    OrbPink = Color(0xFFFF3D8B),
    OrbBlue = Color(0xFF5C7FFF),
    OrbTeal = Color(0xFF1FE0C4),
    OrbAmber = Color(0xFFFFCF5C),

    // --- 背景色雾 ---
    WashPink = Color(0xFFFFD6E8),
    WashViolet = Color(0xFFD8D2FF),
    WashMint = Color(0xFFCDF6EE),
    WashCream = Color(0xFFFFE6C9),

    // --- 质感细节 ---
    SheenTop = Color(0x20FFFFFF),       // 白 .125，若有若无的高光
    OrbBlend = BlendMode.Multiply,
    VeilEdge = Color(0x80FFFFFF),       // 白 .50 中心透明四周提亮
    VeilImage = Color(0x4DFFFFFF)       // 白 .30
)

/** 暗色液态玻璃：深夜蓝底 + 低透明白玻璃 + 高饱和发光光斑。 */
val DarkGlassPalette = GlassPalette(
    // --- 墨色（近白冷色）---
    InkHi = Color(0xFFE8EDF6),
    InkStrong = Color(0xFFFFFFFF),
    InkMid = Color(0xB3C7D2E6),         // rgba(199,210,230,.70)
    InkLo = Color(0x59C7D2E6),          // rgba(199,210,230,.35)

    // --- 强调色（提亮一档，暗底更醒目）---
    Accent = Color(0xFF31E0CF),
    AccentInk = Color(0xFF2BCBBC),
    AccentWash = Color(0x3331E0CF),     // rgba(49,224,207,.20)
    AccentRim = Color(0x4D31E0CF),      // rgba(49,224,207,.30)
    Down = Color(0xFFFF6E9B),
    DownWash = Color(0x33FF6E9B),
    DownRim = Color(0x4DFF6E9B),

    // --- 页面与玻璃（低透明白 = 深色玻璃）---
    Page = Color(0xFF0B1019),
    Surface = Color(0x14FFFFFF),        // rgba(255,255,255,.08)
    SurfaceSoft = Color(0x0FFFFFFF),    // rgba(255,255,255,.06)
    PillTint = Color(0x1AFFFFFF),       // rgba(255,255,255,.10)
    Rim = Color(0x26FFFFFF),            // rgba(255,255,255,.15) 暗色描边更含蓄
    SelectorSurface = Color(0x17FFFFFF), // rgba(255,255,255,.09) 深色下选择条更透

    // --- 阴影（暗色下阴影弱化，改用更深的黑）---
    ShadowSoft = Color(0x47000000),
    ShadowBar = Color(0x3D000000),
    ShadowPill = Color(0x52000000),
    ShadowRow = Color(0x2E000000),
    InnerTint = Color(0x12FFFFFF),      // 顶部内高光 rgba(255,255,255,.07)

    // --- 背景光斑（高饱和发光）---
    OrbPink = Color(0xFFFF4D9D),
    OrbBlue = Color(0xFF6E8CFF),
    OrbTeal = Color(0xFF22E6C8),
    OrbAmber = Color(0xFFFFC966),

    // --- 背景色雾（深色调，叠加出深邃层次）---
    WashPink = Color(0xFF3A1B33),
    WashViolet = Color(0xFF1D1F4A),
    WashMint = Color(0xFF10303C),
    WashCream = Color(0xFF332821),

    // --- 质感细节 ---
    SheenTop = Color(0x17FFFFFF),       // 白 .09，若有若无的高光
    OrbBlend = BlendMode.Plus,          // 加法混合 = 自发光
    VeilEdge = Color(0x73000000),       // 黑 .45 四周压暗
    VeilImage = Color(0x59000000)       // 黑 .35
)

/**
 * 排版令牌：只承载字体/字重/字号，颜色一律留空（Color.Unspecified），
 * 由调用处在组合期用当前调色板显式指定 —— 暗色模式才能正确取色。
 */
object GlassText {
    val Hero = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.5).sp
    )
    val Numeric = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 16.sp
    )
    val NumericSmall = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 19.sp,
        letterSpacing = (-0.4).sp
    )
    val Title = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 23.sp,
        letterSpacing = (-0.4).sp
    )
    val Body = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 14.5.sp
    )
    val Label = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp
    )
    val Meta = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp
    )
    val Section = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 12.sp,
        letterSpacing = 1.4.sp
    )
    val Chip = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 13.sp
    )
    /** 分段控件未选中态 */
    val SegmentIdle = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp
    )
    /** 分段控件选中态 */
    val SegmentActive = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp
    )
    /** 图表刻度 / 图例小字 */
    val ChartAxis = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 0.2.sp
    )
}
