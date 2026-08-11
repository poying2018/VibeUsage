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

    /** Dedup buckets that differ only by project variant. */
    fun dedupResponse(response: UsageResponse): UsageResponse =
        response.copy(buckets = dedup(response.buckets))
private const val TAG = "VibeUsage"
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

    private fun filterByTimeRange(buckets: List<Bucket>, timeRange: TimeRange): List<Bucket> {
        Log.d(TAG, "filter range=$timeRange input=${buckets.size}")
        if (timeRange == TimeRange.ALL) return buckets
        val result = when (timeRange) {
            TimeRange.TODAY -> {
                val todayStr = dateFormat.format(Date())
                buckets.filter { bucket ->
                    val t = parseIsoTime(bucket.bucketStart) ?: return@filter false
                    toBeijingDateOnly(t) == todayStr
                }
            }
            TimeRange.HOURS_24 -> {
                val nowCal = Calendar.getInstance(beijingTz).apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endHour = nowCal.time
                val startHour = Calendar.getInstance(beijingTz).apply {
                    timeInMillis = nowCal.timeInMillis - 24L * 60L * 60L * 1000L
                }.time
                buckets.filter { bucket ->
                    val t = parseIsoTime(bucket.bucketStart) ?: return@filter false
                    !t.before(startHour) && !t.after(endHour)
                }
            }
            TimeRange.DAYS_7 -> {
                val todayStr = dateFormat.format(Date())
                val cal = Calendar.getInstance(beijingTz).apply {
                    time = dateFormat.parse(todayStr) ?: Date()
                    add(Calendar.DAY_OF_YEAR, -6)
                }
                val fromStr = dateFormat.format(cal.time)
                buckets.filter { bucket ->
                    val t = parseIsoTime(bucket.bucketStart) ?: return@filter false
                    val d = toBeijingDateOnly(t)
                    d >= fromStr && d <= todayStr
                }
            }
            TimeRange.DAYS_30 -> {
                val todayStr = dateFormat.format(Date())
                val cal = Calendar.getInstance(beijingTz).apply {
                    time = dateFormat.parse(todayStr) ?: Date()
                    add(Calendar.DAY_OF_YEAR, -29)
                }
                val fromStr = dateFormat.format(cal.time)
                buckets.filter { bucket ->
                    val t = parseIsoTime(bucket.bucketStart) ?: return@filter false
                    val d = toBeijingDateOnly(t)
                    d >= fromStr && d <= todayStr
                }
            }
            else -> buckets
        }
        Log.d(TAG, "filter result=$timeRange output=${result.size}")
        return result
    }

    /**
     * 会话是否落在当前时间范围内（按 lastMessageAt 判断，时间语义与 filterByTimeRange 完全一致）。
     * 修复：此前 sessionCount 直接取 data.sessions.size（全量），切换时间范围时会话总数不变。
     */
    private fun filterSessionsByTimeRange(sessions: List<Session>, timeRange: TimeRange): List<Session> {
        if (timeRange == TimeRange.ALL) return sessions
        val now = Date()
        val todayStr = dateFormat.format(now)
        val endHour = Calendar.getInstance(beijingTz).apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val startHour = Calendar.getInstance(beijingTz).apply {
            timeInMillis = endHour.time - 24L * 60L * 60L * 1000L
        }.time
        val from7 = Calendar.getInstance(beijingTz).apply {
            time = dateFormat.parse(todayStr) ?: now
            add(Calendar.DAY_OF_YEAR, -6)
        }.time
        val from30 = Calendar.getInstance(beijingTz).apply {
            time = dateFormat.parse(todayStr) ?: now
            add(Calendar.DAY_OF_YEAR, -29)
        }.time
        return sessions.filter { s ->
            val t = parseIsoTime(s.lastMessageAt) ?: return@filter false
            when (timeRange) {
                TimeRange.TODAY -> toBeijingDateOnly(t) == todayStr
                TimeRange.HOURS_24 -> !t.before(startHour) && !t.after(endHour)
                TimeRange.DAYS_7 -> !t.before(from7)
                TimeRange.DAYS_30 -> !t.before(from30)
                else -> true
            }
        }
    }

    fun filterBuckets(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet()
    ): List<Bucket> {
        val base = if (devices.isEmpty()) data.buckets
        else data.buckets.filter { it.hostname in devices }
        return filterByTimeRange(base, timeRange)
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

    fun computeDailyUsage(data: UsageResponse, timeRange: TimeRange): List<DailyUsage> {
        val buckets = filterBuckets(data, timeRange)
        return buckets.groupBy { bucket ->
            val t = parseIsoTime(bucket.bucketStart) ?: return@groupBy ""
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
            .map { it.copy(date = labelFormat.format(dateFormat.parse(it.date))) }
    }

    fun computeHourlyUsage(data: UsageResponse): List<DailyUsage> {
        return data.buckets
            .groupBy { bucket ->
                val t = parseIsoTime(bucket.bucketStart) ?: return@groupBy ""
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

    fun computeDisplaySessions(data: UsageResponse, timeRange: TimeRange): List<DisplaySession> {
        val buckets = filterBuckets(data, timeRange)
        val costBySession = buckets.groupBy { it.source + "|" + it.hostname }
            .mapValues { (_, list) -> list.sumOf { it.estimatedCost } }
        val tokensBySession = buckets.groupBy { it.source + "|" + it.hostname }
            .mapValues { (_, list) -> list.sumOf { it.fullTokens() } }
        return filterSessionsByTimeRange(data.sessions, timeRange)
            .sortedByDescending { it.lastMessageAt }
            .map { s ->
                val key = s.source + "|" + s.hostname
                DisplaySession(
                    source = s.source,
                    project = s.project,
                    hostname = s.hostname,
                    lastMessageAt = s.lastMessageAt,
                    durationSeconds = s.durationSeconds,
                    messageCount = s.messageCount,
                    tokens = tokensBySession[key] ?: 0L,
                    cost = costBySession[key] ?: 0.0
                )
            }
    }

    fun availableDevices(data: UsageResponse): List<String> =
        data.buckets.map { it.hostname }.distinct().sorted()
}
