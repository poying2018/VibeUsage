package ai.vibecafe.usage.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import ai.vibecafe.usage.ui.ag.readableBrandColor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前景色可读性：品牌色 / 额度色在深浅底上都得看得见。
 *
 * 这类问题单测比肉眼看靠谱 —— 「浅灰在浅底上看不清」这种事，
 * 只有把对比度算出来才说得清。
 */
class ContrastTest {

    /** 玻璃卡片在深色主题下的实际底色（页面底 + 8% 白）。 */
    private val darkCard = Color(0xFF0B1019).compositeOver(Color(0x14FFFFFF))

    /** 浅色主题下的卡片底（页面底 + 55% 白）。 */
    private val lightCard = Color(0xFFEEF2F7).compositeOver(Color(0x8CFFFFFF))

    @Test
    fun `contrast ratio of black on white is the maximum`() {
        assertTrue(contrastRatio(Color.Black, Color.White) > 20f)
    }

    @Test
    fun `near black brand color is lifted on a dark background`() {
        val base = Color(0xFF1F2937)   // CommandCode 的近黑品牌色
        val adjusted = readableBrandColor(base, darkCard)
        // 深底上近黑等于隐形，必须被提亮到看得见的程度
        assertTrue(
            "对比度 ${contrastRatio(adjusted, darkCard)} 应 ≥ 3",
            contrastRatio(adjusted, darkCard) >= 3f
        )
        assertTrue(adjusted.luminance() > base.luminance())
    }

    @Test
    fun `near black brand color stays dark on a light background`() {
        val base = Color(0xFF1F2937)
        val adjusted = readableBrandColor(base, lightCard)
        // 浅底上近黑本来就看得见，不该被提亮成浅灰（那才是隐形）
        assertTrue(
            "浅底上近黑应保持原样",
            contrastRatio(adjusted, lightCard) >= 3f
        )
        assertTrue(adjusted.luminance() <= base.luminance() + 0.01f)
    }

    @Test
    fun `light grey brand color is darkened on a light background`() {
        val base = Color(0xFFA8B3C5)   // Kimi 的浅灰品牌色
        val adjusted = readableBrandColor(base, lightCard)
        assertTrue(
            "对比度 ${contrastRatio(adjusted, lightCard)} 应 ≥ 3",
            contrastRatio(adjusted, lightCard) >= 3f
        )
        assertTrue(adjusted.luminance() < base.luminance())
    }

    @Test
    fun `light grey brand color is left alone on a dark background`() {
        val base = Color(0xFFA8B3C5)
        val adjusted = readableBrandColor(base, darkCard)
        assertTrue(
            "深底上浅灰本来就清晰，不该被改动",
            contrastRatio(adjusted, darkCard) >= 3f
        )
    }

    @Test
    fun `ensureContrast keeps a color that already passes`() {
        val vivid = Color(0xFF31E0CF)
        assertTrue(vivid.ensureContrast(darkCard) == vivid)
    }

    @Test
    fun `ensureContrast reaches the requested ratio`() {
        val pale = Color(0xFFFFF0F5)
        val adjusted = pale.ensureContrast(Color(0xFFEEF2F7), minRatio = 4.5f)
        assertTrue(contrastRatio(adjusted, Color(0xFFEEF2F7)) >= 4.5f)
    }
}
