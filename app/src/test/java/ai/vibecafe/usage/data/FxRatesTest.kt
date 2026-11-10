package ai.vibecafe.usage.data

import ai.vibecafe.usage.ui.formatCost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 汇率解析 + 人民币格式化回归测试。
 *
 * 要锁的口径：
 * 1. er-api / exchangerate-api 两种形状都能解出 CNY；
 * 2. 脏值（缺失/非数字/离谱带）返回 null，绝不污染当前汇率；
 * 3. 金额按当前汇率换成 ¥，分档与原来一致。
 */
class FxRatesTest {

    private var prevRate: Double = FxRates.usdToCny

    @After
    fun restoreRate() {
        FxRates.usdToCny = prevRate
    }

    @Test
    fun `er-api shape parses`() {
        assertEquals(
            6.686107,
            FxRates.parse("""{"result":"success","base_code":"USD","rates":{"CNY":6.686107,"EUR":0.9}}""")!!,
            0.0001
        )
    }

    @Test
    fun `exchangerate-api shape parses`() {
        assertEquals(
            6.69,
            FxRates.parse("""{"base":"USD","rates":{"USD":1,"CNY":6.69}}""")!!,
            0.0001
        )
    }

    @Test
    fun `garbage and out of range return null`() {
        assertNull(FxRates.parse("not json"))
        assertNull(FxRates.parse("""{"rates":{}}"""))
        assertNull(FxRates.parse("""{"rates":{"CNY":"6.7"}}"""))
        assertNull(FxRates.parse("""{"rates":{"CNY":0}}"""))
        assertNull(FxRates.parse("""{"rates":{"CNY":100}}"""))
    }

    @Test
    fun `conversion uses current rate`() {
        FxRates.usdToCny = 7.0
        assertEquals(70.0, FxRates.convert(10.0), 0.0001)
    }

    @Test
    fun `manual rate wins over cached auto rate`() {
        assertEquals(7.1, FxRates.preferredRate(7.1, 6.9), 0.0001)
        assertEquals(6.9, FxRates.preferredRate(0.0, 6.9), 0.0001)
        assertEquals(6.9, FxRates.preferredRate(100.0, 6.9), 0.0001)
        assertEquals(FxRates.DEFAULT_USD_CNY, FxRates.preferredRate(0.0, 100.0), 0.0001)
    }

    @Test
    fun `manual rate is clamped to the sane band`() {
        assertEquals(FxRates.MIN_USD_CNY, FxRates.clampManualRate(4.9), 0.0001)
        assertEquals(FxRates.MAX_USD_CNY, FxRates.clampManualRate(9.1), 0.0001)
        assertEquals(6.6861, FxRates.clampManualRate(6.6861), 0.0001)
        assertEquals(FxRates.DEFAULT_USD_CNY, FxRates.clampManualRate(Double.NaN), 0.0001)
        assertEquals(
            FxRates.DEFAULT_USD_CNY,
            FxRates.clampManualRate(Double.POSITIVE_INFINITY),
            0.0001
        )
    }

    @Test
    fun `formatCost shows cny with tiered decimals`() {
        FxRates.usdToCny = 7.0
        assertEquals("¥70.00", formatCost(10.0))
        assertEquals("¥700.0", formatCost(100.0))
        assertEquals("¥1400", formatCost(200.0))
        assertEquals("¥0.00", formatCost(0.0))
    }
}
