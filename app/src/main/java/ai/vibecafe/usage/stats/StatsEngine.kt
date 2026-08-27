package ai.vibecafe.usage.stats

import android.util.Log
import ai.vibecafe.usage.data.Bucket
import ai.vibecafe.usage.data.Session
import ai.vibecafe.usage.data.UsageResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class TimeRange { TODAY, HOURS_24, DAYS_7, DAYS_30, DAYS_90, ALL, CUSTOM }

/** 自定义日期范围（yyyy-MM-dd，北京时间，含首尾两天）。 */
data class CustomRange(val from: String, val to: String)

/** 月度预测：本月已消耗 + 按日均推算的整月预测。 */
data class MonthProjection(val monthCost: Double, val projected: Double)

data class DashboardStats(
    val totalTokens: Long,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCachedTokens: Long,
    val totalReasoningTokens: Long,
    val totalCost: Double,
    val toolCount: Int,
    val modelCount: Int,
    val sessionCount: Int
)

data class ToolDistribution(
    val tool: String,
    val tokens: Long,
    val cost: Double,
    val percentage: Float
)

data class ModelCost(
    val model: String,
    val tokens: Long,
    val cost: Double
)

/** 长按应用详情：应用维度的细分指标。 */
data class ToolDetail(
    val tool: String,
    val cost: Double,
    val tokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val reasoningTokens: Long,
    val modelCount: Int,
    val sessionCount: Int,
    val percentage: Float
)

/** 长按模型详情：模型维度的细分指标，含缓存命中率。 */
data class ModelDetail(
    val model: String,
    val cost: Double,
    val tokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val reasoningTokens: Long,
    val toolCount: Int,
    val sessionCount: Int,
    val percentage: Float,
    /** 缓存命中率（0~100）：cached / (input + cached)。 */
    val cacheHitRate: Float
)

data class DailyUsage(
    val date: String,
    val tokens: Long,
    val cost: Double
)

data class DisplaySession(
    val source: String,
    val project: String,
    val hostname: String,
    val lastMessageAt: String,
    val durationSeconds: Long,
    val messageCount: Int,
    /** 按消息数分摊到该会话的估算值（API 无会话级用量，只能按占比拆分）。 */
    val tokens: Long,
    val cost: Double
)

object StatsEngine {

    private const val TAG = "VibeUsage"

