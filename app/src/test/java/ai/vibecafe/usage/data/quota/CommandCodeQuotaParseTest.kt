package ai.vibecafe.usage.data.quota

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommandCode 额度解析。
 *
 * 样本是 2026-10 用真实账号（user_ 开头 API Key）实测抓到的响应原文：
 * `/alpha/billing/credits` 与 `/alpha/usage/summary` 的字段口径以这份样本为准。
 *
 * 两条重要的接口契约（官方 harness 的注释里写明）：
 * 1. `credits` / `subscriptions` / `usage/summary` 都需要 org owner/admin，
 *    普通成员会 403 —— 所以解析必须能在「只有 whoami 成功」时不崩。
 * 2. `windowLimits` 里的 5 小时 / 每周窗口是**滚动**上限，cap 是次数/额度，
 *    used 是已用，比例按 (cap - used) / cap 算。
 */
class CommandCodeQuotaParseTest {

    /** 2026-10 实测：/alpha/billing/credits 原文 */
    private val CREDITS_LIVE =
        """{"credits":{"belowThreshold":false,"creditThreshold":0,"monthlyCredits":7.2595304034,"purchasedCredits":0,"freeCredits":0},"windowLimits":{"limited":true,"exceeded":null,"fiveHour":{"used":0,"cap":3,"exceeded":false,"resetAt":0},"weekly":{"used":1.7932501153,"cap":6,"exceeded":false,"resetAt":1792977253808}},"sandboxAccess":false,"sandboxMinutes":null}"""

    /** 2026-10 实测：/alpha/usage/summary 原文 */
    private val SUMMARY_LIVE =
        """{"totalCount":3496,"totalCost":2.7403430966,"averageCost":0.0007838510001716248,"successRate":100,"completedCount":3496,"failedCount":0,"totalTokensIn":1143936672,"totalTokensOut":2438265,"totalTokens":1146374937,"totalCredits":2.7403430966,"totalFreeCredits":0,"totalMonthlyCredits":2.7403430966,"totalPurchasedCredits":0,"periodBasis":"billing-period","periodStart":"2026-10-01T23:41:59.000Z","daily":[]}"""

    private fun obj(json: String): JsonObject =
        com.google.gson.Gson().fromJson(json, JsonObject::class.java)

    @Test
    fun `window limits render as remaining percent`() {
        val groups = CommandCode.buildGroups(obj(CREDITS_LIVE), obj(SUMMARY_LIVE))
        val windows = groups["额度窗口"]
        assertNotNull("应有额度窗口分组", windows)
        assertEquals(2, windows!!.size)

        // 5 小时窗口：used=0 / cap=3 → 满格
        val fiveHour = windows[0]
        assertEquals("5 小时窗口", fiveHour.label)
        assertEquals(100, fiveHour.percentRemaining)
        assertEquals(0, fiveHour.usedPercent)

        // 每周窗口：used=1.793 / cap=6 → 剩余约 70%
        val weekly = windows[1]
        assertEquals("每周窗口", weekly.label)
        assertEquals(70, weekly.percentRemaining)
        assertEquals(30, weekly.usedPercent)
        assertNotNull(weekly.counts)
        // resetAt=1792977253808 是未来的重置时刻 → 应有倒计时文案
        assertNotNull("每周窗口应有重置倒计时", weekly.reset)
        // 5 小时窗口的 resetAt=0（服务端未下发）→ 不显示重置
        assertNull("resetAt 为 0 时不应显示重置", fiveHour.reset)
    }

    @Test
    fun `credits and usage summary render as separate groups`() {
        val groups = CommandCode.buildGroups(obj(CREDITS_LIVE), obj(SUMMARY_LIVE))

        val credit = groups["套餐额度"]
        assertNotNull("应有套餐额度分组", credit)
        assertEquals("Credits", credit!!.single().label)
        // total = monthly 7.2595 + purchased 0 + free 0；used = totalCredits 2.7403
        assertEquals(62, credit.single().percentRemaining)

        val usage = groups["本周期用量"]
        assertNotNull("应有本周期用量分组", usage)
        assertEquals("Tokens", usage!!.single().label)
        // 11.46 亿 tokens → 11.5亿
        assertTrue("用量文案应含 tokens：${usage.single().counts}", usage.single().counts!!.contains("tokens"))
    }

    @Test
    fun `member account without billing permission still parses without crashing`() {
        // 普通成员：credits / summary 都是 403 → 传 null，不能抛异常
        val groups = CommandCode.buildGroups(null, null)
        assertTrue("没有任何可展示数据时分组应为空", groups.isEmpty())
    }

    @Test
    fun `partial response keeps available groups only`() {
        // 只有窗口额度、没有 usage summary：窗口仍要出，套餐额度的 used 记 0
        val groups = CommandCode.buildGroups(obj(CREDITS_LIVE), null)
        assertNotNull(groups["额度窗口"])
        val credit = groups["套餐额度"]
        assertNotNull(credit)
        assertEquals("没有 summary 时已用按 0 算", 100, credit!!.single().percentRemaining)
    }

    @Test
    fun `zero cap window is not rendered`() {
        // cap=0 的窗口没有意义（服务端不下发这种，但解析要挡住）
        val zero = """{"windowLimits":{"fiveHour":{"used":0,"cap":0},"weekly":{"used":1,"cap":0}}}"""
        val groups = CommandCode.buildGroups(obj(zero), null)
        assertTrue("cap 为 0 的窗口不应渲染", groups["额度窗口"].isNullOrEmpty())
    }

    @Test
    fun `member account without billing permission yields an empty but non crashing result`() {
        // 普通成员：credits / summary 都是 403 → 分组为空（fetchUsage 据此给出
        // 「需要管理员权限」的提示文案，而不是让用户看到空白）
        val groups = CommandCode.buildGroups(null, null)
        assertTrue("门禁接口全不可用时分组应为空", groups.isEmpty())
    }
}
