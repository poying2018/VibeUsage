package ai.vibecafe.usage.data.quota

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenCode Go 额度解析回归测试（对官方 OpenAPI 契约 /inference/go/v1/usage 的形状）。
 *
 * 要锁的口径：
 * 1. percent 是**已用**，画条要取反成 percentRemaining；
 * 2. rolling/weekly/monthly → 「5 小时 / 每周 / 每月窗口」的组名映射（带「窗口」才会被总览趋势选中）；
 * 3. 缺 percent 的窗口整档跳过 —— 缺省成 0 会画出一条「满额度」的假条，比不画更误导；
 * 4. status=rate-limited 要显式可见，不能只剩一条 0% 看不出原因。
 */
class OpenCodeQuotaParseTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    /** 契约形状：三档窗口，percent 为已用，resetsAt 为 ISO8601。 */
    private val THREE_WINDOWS = json(
        """
        {"usage":{
          "rolling":{"status":"ok","percent":62,"resetsAt":"2026-11-04T18:30:00Z"},
          "weekly":{"status":"ok","percent":15,"resetsAt":"2026-11-08T16:00:00Z"},
          "monthly":{"status":"rate-limited","percent":100,"resetsAt":"2026-12-01T00:00:00Z"}
        }}
        """
    )

    @Test
    fun `maps rolling weekly monthly to window groups`() {
        val groups = OpenCode.buildGroups(THREE_WINDOWS)
        assertEquals(listOf("5 小时窗口", "每周窗口", "每月窗口"), groups.keys.toList())
    }

    @Test
    fun `percent is used and must be inverted for remaining`() {
        val bar = OpenCode.buildGroups(THREE_WINDOWS).getValue("5 小时窗口").first()
        assertEquals(62, bar.usedPercent)
        assertEquals(38, bar.percentRemaining)
    }

    @Test
    fun `rate-limited status is shown in counts`() {
        val bar = OpenCode.buildGroups(THREE_WINDOWS).getValue("每月窗口").first()
        assertEquals("已限流", bar.counts)
        assertEquals(0, bar.percentRemaining)
    }

    @Test
    fun `reset uses the ISO resetsAt field`() {
        // 未来时刻（相对测试编写时的 2026-11-04）→ 「… 重置 · X后」
        val bar = OpenCode.buildGroups(THREE_WINDOWS).getValue("每周窗口").first()
        assertNotNull(bar.reset)
        assertTrue("重置文案应以 11-09 00:00 开头，实际=${bar.reset}", bar.reset!!.startsWith("11-09 00:00 重置"))
    }

    @Test
    fun `missing percent skips that window instead of drawing a fake full bar`() {
        val obj = json("""{"usage":{"weekly":{"status":"ok","resetsAt":"2026-11-08T16:00:00Z"}}}""")
        // 没有 percent → 整档不出现（而不是画成已用 0%、满额度）
        assertTrue(OpenCode.buildGroups(obj).isEmpty())
    }

    @Test
    fun `windows arrive independently and out-of-order keys keep display order`() {
        val obj = json("""{"usage":{"monthly":{"percent":50,"status":"ok"},"rolling":{"percent":10,"status":"ok"}}}""")
        // monthly 在 JSON 里排前面，但组序仍按固定窗口表：5 小时 → 每月；weekly 缺席不占位
        assertEquals(listOf("5 小时窗口", "每月窗口"), OpenCode.buildGroups(obj).keys.toList())
    }

    @Test
    fun `absent usage object yields empty groups not a crash`() {
        assertTrue(OpenCode.buildGroups(json("""{}""")).isEmpty())
        assertTrue(OpenCode.buildGroups(json("""{"usage":null}""")).isEmpty())
    }

    @Test
    fun `percent may come as a string and still parse`() {
        val obj = json("""{"usage":{"rolling":{"percent":"25.5","status":"ok"}}}""")
        val bar = OpenCode.buildGroups(obj).getValue("5 小时窗口").first()
        assertEquals(25, bar.usedPercent)
        assertEquals(75, bar.percentRemaining)
        assertNull(bar.reset)
    }
}
