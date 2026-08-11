package ai.vibecafe.usage.ui.theme

import ai.vibecafe.usage.R
import androidx.compose.material3.Typography
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

object Glass {
    // --- 墨色 ---
    val InkHi = Color(0xFF1A1F2B)          // --ink-hi
    val InkStrong = Color(0xFF10151F)      // .range-btn.active
    val InkMid = Color(0xCC1E2839)         // --ink-mid  加深：rgba(30,40,60,.80) 保证远距可读
    val InkLo = Color(0x591E2839)          // --ink-lo   rgba(30,40,60,.35)

    // --- 强调色 ---
    val Accent = Color(0xFF0BBFB0)         // --accent
    val AccentInk = Color(0xFF0A9B8F)
    val AccentWash = Color(0x240BBFB0)     // rgba(11,191,176,.14)
    val AccentRim = Color(0x4D0BBFB0)      // rgba(11,191,176,.30)
    val Down = Color(0xFFD2537A)
    val DownWash = Color(0x24E65A78)
    val DownRim = Color(0x4DE65A78)

    // --- 页面与玻璃 ---
    val Page = Color(0xFFEEF2F7)
    val Surface = Color(0x8CFFFFFF)        // rgba(255,255,255,.55) 卡片底
    val SurfaceSoft = Color(0x80FFFFFF)    // rgba(255,255,255,.50) 选择条 / 行
    val PillTint = Color(0x52FFFFFF)       // rgba(255,255,255,.32) 滑块
    val Rim = Color(0xB3FFFFFF)            // rgba(255,255,255,.70) 描边

    // --- 阴影（rgb(70,90,130) = 0x465A82）---
    val ShadowSoft = Color(0x2E465A82)     // 0 30px 80px rgba(70,90,130,.18)
    val ShadowBar = Color(0x29465A82)      // 0 12px 32px rgba(70,90,130,.16)
    val ShadowPill = Color(0x42465A82)     // 0 8px 24px rgba(70,90,130,.22) + 「再加一丢丢」
    val ShadowRow = Color(0x1A465A82)
    val InnerTint = Color(0x1F50648C)      // inset 0 2px 6px rgba(80,100,140,.12)

    // --- 背景光斑（对应 .orb）---
    val OrbPink = Color(0xFFFF3D8B)
    val OrbBlue = Color(0xFF5C7FFF)
    val OrbTeal = Color(0xFF1FE0C4)
    val OrbAmber = Color(0xFFFFCF5C)

    // --- 背景径向渐变 ---
    val WashPink = Color(0xFFFFD6E8)
    val WashViolet = Color(0xFFD8D2FF)
    val WashMint = Color(0xFFCDF6EE)
    val WashCream = Color(0xFFFFE6C9)
}

object GlassText {
    val Hero = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.5).sp,
        color = Glass.InkHi
    )
    val Numeric = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 16.sp,
        color = Glass.InkHi
    )
    val NumericSmall = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 19.sp,
        letterSpacing = (-0.4).sp,
        color = Glass.InkHi
    )
    val Title = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 23.sp,
        letterSpacing = (-0.4).sp,
        color = Glass.InkHi
    )
    val Body = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 14.5.sp,
        color = Glass.InkHi
    )
    val Label = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        color = Glass.InkMid
    )
    val Meta = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
        color = Glass.InkMid
    )
    val Section = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 12.sp,
        letterSpacing = 1.4.sp,
        color = Glass.InkMid
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
        fontSize = 14.sp,
        color = Color(0xB31E2839)   // rgba(30,40,60,.70)
    )
    /** 分段控件选中态 */
    val SegmentActive = TextStyle(
        fontFamily = HanSansFamily,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp,
        color = Glass.InkStrong
    )
}
