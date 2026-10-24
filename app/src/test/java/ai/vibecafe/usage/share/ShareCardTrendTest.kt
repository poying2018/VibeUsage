package ai.vibecafe.usage.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ShareCardTrendTest {

    /**
     * trendText 内部已经显式用 Locale.US 格式化，这里**不需要**再改全局默认 Locale。
     * 早先的 Locale.setDefault(Locale.US) 从不恢复，是进程级副作用：
     * 会影响其它测试类里用 Locale.getDefault() 的格式化（如 compact()），
     * 让结果依赖测试执行顺序（在小数点为逗号的 locale 上更会直接跑挂）。
     */
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun rememberLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `up trend renders teal arrow plus percent`() {
        assertEquals("↗ +12%", ShareCard.trendText(0.1234f))
        assertEquals("↗ +1%", ShareCard.trendText(0.005f))
    }

    @Test
    fun `down trend renders rose arrow minus percent`() {
        assertEquals("↘ -8%", ShareCard.trendText(-0.08f))
    }

    @Test
    fun `near zero trend reads flat`() {
        assertEquals("→ 持平", ShareCard.trendText(0.004f))
        assertEquals("→ 持平", ShareCard.trendText(-0.004f))
        assertEquals("→ 持平", ShareCard.trendText(0f))
    }

    @Test
    fun `non finite trend reads flat instead of overflowing`() {
        assertEquals("→ 持平", ShareCard.trendText(Float.NaN))
        assertEquals("→ 持平", ShareCard.trendText(Float.POSITIVE_INFINITY))
        assertEquals("→ 持平", ShareCard.trendText(Float.NEGATIVE_INFINITY))
    }
}