    val beijingTz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = beijingTz
    }
    private val labelFormat = SimpleDateFormat("M/d", Locale.US).apply {
        timeZone = beijingTz
    }
    private val hourFormat = SimpleDateFormat("HH:00", Locale.US).apply {
        timeZone = beijingTz
    }

    private fun parseIsoTime(iso: String): Date? = runCatching { isoFormat.parse(iso) }.getOrNull()
    private fun toBeijingDateOnly(date: Date): String = dateFormat.format(date)
    private fun toBeijingHour(date: Date): String = hourFormat.format(date)

    /**
     * 缓存 ISO 时间解析结果，避免同一 bucket 在多个 compute 函数间重复解析。
     * 使用软引用语义——随着 filterBuckets 调用释放，不会内存泄漏。
     */
    private val isoCache = HashMap<String, Date?>()

    private fun parseCached(iso: String): Date? {
        return isoCache.getOrPut(iso) { parseIsoTime(iso) }
    }

    private fun clearIsoCache() {
        isoCache.clear()
    }

    /** Dedup buckets that differ only by project variant. */
    fun dedupResponse(response: UsageResponse): UsageResponse =
        response.copy(buckets = dedup(response.buckets))

    fun dedup(buckets: List<Bucket>): List<Bucket> {
        return buckets
            .groupBy { "${it.source}|${it.model}|${it.hostname}|${it.bucketStart}" }
            .map { (_, group) ->
                val first = group.first()
                first.copy(
                    inputTokens = group.sumOf { it.inputTokens },
                    outputTokens = group.sumOf { it.outputTokens },
                    cachedInputTokens = group.sumOf { it.cachedInputTokens },
                    reasoningOutputTokens = group.sumOf { it.reasoningOutputTokens },
                    estimatedCost = group.sumOf { it.estimatedCost }
                )
            }
    }

    /** 提取时间范围边界 Date，减少 filter 方法间的重复 Calendar 创建。 */
    private data class TimeBoundaries(
        val todayDate: Date,
        val todayStr: String,
        val endHour: Date,
        val startHour: Date,
        val from7: Date,
        val from30: Date,
        val from90: Date,
        val customFrom: Date?,
        val customTo: Date?
    )

    private fun computeBoundaries(custom: CustomRange? = null): TimeBoundaries {
        val now = Date()
        val todayStr = dateFormat.format(now)
        val todayDate = dateFormat.parse(todayStr) ?: now
        val endHour = Calendar.getInstance(beijingTz).apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val startHour = Calendar.getInstance(beijingTz).apply {
            timeInMillis = endHour.time - 24L * 60L * 60L * 1000L
        }.time
        fun daysAgo(days: Int): Date = Calendar.getInstance(beijingTz).apply {
            time = todayDate
            add(Calendar.DAY_OF_YEAR, -days)
        }.time
        val customFrom = custom?.let { dateFormat.parse(it.from) }
        val customTo = custom?.let {
            dateFormat.parse(it.to)?.let { end ->
                // 含 to 当天：取 23:59:59.999
                Calendar.getInstance(beijingTz).apply {
                    time = end
                    add(Calendar.DAY_OF_YEAR, 1)
                    add(Calendar.MILLISECOND, -1)
                }.time
            }
        }
        return TimeBoundaries(todayDate, todayStr, endHour, startHour, daysAgo(6), daysAgo(29), daysAgo(89), customFrom, customTo)
    }

    /** 判断一个 Date 是否落在时间范围内。 */
    private fun dateInRange(t: Date, timeRange: TimeRange, b: TimeBoundaries): Boolean {
        return when (timeRange) {
            TimeRange.TODAY -> toBeijingDateOnly(t) == b.todayStr
            TimeRange.HOURS_24 -> !t.before(b.startHour) && !t.after(b.endHour)
            TimeRange.DAYS_7 -> !t.before(b.from7)
            TimeRange.DAYS_30 -> !t.before(b.from30)
            TimeRange.DAYS_90 -> !t.before(b.from90)
            TimeRange.ALL -> true
            TimeRange.CUSTOM -> b.customFrom != null && b.customTo != null &&
                !t.before(b.customFrom) && !t.after(b.customTo)
        }
    }

    private fun filterByTimeRange(
        buckets: List<Bucket>,
        timeRange: TimeRange,
        b: TimeBoundaries
    ): List<Bucket> {
        if (timeRange == TimeRange.ALL) return buckets
        Log.d(TAG, "filter range=$timeRange input=${buckets.size}")
        val result = buckets.filter { bucket ->
            val t = parseCached(bucket.bucketStart) ?: return@filter false
            dateInRange(t, timeRange, b)
        }
        Log.d(TAG, "filter result=$timeRange output=${result.size}")
        return result
    }

    private fun filterSessionsByTimeRange(
        sessions: List<Session>,
        timeRange: TimeRange,
        b: TimeBoundaries
    ): List<Session> {
        if (timeRange == TimeRange.ALL) return sessions
        return sessions.filter { s ->
            val t = parseCached(s.lastMessageAt) ?: return@filter false
            dateInRange(t, timeRange, b)
        }
    }

    private fun scopedBuckets(
        data: UsageResponse,
        timeRange: TimeRange,
        b: TimeBoundaries,
        devices: Set<String>
    ): List<Bucket> {
        val base = if (devices.isEmpty()) data.buckets
        else data.buckets.filter { it.hostname in devices }
        return filterByTimeRange(base, timeRange, b)
    }

    fun filterBuckets(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<Bucket> {
        clearIsoCache()
        val b = computeBoundaries(custom)
        return scopedBuckets(data, timeRange, b, devices).also {
            clearIsoCache()
        }
    }

    fun computeStats(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): DashboardStats {
        Log.d(TAG, "computeStats range=$timeRange devices=$devices")
        clearIsoCache()
        val b = computeBoundaries(custom)
        val buckets = scopedBuckets(data, timeRange, b, devices)
        val input = buckets.sumOf { it.inputTokens }
        val output = buckets.sumOf { it.outputTokens }
        val cached = buckets.sumOf { it.cachedInputTokens }
        val reasoning = buckets.sumOf { it.reasoningOutputTokens }
        return DashboardStats(
            totalTokens = input + output + reasoning + cached,
            totalInputTokens = input,
            totalOutputTokens = output,
            totalCachedTokens = cached,
            totalReasoningTokens = reasoning,
            totalCost = buckets.sumOf { it.estimatedCost },
            toolCount = buckets.map { it.source }.distinct().size,
            modelCount = buckets.map { it.model }.distinct().size,
            sessionCount = filterSessionsByTimeRange(data.sessions, timeRange, b).size
        ).also { clearIsoCache() }
    }

    fun computeToolDistribution(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<ToolDistribution> {
        val buckets = filterBuckets(data, timeRange, devices, custom)
        val total = buckets.sumOf { it.fullTokens() }.toFloat()
        return buckets.groupBy { it.source }
            .map { (tool, list) ->
                val tokens = list.sumOf { it.fullTokens() }
                ToolDistribution(
                    tool = tool,
                    tokens = tokens,
                    cost = list.sumOf { it.estimatedCost },
                    percentage = if (total > 0f) tokens / total * 100f else 0f
                )
            }
            .sortedByDescending { it.tokens }
    }

    fun computeModelCosts(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<ModelCost> {
        val buckets = filterBuckets(data, timeRange, devices, custom)
        return buckets.groupBy { it.model }
            .map { (model, list) ->
                ModelCost(
                    model = model,
                    tokens = list.sumOf { it.fullTokens() },
                    cost = list.sumOf { it.estimatedCost }
                )
            }
            .sortedByDescending { it.cost }
    }

    /** 长按应用详情：按工具名过滤出该应用在范围内的细分指标（含输入/输出/缓存/推理、模型数、会话数）。 */
    fun computeToolDetail(
        data: UsageResponse,
        timeRange: TimeRange,
        tool: String,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): ToolDetail? {
        clearIsoCache()
        val b = computeBoundaries(custom)
        val scoped = scopedBuckets(data, timeRange, b, devices)
        val buckets = scoped.filter { it.source == tool }
        if (buckets.isEmpty()) return null
        val total = scoped.sumOf { it.fullTokens() }.toFloat()
        val sessions = filterSessionsByTimeRange(data.sessions, timeRange, b).filter { it.source == tool }
        return ToolDetail(
            tool = tool,
            cost = buckets.sumOf { it.estimatedCost },
            tokens = buckets.sumOf { it.fullTokens() },
            inputTokens = buckets.sumOf { it.inputTokens },
            outputTokens = buckets.sumOf { it.outputTokens },
            cachedTokens = buckets.sumOf { it.cachedInputTokens },
            reasoningTokens = buckets.sumOf { it.reasoningOutputTokens },
            modelCount = buckets.map { it.model }.distinct().size,
            sessionCount = sessions.size,
            percentage = if (total > 0f) buckets.sumOf { it.fullTokens() } / total * 100f else 0f
        ).also { clearIsoCache() }
    }

    /** 长按模型详情：按模型名过滤出该模型在范围内的细分指标，并计算缓存命中率。 */
    fun computeModelDetail(
        data: UsageResponse,
        timeRange: TimeRange,
        model: String,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): ModelDetail? {
        clearIsoCache()
        val b = computeBoundaries(custom)
        val scoped = scopedBuckets(data, timeRange, b, devices)
        val buckets = scoped.filter { it.model == model }
        if (buckets.isEmpty()) return null
        val total = scoped.sumOf { it.fullTokens() }.toFloat()
        val sources = buckets.map { it.source }.toSet()
        val sessions = filterSessionsByTimeRange(data.sessions, timeRange, b).filter { it.source in sources }
        val input = buckets.sumOf { it.inputTokens }
        val cached = buckets.sumOf { it.cachedInputTokens }
        return ModelDetail(
            model = model,
            cost = buckets.sumOf { it.estimatedCost },
            tokens = buckets.sumOf { it.fullTokens() },
            inputTokens = input,
            outputTokens = buckets.sumOf { it.outputTokens },
            cachedTokens = cached,
            reasoningTokens = buckets.sumOf { it.reasoningOutputTokens },
            toolCount = buckets.map { it.source }.distinct().size,
            sessionCount = sessions.size,
            percentage = if (total > 0f) buckets.sumOf { it.fullTokens() } / total * 100f else 0f,
            cacheHitRate = if (input + cached > 0L) cached.toFloat() / (input + cached) * 100f else 0f
        ).also { clearIsoCache() }
    }

    fun computeDailyUsage(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<DailyUsage> {
        val buckets = filterBuckets(data, timeRange, devices, custom)
        return buckets.groupBy { bucket ->
            val t = parseCached(bucket.bucketStart) ?: return@groupBy ""
            toBeijingDateOnly(t)
        }
            .map { (date, list) ->
                DailyUsage(
                    date = date,
                    tokens = list.sumOf { it.fullTokens() },
                    cost = list.sumOf { it.estimatedCost }
                )
            }
            .sortedBy { it.date }
            .map { entry ->
                val parsed = dateFormat.parse(entry.date) ?: return@map entry
                entry.copy(date = labelFormat.format(parsed))
            }
    }

    fun computeHourlyUsage(
        data: UsageResponse,
        devices: Set<String> = emptySet()
    ): List<DailyUsage> {
        val base = if (devices.isEmpty()) data.buckets else data.buckets.filter { it.hostname in devices }
        return base
            .groupBy { bucket ->
                val t = parseCached(bucket.bucketStart) ?: return@groupBy ""
                toBeijingHour(t)
            }
            .map { (hour, list) ->
                DailyUsage(
                    date = hour,
                    tokens = list.sumOf { it.fullTokens() },
                    cost = list.sumOf { it.estimatedCost }
                )
            }
            .sortedBy { it.date }
    }

    /** 「今日」趋势：仅统计今天（北京时间）的 bucket，按小时聚合，0 点至当前小时补零。 */
    fun computeTodayHourlyUsage(
        data: UsageResponse,
        devices: Set<String> = emptySet()
    ): List<DailyUsage> {
        val base = if (devices.isEmpty()) data.buckets else data.buckets.filter { it.hostname in devices }
        val today = toBeijingDateOnly(Date())
        val sums = HashMap<String, DailyUsage>()
        for (b in base) {
            val t = parseCached(b.bucketStart) ?: continue
            if (toBeijingDateOnly(t) != today) continue
            val key = toBeijingHour(t)
            val prev = sums[key] ?: DailyUsage(key, 0L, 0.0)
            sums[key] = prev.copy(
                tokens = prev.tokens + b.fullTokens(),
                cost = prev.cost + b.estimatedCost
            )
        }
        val nowHour = Calendar.getInstance(beijingTz).get(Calendar.HOUR_OF_DAY)
        return (0..nowHour).map { h ->
            val key = String.format(Locale.US, "%02d:00", h)
            sums[key] ?: DailyUsage(key, 0L, 0.0)
        }
    }

    fun computeDisplaySessions(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<DisplaySession> {
        clearIsoCache()
        val b = computeBoundaries(custom)
        val buckets = scopedBuckets(data, timeRange, b, devices)
        // 工具+主机维度的总消耗（bucket 只有工具粒度，无会话粒度）
        data class Agg(var cost: Double, var tokens: Long, var messages: Int)
        val agg = HashMap<SessionKey, Agg>()
        for (bk in buckets) {
            val key = SessionKey(bk.source, bk.hostname)
            val a = agg.getOrPut(key) { Agg(0.0, 0L, 0) }
            a.cost += bk.estimatedCost
            a.tokens += bk.fullTokens()
        }
        val sessions = filterSessionsByTimeRange(data.sessions, timeRange, b)
        // 分摊基数：该工具+主机在范围内所有会话的消息总数
        for (s in sessions) {
            val key = SessionKey(s.source, s.hostname)
            agg.getOrPut(key) { Agg(0.0, 0L, 0) }.messages += s.messageCount
        }
        return sessions
            .sortedByDescending { it.lastMessageAt }
            .map { s ->
                val key = SessionKey(s.source, s.hostname)
                val a = agg[key] ?: Agg(0.0, 0L, 0)
                // 按消息数占比分摊；无消息数信息时平摊
                val share = if (a.messages > 0) s.messageCount.toFloat() / a.messages else 0f
                DisplaySession(
                    source = s.source,
                    project = s.project,
                    hostname = s.hostname,
                    lastMessageAt = s.lastMessageAt,
                    durationSeconds = s.durationSeconds,
                    messageCount = s.messageCount,
                    tokens = (a.tokens * share).toLong(),
                    cost = a.cost * share
                )
            }
            .also { clearIsoCache() }
    }

    /**
     * 趋势百分比：当前范围 vs 等长的上一周期（按消耗金额）。
     * ALL 档取近 30 天 vs 再前 30 天；数据不足（上一周期无消耗）返回 null。
     */
    fun computeTrendPercent(
        data: UsageResponse,
        timeRange: TimeRange,
        custom: CustomRange? = null
    ): Float? {
        clearIsoCache()
        val now = Date()
        val today = dateFormat.parse(dateFormat.format(now)) ?: now
        fun cal(base: Date, days: Int): Date = Calendar.getInstance(beijingTz).apply {
            time = base
            add(Calendar.DAY_OF_YEAR, days)
        }.time
        fun hoursAgo(h: Long): Date = Date(now.time - h * 3600_000L)

        val (curStart, prevStart) = when (timeRange) {
            TimeRange.TODAY -> cal(today, 0) to cal(today, -1)
            TimeRange.HOURS_24 -> hoursAgo(24) to hoursAgo(48)
            TimeRange.DAYS_7 -> cal(today, -6) to cal(today, -13)
            TimeRange.DAYS_30 -> cal(today, -29) to cal(today, -59)
            TimeRange.DAYS_90 -> cal(today, -89) to cal(today, -179)
            TimeRange.ALL -> cal(today, -29) to cal(today, -59)
            TimeRange.CUSTOM -> {
                val from = custom?.let { dateFormat.parse(it.from) } ?: return null
                val to = custom?.let { dateFormat.parse(it.to) } ?: return null
                val len = ((to.time - from.time) / 86_400_000L).toInt() + 1
                from to cal(from, -len)
            }
        }

        var cur = 0.0
        var prev = 0.0
        for (bk in data.buckets) {
            val t = parseCached(bk.bucketStart) ?: continue
            if (!t.before(curStart)) {
                cur += bk.estimatedCost
            } else if (!t.before(prevStart)) {
                prev += bk.estimatedCost
            }
        }
        clearIsoCache()
        if (prev <= 0.0 && cur <= 0.0) return null
        if (prev <= 0.0) return null
        return ((cur - prev) / prev).toFloat()
    }

    /** 月度预测：本月（北京时间）已消耗，按日均推算整月总额。 */
    fun computeMonthProjection(data: UsageResponse, devices: Set<String> = emptySet()): MonthProjection? {
        val base = if (devices.isEmpty()) data.buckets else data.buckets.filter { it.hostname in devices }
        if (base.isEmpty()) return null
        val nowCal = Calendar.getInstance(beijingTz)
        val monthStartCal = (nowCal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStart = monthStartCal.time
        val monthCost = base.filter { bk ->
            // 1 号 00:00 的 bucket 也属于本月（不早于月初）
            (parseCached(bk.bucketStart)?.before(monthStart) == false)
        }.sumOf { it.estimatedCost }
        val daysElapsed = nowCal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = nowCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        if (monthCost <= 0.0 || daysElapsed <= 0) return null
        val dailyAvg = monthCost / daysElapsed
        return MonthProjection(monthCost = monthCost, projected = dailyAvg * daysInMonth)
    }

    fun availableDevices(data: UsageResponse): List<String> =
        data.buckets.map { it.hostname }.distinct().sorted()

    /** 避免字符串拼接的 session 分组 key。 */
    private data class SessionKey(val source: String, val hostname: String)
}
