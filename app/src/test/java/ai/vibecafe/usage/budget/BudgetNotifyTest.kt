package ai.vibecafe.usage.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算多级提醒的口径测试。
 *
 * 50% / 80% / 100% 三档的边界、非法阈值不误报、同月不重复但可升级、
 * 跨月重新开始 —— 这些都不依赖 Context，抽成纯函数后直接锁住。
 */
class BudgetNotifyTest {

    @Test
    fun `level boundaries are inclusive`() {
        assertEquals(0, BudgetNotify.levelFor(49.99, 100.0))
        assertEquals(50, BudgetNotify.levelFor(50.0, 100.0))
        assertEquals(50, BudgetNotify.levelFor(79.99, 100.0))
        assertEquals(80, BudgetNotify.levelFor(80.0, 100.0))
        assertEquals(80, BudgetNotify.levelFor(99.99, 100.0))
        assertEquals(100, BudgetNotify.levelFor(100.0, 100.0))
        assertEquals(100, BudgetNotify.levelFor(250.0, 100.0))
    }

    @Test
    fun `invalid threshold never alerts`() {
        assertEquals(0, BudgetNotify.levelFor(1000.0, 0.0))
        assertEquals(0, BudgetNotify.levelFor(1000.0, -1.0))
        assertEquals(0, BudgetNotify.levelFor(1000.0, Double.NaN))
        assertEquals(0, BudgetNotify.levelFor(1000.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `non finite projection never alerts`() {
        assertEquals(0, BudgetNotify.levelFor(Double.NaN, 100.0))
        assertEquals(0, BudgetNotify.levelFor(Double.POSITIVE_INFINITY, 100.0))
    }

    @Test
    fun `same month suppresses equal or lower level`() {
        assertFalse(BudgetNotify.shouldNotify("2026-11", 80, "2026-11", 50))
        assertFalse(BudgetNotify.shouldNotify("2026-11", 80, "2026-11", 80))
    }

    @Test
    fun `same month allows escalation`() {
        assertTrue(BudgetNotify.shouldNotify("2026-11", 50, "2026-11", 80))
        assertTrue(BudgetNotify.shouldNotify("2026-11", 80, "2026-11", 100))
        assertTrue(BudgetNotify.shouldNotify(null, 0, "2026-11", 50))
    }

    @Test
    fun `new month resets even for lower level`() {
        assertTrue(BudgetNotify.shouldNotify("2026-10", 100, "2026-11", 50))
    }

    @Test
    fun `level zero never notifies`() {
        assertFalse(BudgetNotify.shouldNotify(null, 0, "2026-11", 0))
        assertFalse(BudgetNotify.shouldNotify("2026-11", 100, "2026-11", 0))
    }
}
