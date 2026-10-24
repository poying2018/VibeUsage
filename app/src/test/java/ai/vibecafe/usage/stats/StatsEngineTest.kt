package ai.vibecafe.usage.stats

import ai.vibecafe.usage.data.Bucket
import ai.vibecafe.usage.data.Session
import ai.vibecafe.usage.data.UsageResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * StatsEngine 时间筛选边界回归测试。
 * 所有 bucket 时间用「北京时间日历」生成，与引擎内部逻辑同基准。
 */
class StatsEngineTest {

    private val bjTz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = bjTz }
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** 生成「北京时间 date 当天 00:00」的 bucketStart ISO 串。 */
    private fun dayStartIso(offsetDays: Int): String {
        val cal = Calendar.getInstance(bjTz)
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        val dayStartBj = dayFmt.parse(dayFmt.format(cal.time))!!
        return isoFmt.format(dayStartBj)
    }

    private fun bucket(offsetDays: Int, cost: Double = 1.0, hostname: String = "H") = Bucket(
        source = "tool", model = "model", project = "p", hostname = hostname,
        bucketStart = dayStartIso(offsetDays),
        inputTokens = 100, outputTokens = 10, cachedInputTokens = 0, reasoningOutputTokens = 0,
        totalTokens = 110, estimatedCost = cost
    )

    private fun session(offsetDays: Int, messages: Int = 1, source: String = "tool", hostname: String = "H") = Session(
        source = source, project = "p", hostname = hostname,
        firstMessageAt = dayStartIso(offsetDays),
        lastMessageAt = dayStartIso(offsetDays),
        durationSeconds = 60, activeSeconds = 10,
        messageCount = messages, userMessageCount = messages,
        userPromptHours = emptyList()
    )

    private fun resp(vararg buckets: Bucket, sessions: List<Session> = emptyList()) =
        UsageResponse(buckets = buckets.toList(), sessions = sessions, hasAnyData = buckets.isNotEmpty())

    // ---- 筛选窗口 ----

    @Test
    fun `all range returns every bucket`() {
        val data = resp(bucket(-100), bucket(-10), bucket(0))
        val stats = StatsEngine.computeStats(data, TimeRange.ALL)
        assertEquals(3 * 110L, stats.totalTokens)
    }

    @Test
    fun `days30 window covers today and previous 29 days only`() {
        val data = resp(bucket(0), bucket(-29), bucket(-30), bucket(-100))
        val stats = StatsEngine.computeStats(data, TimeRange.DAYS_30)
        // 今天 + 29 天前在窗口内；30 天前与更早被排除
        assertEquals(2 * 110L, stats.totalTokens)
    }

    @Test
    fun `days90 window covers today and previous 89 days`() {
        val data = resp(bucket(0), bucket(-89), bucket(-90))
        val stats = StatsEngine.computeStats(data, TimeRange.DAYS_90)
        assertEquals(2 * 110L, stats.totalTokens)
    }

    @Test
    fun `today window only includes beijing today`() {
        val data = resp(bucket(0), bucket(-1))
        val stats = StatsEngine.computeStats(data, TimeRange.TODAY)
        assertEquals(110L, stats.totalTokens)
    }

    @Test
    fun `custom range is inclusive on both ends and independent of other ranges`() {
        val from = dayFmt.format(Calendar.getInstance(bjTz).apply { add(Calendar.DAY_OF_YEAR, -10) }.time)
        val to = dayFmt.format(Calendar.getInstance(bjTz).apply { add(Calendar.DAY_OF_YEAR, -8) }.time)
        val data = resp(bucket(0), bucket(-8), bucket(-10), bucket(-11))
        val stats = StatsEngine.computeStats(data, TimeRange.CUSTOM, custom = CustomRange(from, to))
        assertEquals(2 * 110L, stats.totalTokens)
    }

    @Test
    fun `device filter applies across all sections`() {
        val data = resp(bucket(0, hostname = "A"), bucket(0, hostname = "B"))
        val onlyA = StatsEngine.computeToolDistribution(data, TimeRange.ALL, devices = setOf("A"))
        // 只剩 A 主机的 1 条 bucket（tool 名不变，仍为 tool）
        assertEquals(1, onlyA.size)
        assertEquals(110L, onlyA.first().tokens)
    }

    @Test
    fun `session count follows range filter`() {
        val data = resp(bucket(0), sessions = listOf(session(0), session(-60)))
        val stats = StatsEngine.computeStats(data, TimeRange.DAYS_30)
        assertEquals(1, stats.sessionCount)
    }

    // ---- 会话分摊 ----

    @Test
    fun `session tokens are split proportionally by message count`() {
        val data = resp(
            bucket(-1, cost = 10.0),
            sessions = listOf(session(-1, messages = 3), session(-1, messages = 1))
        )
        val sessions = StatsEngine.computeDisplaySessions(data, TimeRange.ALL)
        assertEquals(2, sessions.size)
        val sorted = sessions.sortedByDescending { it.messageCount }
        // 4 条消息分摊 10 USD 与 bucket 总 tokens：3/4 与 1/4
        assertTrue(sorted[0].cost > sorted[1].cost)
        assertEquals(110L * 3 / 4, sorted[0].tokens)
        assertEquals(110L * 1 / 4, sorted[1].tokens)
    }

    // ---- 趋势百分比 ----

    @Test
    fun `trend percent compares current window against previous window`() {
        // 今天 $4，昨天 $2 → +100%
        val data = resp(bucket(0, cost = 4.0), bucket(-1, cost = 2.0))
        val trend = StatsEngine.computeTrendPercent(data, TimeRange.TODAY)
        assertNotNull(trend)
        assertEquals(1.0f, trend!!, 0.001f)
    }

    @Test
    fun `trend percent is null when previous window empty`() {
        val data = resp(bucket(0, cost = 4.0))
        assertNull(StatsEngine.computeTrendPercent(data, TimeRange.TODAY))
    }

    @Test
    fun `trend percent respects the device filter`() {
        // 机器 A：今天 $2、昨天 $1 → +100%
        // 机器 B：今天 $100、昨天 $1 → +9900%
        // 不筛设备时是 (102-2)/2 = +5000%；筛成 A 必须是 +100% ——
        // 页面上的金额是按设备筛过的，趋势百分比却用全量算的话，
        // 用户看到的百分比和金额完全对不上。
        val data = resp(
            bucket(0, cost = 2.0, hostname = "A"),
            bucket(-1, cost = 1.0, hostname = "A"),
            bucket(0, cost = 100.0, hostname = "B"),
            bucket(-1, cost = 1.0, hostname = "B")
        )
        val all = StatsEngine.computeTrendPercent(data, TimeRange.TODAY)
        assertNotNull(all)
        assertEquals(50.0f, all!!, 0.001f)

        val onlyA = StatsEngine.computeTrendPercent(data, TimeRange.TODAY, null, setOf("A"))
        assertNotNull(onlyA)
        assertEquals(1.0f, onlyA!!, 0.001f)
    }

    @Test
    fun `trend percent is null when the selected device had no previous usage`() {
        // 机器 B 只有今天的消耗：上一周期（对它）为空 → 没有趋势可言
        val data = resp(
            bucket(0, cost = 2.0, hostname = "A"),
            bucket(-1, cost = 1.0, hostname = "A"),
            bucket(0, cost = 100.0, hostname = "B")
        )
        assertNull(StatsEngine.computeTrendPercent(data, TimeRange.TODAY, null, setOf("B")))
    }

    @Test
    fun `custom range trend is capped at the range end`() {
        // 自定义 3 天（-3..-1），当前周期 $3、上一周期（-6..-4）$1 → +200%。
        // to 之后（今天）的消耗**不能**算进当前周期：
        // 早先的实现只设了 curStart 不设 curEnd，今天的 bucket 会把趋势算成 +2900%。
        val from = dayFmt.format(Calendar.getInstance(bjTz).apply { add(Calendar.DAY_OF_YEAR, -3) }.time)
        val to = dayFmt.format(Calendar.getInstance(bjTz).apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
        val data = resp(
            bucket(-6, cost = 1.0),   // 上一周期
            bucket(-3, cost = 1.0),
            bucket(-2, cost = 1.0),
            bucket(-1, cost = 1.0),
            bucket(0, cost = 100.0)   // 今天：在范围之外，必须被排除
        )
        val trend = StatsEngine.computeTrendPercent(data, TimeRange.CUSTOM, CustomRange(from, to))
        assertNotNull(trend)
        assertEquals(2.0f, trend!!, 0.001f)
    }

    @Test
    fun `days7 and hours24 windows pick the right buckets`() {
        val data = resp(
            bucket(0, cost = 1.0),
            bucket(-6, cost = 2.0),
            bucket(-7, cost = 100.0)   // 7 天窗口外
        )
        val stats = StatsEngine.computeStats(data, TimeRange.DAYS_7)
        assertEquals(2 * 110L, stats.totalTokens)   // 今天 + 6 天前

        // 24 小时窗口：endHour 按小时取整，startHour = endHour - 24h
        val hourly = StatsEngine.computeStats(data, TimeRange.HOURS_24)
        assertTrue("24 小时窗口至少应包含今天的 bucket", hourly.totalTokens >= 110L)
    }

    @Test
    fun `device filter applies to session counts as well`() {
        // 设备筛选必须同时作用在会话上：否则会话数会把别的设备也算进来
        val data = resp(
            bucket(0, hostname = "A"),
            sessions = listOf(
                session(0, hostname = "A"),
                session(0, hostname = "B")
            )
        )
        val all = StatsEngine.computeStats(data, TimeRange.ALL)
        assertEquals(2, all.sessionCount)
        val onlyA = StatsEngine.computeStats(data, TimeRange.ALL, devices = setOf("A"))
        assertEquals(1, onlyA.sessionCount)
    }

    // ---- dedup ----

    @Test
    fun `dedup merges same key and sums values`() {
        val a = bucket(-1, cost = 2.0)
        val b = bucket(-1, cost = 3.0)
        val merged = StatsEngine.dedup(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(5.0, merged.first().estimatedCost, 0.0001)
        assertEquals(200, merged.first().inputTokens)
    }

    // ---- 月度预测 ----

    @Test
    fun `month projection scales by day ratio`() {
        val cal = Calendar.getInstance(bjTz)
        val daysElapsed = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val data = resp(bucket(-daysElapsed + 1, cost = 100.0)) // 全部落在本月
        val mp = StatsEngine.computeMonthProjection(data)
        assertNotNull(mp)
        assertEquals(100.0, mp!!.monthCost, 0.0001)
        assertEquals(100.0 / daysElapsed * daysInMonth, mp.projected, 0.0001)
    }
}
