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

enum class TimeRange { TODAY, HOURS_24, DAYS_7, DAYS_30, ALL }

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
        val from30: Date
    )

    private fun computeBoundaries(): TimeBoundaries {
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
        val from7 = Calendar.getInstance(beijingTz).apply {
            time = todayDate
            add(Calendar.DAY_OF_YEAR, -6)
        }.time
        val from30 = Calendar.getInstance(beijingTz).apply {
            time = todayDate
            add(Calendar.DAY_OF_YEAR, -29)
        }.time
        return TimeBoundaries(todayDate, todayStr, endHour, startHour, from7, from30)
    }

    /** 判断一个 Date 是否落在时间范围内。 */
    private fun dateInRange(t: Date, timeRange: TimeRange, b: TimeBoundaries): Boolean {
        return when (timeRange) {
            TimeRange.TODAY -> toBeijingDateOnly(t) == b.todayStr
            TimeRange.HOURS_24 -> !t.before(b.startHour) && !t.after(b.endHour)
            TimeRange.DAYS_7 -> !t.before(b.from7)
            TimeRange.DAYS_30 -> !t.before(b.from30)
            TimeRange.ALL -> true
        }
    }

    private fun filterByTimeRange(buckets: List<Bucket>, timeRange: TimeRange): List<Bucket> {
        Log.d(TAG, "filter range=$timeRange input=${buckets.size}")
        if (timeRange == TimeRange.ALL) return buckets
        val b = computeBoundaries()
        val result = buckets.filter { bucket ->
            val t = parseCached(bucket.bucketStart) ?: return@filter false
            dateInRange(t, timeRange, b)
        }
        Log.d(TAG, "filter result=$timeRange output=${result.size}")
        return result
    }

    private fun filterSessionsByTimeRange(sessions: List<Session>, timeRange: TimeRange): List<Session> {
        if (timeRange == TimeRange.ALL) return sessions
        val b = computeBoundaries()
        return sessions.filter { s ->
            val t = parseCached(s.lastMessageAt) ?: return@filter false
            dateInRange(t, timeRange, b)
        }
    }

    fun filterBuckets(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet()
    ): List<Bucket> {
        clearIsoCache()
        val base = if (devices.isEmpty()) data.buckets
        else data.buckets.filter { it.hostname in devices }
        return filterByTimeRange(base, timeRange).also {
            clearIsoCache()
        }
    }

    fun computeStats(data: UsageResponse, timeRange: TimeRange, devices: Set<String> = emptySet()): DashboardStats {
        Log.d(TAG, "computeStats range=$timeRange devices=$devices")
        val buckets = filterBuckets(data, timeRange, devices)
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
            sessionCount = filterSessionsByTimeRange(data.sessions, timeRange).size
        )
    }

    fun computeToolDistribution(data: UsageResponse, timeRange: TimeRange): List<ToolDistribution> {
        val buckets = filterBuckets(data, timeRange)
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

    fun computeModelCosts(data: UsageResponse, timeRange: TimeRange): List<ModelCost> {
        val buckets = filterBuckets(data, timeRange)
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
        devices: Set<String> = emptySet()
    ): ToolDetail? {
        val scoped = filterBuckets(data, timeRange, devices)
        val buckets = scoped.filter { it.source == tool }
        if (buckets.isEmpty()) return null
        val total = scoped.sumOf { it.fullTokens() }.toFloat()
        val sessions = filterSessionsByTimeRange(data.sessions, timeRange).filter { it.source == tool }
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
        )
    }

    /** 长按模型详情：按模型名过滤出该模型在范围内的细分指标，并计算缓存命中率。 */
    fun computeModelDetail(
        data: UsageResponse,
        timeRange: TimeRange,
        model: String,
        devices: Set<String> = emptySet()
    ): ModelDetail? {
        val scoped = filterBuckets(data, timeRange, devices)
        val buckets = scoped.filter { it.model == model }
        if (buckets.isEmpty()) return null
        val total = scoped.sumOf { it.fullTokens() }.toFloat()
        val sources = buckets.map { it.source }.toSet()
        val sessions = filterSessionsByTimeRange(data.sessions, timeRange).filter { it.source in sources }
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
        )
    }

    fun computeDailyUsage(data: UsageResponse, timeRange: TimeRange): List<DailyUsage> {
        val buckets = filterBuckets(data, timeRange)
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

    fun computeHourlyUsage(data: UsageResponse): List<DailyUsage> {
        return data.buckets
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
    fun computeTodayHourlyUsage(data: UsageResponse): List<DailyUsage> {
        val today = toBeijingDateOnly(Date())
        val sums = HashMap<String, DailyUsage>()
        for (b in data.buckets) {
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

    fun computeDisplaySessions(data: UsageResponse, timeRange: TimeRange): List<DisplaySession> {
        val buckets = filterBuckets(data, timeRange)
        // 单次分组，避免重复遍历
        val aggBySession = buckets.groupBy { SessionKey(it.source, it.hostname) }
            .mapValues { (_, list) ->
                list.sumOf { it.estimatedCost } to list.sumOf { it.fullTokens() }
            }
        return filterSessionsByTimeRange(data.sessions, timeRange)
            .sortedByDescending { it.lastMessageAt }
            .map { s ->
                val key = SessionKey(s.source, s.hostname)
                val (cost, tokens) = aggBySession[key] ?: (0.0 to 0L)
                DisplaySession(
                    source = s.source,
                    project = s.project,
                    hostname = s.hostname,
                    lastMessageAt = s.lastMessageAt,
                    durationSeconds = s.durationSeconds,
                    messageCount = s.messageCount,
                    tokens = tokens,
                    cost = cost
                )
            }
    }

    fun availableDevices(data: UsageResponse): List<String> =
        data.buckets.map { it.hostname }.distinct().sorted()

    /** 避免字符串拼接的 session 分组 key。 */
    private data class SessionKey(val source: String, val hostname: String)
}