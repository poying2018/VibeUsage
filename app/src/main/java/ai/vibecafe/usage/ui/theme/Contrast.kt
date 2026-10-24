package ai.vibecafe.usage.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * WCAG 对比度：两个颜色的相对亮度之比（1.0 ~ 21.0）。
 *
 * 正文级别的可读性一般要求 ≥ 4.5，图标 / 大号数字这类非正文元素 ≥ 3.0 就够。
 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * 把这个颜色调整到与背景的对比度不低于 [minRatio]。
 *
 * 调整方向只有一个：**远离背景**。浅底就往黑里压，深底就往白里提，
 * 色相和饱和度按比例保留 —— 品牌色不会因为可读性调整就变成另一家的颜色。
 *
 * 为什么不用「亮度低于 X 就提亮、高于 Y 就压暗」这种固定阈值：
 * 那样必须为深底 / 浅底各写一套分支，而各平台的品牌色深浅跨度极大
 * （CommandCode 近黑 #1F2937、Kimi 浅灰 #A8B3C5 差了 20 倍亮度），
 * 固定阈值要么救不回来、要么把本来正常的颜色也改掉。
 * 直接对着背景算对比度则与具体色值无关，一套规则覆盖所有品牌色和两种主题。
 */
fun Color.ensureContrast(background: Color, minRatio: Float = 3.0f): Color {
    if (contrastRatio(this, background) >= minRatio) return this
    // 目标极值：浅底压到接近黑，深底提到接近白
    val target = if (background.luminance() < 0.5f) Color.White else Color.Black
    var t = 0.1f
    var adjusted = this
    while (t <= 1.001f) {
        adjusted = lerp(this, target, t)
        if (contrastRatio(adjusted, background) >= minRatio) return adjusted
        t += 0.1f
    }
    return adjusted
}

/**
 * 卡片实际呈现的颜色：半透明玻璃面叠在页面底色之上。
 *
 * 玻璃卡片不是纯色，拿 `Surface` 直接当背景算对比度会算偏；
 * 合成后的实际颜色才是文字和图标真正压着的那层底。
 */
fun GlassPalette.cardBackground(): Color = Surface.compositeOver(Page)
