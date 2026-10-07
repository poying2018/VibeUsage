package ai.vibecafe.usage.data.quota

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiniMax Token Plan 额度解析。
 *
 * TOKEN_PLAN_LIVE 是 /v1/token_plan/remains 的真实响应原文（2026-10 抓自 sk-cp- 订阅密钥）。
 * 这份样本教出了三件事，都不要再按猜测改回去：
 *
 * 1. 端点是 /v1/token_plan/remains。老的 /v1/api/openplatform/coding_plan/remains 现在只认网页登录
 *    Cookie，拿 API Key 打过去恒返回 1004「cookie is missing, log in again」。
 * 2. **所有 current_*_count 恒为 0**，额度改成服务端预算好的
 *    current_interval_remaining_percent / current_weekly_remaining_percent。只按 count 换算会一条都渲染不出来。
 * 3. model_name 不再是模型 id，而是额度分桶名（general / video）。
 *
 * LEGACY_COUNTS 是旧形态样本：那时没有 percent 字段，只有 count，且 usage_count 实为「剩余次数」
 * （对照控制台「已用 3%」时返回 usage_count=1459 / total=1500）。
 */
class MiniMaxQuotaParseTest {

    /** 现行 Token Plan 形态：count 全 0，percent 97 / 77（general）与 100 / 100（video，未用过）。 */
    private val TOKEN_PLAN_LIVE =
        """{"model_remains":[{"start_time":1784386800000,"end_time":1784404800000,"remains_time":4878202,"current_interval_total_count":0,"current_interval_usage_count":0,"model_name":"general","current_weekly_total_count":0,"current_weekly_usage_count":0,"weekly_start_time":1783900800000,"weekly_end_time":1784505600000,"weekly_remains_time":105678202,"current_interval_status":1,"current_interval_remaining_percent":97,"current_weekly_status":1,"current_weekly_remaining_percent":77},{"start_time":1784332800000,"end_time":1784419200000,"remains_time":19278202,"current_interval_total_count":0,"current_interval_usage_count":0,"model_name":"video","current_weekly_total_count":0,"current_weekly_usage_count":0,"weekly_start_time":1783900800000,"weekly_end_time":1784505600000,"weekly_remains_time":105678202,"current_interval_status":3,"current_interval_remaining_percent":100,"current_weekly_status":3,"current_weekly_remaining_percent":100}],"base_resp":{"status_code":0,"status_msg":"success"}}"""

    /** 旧形态：没有 percent 字段，只有 count；usage_count=1459/1500 即「已用 3%」。 */
    private val LEGACY_COUNTS =
        """{"model_remains":[{"model_name":"MiniMax-M2","current_interval_total_count":1500,"current_interval_usage_count":1459,"remains_time":11700000,"end_time":1784404800000,"current_weekly_total_count":10000,"current_weekly_usage_count":8000}],"base_resp":{"status_code":0,"status_msg":"ok"}}"""

    /** 跨大区用错 key：这正是国内站在前、国际站兜底要覆盖的情况。 */
    private val ERR_WRONG_REGION =
        """{"base_resp":{"status_code":2049,"status_msg":"invalid api key"}}"""

    /** 订阅了但两个窗口都没下发任何可用字段——不能编一个假的 100%。 */
    private val ALL_ZERO_NO_PERCENT =
        """{"model_remains":[{"model_name":"general","current_interval_total_count":0,"current_interval_usage_count":0,"current_weekly_total_count":0,"current_weekly_usage_count":0}],"base_resp":{"status_code":0,"status_msg":"success"}}"""

    private val NO_REMAINS = """{"base_resp":{"status_code":0,"status_msg":"success"}}"""

    // ─── 现行 Token Plan 形态（本单修复的主路径）───

    @Test
    fun `token plan renders bars from remaining percent even though counts are all zero`() {
        val usage = MiniMax.parseRemains(TOKEN_PLAN_LIVE)

        assertEquals(listOf("5 小时窗口", "每周窗口"), usage.groups.keys.toList())

        val interval = usage.groups.getValue("5 小时窗口")
        assertEquals(2, interval.size)
        // 分桶名 general → 可读标签，不再把桶名当模型名直接抛给 UI
        assertEquals("通用（文本/图像/音频）", interval[0].label)
        assertEquals(97, interval[0].percentRemaining)
        assertEquals(3, interval[0].usedPercent)
        // count 恒为 0，所以不显示「剩 0/0 次」这种占位
        assertNull(interval[0].counts)
        assertTrue(interval[0].reset!!.endsWith("· 1时21分后"))

        assertEquals("视频", interval[1].label)
        assertEquals(100, interval[1].percentRemaining)
        assertEquals(0, interval[1].usedPercent)

        val weekly = usage.groups.getValue("每周窗口")
        assertEquals(2, weekly.size)
        assertEquals(77, weekly[0].percentRemaining)
        // 周窗口的倒计时取 weekly_remains_time，不是 remains_time
        assertTrue(weekly[0].reset!!.endsWith("· 1天5时后"))
        assertEquals(100, weekly[1].percentRemaining)
    }

    @Test
    fun `token plan bucket names are never mistaken for available models`() {
        // model_name 现在是额度分桶名（general / video），不是模型 id，不能当「可用模型」展示
        assertTrue(MiniMax.parseRemains(TOKEN_PLAN_LIVE).models.isEmpty())
    }

    // ─── 展示口径：对齐网页控制台的取整 ───

