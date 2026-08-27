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