    /**
     * 这份是**本机实测**：一把真实 Token Plan 订阅密钥，API 逐字返回
     * current_interval_remaining_percent=99 / weekly=95，App 起初照搬显示 99%，
     * 而 platform.minimaxi.com 的 TokenPlan 面板同一时刻显示 5 小时窗口满格。
     */
    private val LIVE_99_95 =
        """{"model_remains":[{"start_time":1791361162852,"end_time":1791379162852,"remains_time":10979072,"current_interval_total_count":0,"current_interval_usage_count":0,"model_name":"general","current_weekly_total_count":0,"current_weekly_usage_count":0,"weekly_start_time":1791271976775,"weekly_end_time":1791876776775,"weekly_remains_time":508592995,"current_interval_status":1,"current_interval_remaining_percent":99,"current_weekly_status":1,"current_weekly_remaining_percent":95}],"base_resp":{"status_code":0,"status_msg":"success"}}"""

    @Test
    fun `near-full interval window rounds up to match the console`() {
        val usage = MiniMax.parseRemains(LIVE_99_95)
        val interval = usage.groups.getValue("5 小时窗口").single()
        // 服务端给 99（截断值），控制台显示 100% / 已用 0%
        assertEquals(100, interval.percentRemaining)
        assertEquals(0, interval.usedPercent)
    }

    @Test
    fun `visible usage is never rounded away`() {
        val usage = MiniMax.parseRemains(LIVE_99_95)
        // 周窗口消耗明显，必须原样保留 95% / 已用 5%，不能被取整规则带偏
        val weekly = usage.groups.getValue("每周窗口").single()
        assertEquals(95, weekly.percentRemaining)
        assertEquals(5, weekly.usedPercent)
    }

    @Test
    fun `display rounding leaves every other bucket untouched`() {
        fun pcts(interval: Int, weekly: Int): Pair<Int, Int> {
            val json = """{"model_remains":[{"model_name":"general",""" +
                """"current_interval_remaining_percent":$interval,""" +
                """"current_weekly_remaining_percent":$weekly}],""" +
                """"base_resp":{"status_code":0}}"""
            val u = MiniMax.parseRemains(json)
            return u.groups.getValue("5 小时窗口").single().percentRemaining to
                u.groups.getValue("每周窗口").single().percentRemaining
        }
        // 边界：只有「已用 ≤1%」才进位，98 及以下保持原值
        assertEquals(100 to 100, pcts(99, 100))
        assertEquals(98 to 95, pcts(98, 95))
        assertEquals(97 to 77, pcts(97, 77))
        assertEquals(50 to 50, pcts(50, 50))
        // 满格与耗尽不能被规则改写
        assertEquals(100 to 0, pcts(100, 0))
        assertEquals(0 to 0, pcts(0, 0))
    }

    // ─── 旧形态兜底 ───

    @Test
    fun `legacy count shape still converts and reports remaining counts`() {
        val usage = MiniMax.parseRemains(LEGACY_COUNTS)

        val interval = usage.groups.getValue("5 小时窗口").single()
        assertEquals("MiniMax-M2", interval.label) // 未知分桶名原样透出
        assertEquals(97, interval.percentRemaining) // 1459 / 1500
        assertEquals("剩 1459/1500 次", interval.counts)
        assertTrue(interval.reset!!.endsWith("· 3时15分后"))

        val weekly = usage.groups.getValue("每周窗口").single()
        assertEquals(80, weekly.percentRemaining) // 8000 / 10000
        assertEquals("剩 8000/10000 次", weekly.counts)
        // 旧形态没有 weekly_end_time / weekly_remains_time，就不编重置时间
        assertNull(weekly.reset)
    }

    @Test
    fun `percent wins over counts when both are present`() {
        val both =
            """{"model_remains":[{"model_name":"general","current_interval_total_count":1500,"current_interval_usage_count":1459,"current_interval_remaining_percent":40,"current_weekly_total_count":10000,"current_weekly_usage_count":8000,"current_weekly_remaining_percent":55}],"base_resp":{"status_code":0}}"""
        val usage = MiniMax.parseRemains(both)
        assertEquals(40, usage.groups.getValue("5 小时窗口").single().percentRemaining)
        assertEquals(55, usage.groups.getValue("每周窗口").single().percentRemaining)
        // 次数仍按 count 展示（这部分数据是真的）
        assertEquals("剩 1459/1500 次", usage.groups.getValue("5 小时窗口").single().counts)
    }

    // ─── 失败与边界 ───

    @Test
    fun `cross region key error surfaces the server message`() {
        val e = runCatching { MiniMax.parseRemains(ERR_WRONG_REGION) }.exceptionOrNull()
        assertTrue("应抛 QuotaException，实际 $e", e is QuotaException)
        assertEquals(2049, (e as QuotaException).code)
        assertEquals("invalid api key", e.message)
    }

    @Test
    fun `no usable window refuses to invent a full bar`() {
        val e = runCatching { MiniMax.parseRemains(ALL_ZERO_NO_PERCENT) }.exceptionOrNull()
        assertTrue("应抛 QuotaException，实际 $e", e is QuotaException)
        assertTrue(e!!.message!!.contains("没有返回任何额度窗口"))
    }

    @Test
    fun `missing model_remains asks for a subscription key`() {
        val e = runCatching { MiniMax.parseRemains(NO_REMAINS) }.exceptionOrNull()
        assertTrue("应抛 QuotaException，实际 $e", e is QuotaException)
        assertTrue(e!!.message!!.contains("sk-cp-"))
    }
}