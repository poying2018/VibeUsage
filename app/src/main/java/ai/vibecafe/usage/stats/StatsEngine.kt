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

    /** 北京 = UTC+8 固定偏移（1991 年后无夏令时），供天/小时序号纯算术换算用。 */
    private const val BEIJING_OFFSET_MS = 8L * 3600_000L
    // 这些格式化器会被 Dispatchers.Default 的多个线程同时用到；SimpleDateFormat 不是线程安全的，
    // 共享实例会算出错误结果（甚至抛异常），所以按线程各持一份。
    // 对外名字保持不变，所有调用点无需改动。
    private val isoFormatTl = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    private val dateFormatTl = ThreadLocal.withInitial { beijingFormat("yyyy-MM-dd") }
    private val hourFormatTl = ThreadLocal.withInitial { beijingFormat("HH:00") }

    private fun beijingFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = beijingTz
    }

    private val isoFormat: SimpleDateFormat get() = isoFormatTl.get()!!
    private val dateFormat: SimpleDateFormat get() = dateFormatTl.get()!!
    private val hourFormat: SimpleDateFormat get() = hourFormatTl.get()!!

    private fun parseIsoTime(iso: String): Date? = runCatching { isoFormat.parse(iso) }.getOrNull()
    private fun toBeijingHour(date: Date): String = hourFormat.format(date)

    /**
     * ISO 时间解析缓存：ISO 串 → Date 是纯函数（格式固定、按 UTC 解析），
     * 缓存**不会过期**，所以可以跨函数、跨重算一直复用。
     *
     * 原来每个 compute 函数开头/结尾都 clearIsoCache()，等于把缓存废掉了：
     * 一次重算里 9 个函数各扫一遍同一批 bucket，同一个时间戳要解析 9 遍。
     * SimpleDateFormat.parse 是这里最贵的一步（全量历史可达十万级 bucket），
     * 复用后每个时间戳整个 App 会话只解析一次。
     *
     * 长期驻留的代价用条目数上限兜住：超出就整体清空重来。
     */
    private val isoCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val noDate = Any()

    /** 缓存条目上限。60k 条约 4~6MB，够一个会话内复用，也够低端机承受。 */
    private const val ISO_CACHE_MAX = 60_000

    private fun parseCached(iso: String): Date? {
        val cached = isoCache[iso] ?: run {
            if (isoCache.size >= ISO_CACHE_MAX) isoCache.clear()
            val stored: Any = parseIsoTime(iso) ?: noDate
            isoCache[iso] = stored
            stored
        }
        return cached as? Date
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
                    // 也要跟着累加：不重算的话，合并后的 totalTokens 仍是「组内第一条」的值，
                    // 与同一条的 fullTokens() 对不上 —— 留着这个字段就该让它自洽
                    totalTokens = group.sumOf { it.totalTokens },
                    estimatedCost = group.sumOf { it.estimatedCost }
                )
            }
    }

    /** 提取时间范围边界 Date，减少 filter 方法间的重复 Calendar 创建。 */
    private data class TimeBoundaries(
        val todayDayIndex: Long,
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
        // 北京日零点：纯算术（截断到天序号再乘回来），不再 format + parse 一对字符串往返
        val todayDate = dayIndexToDate(beijingDayIndex(now))
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
        return TimeBoundaries(
            beijingDayIndex(todayDate),
            endHour, startHour, daysAgo(6), daysAgo(29), daysAgo(89), customFrom, customTo
        )
    }

    /**
     * 北京时间的「天序号」（epoch day）。
     *
     * 只做算术、不做字符串格式化：这条路径上最贵的操作就是
     * `SimpleDateFormat.format` —— 每条 bucket 一次、每个 compute 函数一遍，
     * 而「这两个时刻是不是同一天」根本不需要字符串。
     *
     * 北京是固定 +08:00（1991 年后无夏令时），加 8 小时偏移即可换算出北京日。
     */
    private fun beijingDayIndex(t: Date): Long = (t.time + BEIJING_OFFSET_MS) / 86_400_000L

    /** 北京时间的「小时序号」。同 [beijingDayIndex]，纯算术。 */
    private fun beijingHourIndex(t: Date): Long = (t.time + BEIJING_OFFSET_MS) / 3_600_000L

    /** 天序号 → 该北京日的 00:00 时刻（[beijingDayIndex] 的逆运算）。 */
    private fun dayIndexToDate(dayIndex: Long): Date =
        Date(dayIndex * 86_400_000L - BEIJING_OFFSET_MS)

    /** 天序号 → 「M/d」（北京时间的月/日），只对最终那几行格式化。 */
    private fun dayIndexLabel(dayIndex: Long): String {
        val d = java.time.LocalDate.ofEpochDay(dayIndex)
        return "${d.monthValue}/${d.dayOfMonth}"
    }

    /** 判断一个 Date 是否落在时间范围内。 */
    private fun dateInRange(t: Date, timeRange: TimeRange, b: TimeBoundaries): Boolean {
        return when (timeRange) {
            // 天序号比较：原实现每条 bucket 都要 format 一次日期字符串
            TimeRange.TODAY -> beijingDayIndex(t) == b.todayDayIndex
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

    /**
     * 按时间范围过滤会话；[devices] 非空时同时按主机过滤。
     *
     * 设备筛选必须同时作用在会话上：bucket 按 hostname 过滤了，会话却不过滤的话，
     * 会话数会把其它设备的也算进来，被分摊的会话还会以 0 tokens/0 成本混进列表。
     */
    private fun filterSessionsByTimeRange(
        sessions: List<Session>,
        timeRange: TimeRange,
        b: TimeBoundaries,
        devices: Set<String> = emptySet()
    ): List<Session> {
        val byDevice = if (devices.isEmpty()) sessions
        else sessions.filter { it.hostname in devices }
        if (timeRange == TimeRange.ALL) return byDevice
        return byDevice.filter { s ->
            val t = parseCached(s.lastMessageAt) ?: return@filter false
            dateInRange(t, timeRange, b)
        }
    }

    // ── 共享过滤结果 ────────────────────────────────────────────────
    //
    // 一次重算里 9 个 compute 函数各自扫一遍同一批 bucket：每次都重新
    // 按设备/时间过滤、重新查时间戳。过滤结果只跟
    //（数据集、范围、设备）有关，算一次大家共用即可。
    //
    // 这里用「小容量 LRU + 按引用比数据集」：UsageResponse 是 data class，
    // equals 会把整个 bucket 列表比一遍，代价太大，所以数据集只比引用，其余按值比。
    //
    // 缓存键里必须带上**时间纪元**（windowEpoch）：RangeScope 把
    // computeBoundaries() 的结果（今日零点、24 小时窗口）一起缓存了。
    // 不带纪元的话，App 长时间停在后台再回前台、或跨过北京零点时，
    // 命中的还是发起时算好的旧边界，「今日」会一直显示昨天的数、
    // 「24 小时」窗口也不会往前滚。纪元按小时推进，够粗（省重算）也够细（不错日）。

    /** 时间纪元：按北京小时推进。跨过任意整点即失效，保证窗口边界不会过期。 */
    private fun windowEpoch(): Long = beijingHourIndex(Date())

    /** 设备过滤后的 bucket（不按时间）。 */
    private class DeviceScope(
        val data: UsageResponse,
        val devices: Set<String>,
        val buckets: List<Bucket>
    ) {
        fun matches(d: UsageResponse, dev: Set<String>) = data === d && devices == dev
    }

    /** 设备 + 时间范围过滤后的 bucket / session。 */
    private class RangeScope(
        val data: UsageResponse,
        val timeRange: TimeRange,
        val devices: Set<String>,
        val custom: CustomRange?,
        val epoch: Long,
        val boundaries: TimeBoundaries,
        val buckets: List<Bucket>,
        val sessions: List<Session>
    ) {
        fun matches(
            d: UsageResponse,
            r: TimeRange,
            dev: Set<String>,
            c: CustomRange?,
            e: Long
        ) = data === d && timeRange == r && devices == dev && custom == c && epoch == e
    }

    /**
     * 容量很小的小 LRU。
     *
     * 驻留上限刻意贴着「UiState 本来就强引用的数据集数量」来定：
     * dailyData / hourlyData / customData 三个对象在 ViewModel 里无论如何都是活的，
     * 所以这里容量 3 的缓存**不额外多留一份历史数据**，只是把已经留着的算好的过滤结果复用掉。
     */
    private class ScopeMemo<T : Any>(private val capacity: Int) {
        private val items = ArrayList<T>(capacity)

        @Synchronized
        fun find(predicate: (T) -> Boolean): T? = items.firstOrNull(predicate)

        @Synchronized
        fun put(item: T) {
            items.add(item)
            while (items.size > capacity) items.removeAt(0)
        }
    }

    private val deviceScopes = ScopeMemo<DeviceScope>(3)
    private val rangeScopes = ScopeMemo<RangeScope>(3)

    /** 设备过滤后的 bucket 列表（跨调用复用）。 */
    private fun deviceScope(data: UsageResponse, devices: Set<String>): List<Bucket> {
        deviceScopes.find { it.matches(data, devices) }?.let { return it.buckets }
        val buckets = if (devices.isEmpty()) data.buckets
        else data.buckets.filter { it.hostname in devices }
        deviceScopes.put(DeviceScope(data, devices, buckets))
        return buckets
    }

    /** 设备 + 时间范围过滤后的 bucket / session（跨调用复用）。 */
    private fun rangeScope(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String>,
        custom: CustomRange?
    ): RangeScope {
        val epoch = windowEpoch()
        rangeScopes.find { it.matches(data, timeRange, devices, custom, epoch) }?.let { return it }
        val b = computeBoundaries(custom)
        val scope = RangeScope(
            data = data,
            timeRange = timeRange,
            devices = devices,
            custom = custom,
            epoch = epoch,
            boundaries = b,
            buckets = filterByTimeRange(deviceScope(data, devices), timeRange, b),
            sessions = filterSessionsByTimeRange(data.sessions, timeRange, b, devices)
        )
        rangeScopes.put(scope)
        return scope
    }

    /** 设备 + 时间范围过滤后的 bucket（跨调用复用同一份过滤结果）。 */
    fun filterBuckets(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<Bucket> = rangeScope(data, timeRange, devices, custom).buckets

    /** 设备 + 时间范围过滤后的会话（与其它 compute 共用同一份 RangeScope）。 */
    private fun filterSessions(
        scope: RangeScope
    ): List<Session> = scope.sessions

    fun computeStats(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): DashboardStats {
        Log.d(TAG, "computeStats range=$timeRange devices=$devices")
        val scope = rangeScope(data, timeRange, devices, custom)
        val buckets = scope.buckets
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
            sessionCount = filterSessions(scope).size
        )
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
        val scope = rangeScope(data, timeRange, devices, custom)
        val buckets = scope.buckets.filter { it.source == tool }
        if (buckets.isEmpty()) return null
        val total = scope.buckets.sumOf { it.fullTokens() }.toFloat()
        val sessions = filterSessions(scope).filter { it.source == tool }
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
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): ModelDetail? {
        val scope = rangeScope(data, timeRange, devices, custom)
        val buckets = scope.buckets.filter { it.model == model }
        if (buckets.isEmpty()) return null
        val total = scope.buckets.sumOf { it.fullTokens() }.toFloat()
        val sources = buckets.map { it.source }.toSet()
        val sessions = filterSessions(scope).filter { it.source in sources }
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

    fun computeDailyUsage(
        data: UsageResponse,
        timeRange: TimeRange,
        devices: Set<String> = emptySet(),
        custom: CustomRange? = null
    ): List<DailyUsage> {
        val buckets = filterBuckets(data, timeRange, devices, custom)
        // 按「北京天序号」分组，而不是按格式化后的日期字符串：
        // 分组键用序号（Long，零分配），只有最终那几行才格式化成 "M/d"。
        // 原来每条 bucket 都要 format 一次，几万条数据就是几万次格式化。
        val byDay = LinkedHashMap<Long, MutableList<Bucket>>()
        for (bk in buckets) {
            val t = parseCached(bk.bucketStart) ?: continue
            byDay.getOrPut(beijingDayIndex(t)) { ArrayList() }.add(bk)
        }
        return byDay.entries
            // 按天序号排序，不按 "M/d" 字符串排：
            // 字符串排序会把 "10/18" 排到 "9/5" 前面（'1' < '9'），趋势图 X 轴就乱了
            .sortedBy { it.key }
            .map { (day, list) ->
                DailyUsage(
                    date = dayIndexLabel(day),
                    tokens = list.sumOf { it.fullTokens() },
                    cost = list.sumOf { it.estimatedCost }
                )
            }
    }

    /**
     * 24 小时趋势：按「HH:00」小时标签聚合。
     *
     * 注意这里**故意**按标签而不是按小时序号分组：数据源是 days=1 的滚动窗口，
     * 可能横跨两个北京日，同一钟点（如昨天 08:00 与今天 08:00）要合成一个点，
     * 否则图上会出现两个同名刻度。
     */
    fun computeHourlyUsage(
        data: UsageResponse,
        devices: Set<String> = emptySet()
    ): List<DailyUsage> {
        return deviceScope(data, devices)
            .groupBy { bucket ->
                val t = parseCached(bucket.bucketStart) ?: return@groupBy ""
                toBeijingHour(t)
            }
            // 同上：解析失败的 bucket 归到 ""，那是个空小时标签，
            // 留在图上会多出一个没有意义的点
            .filterKeys { it.isNotEmpty() }
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
        val base = deviceScope(data, devices)
        // 逐条比较天序号，不再每条 bucket format 一次日期字符串
        val todayIndex = beijingDayIndex(Date())
        val sums = HashMap<String, DailyUsage>()
        for (b in base) {
            val t = parseCached(b.bucketStart) ?: continue
            if (beijingDayIndex(t) != todayIndex) continue
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
        val scope = rangeScope(data, timeRange, devices, custom)
        val buckets = scope.buckets
        // 工具+主机维度的总消耗（bucket 只有工具粒度，无会话粒度）
        data class Agg(var cost: Double, var tokens: Long, var messages: Int)
        val agg = HashMap<SessionKey, Agg>()
        for (bk in buckets) {
            val key = SessionKey(bk.source, bk.hostname)
            val a = agg.getOrPut(key) { Agg(0.0, 0L, 0) }
            a.cost += bk.estimatedCost
            a.tokens += bk.fullTokens()
        }
        val sessions = filterSessions(scope)
        // 分摊基数：该工具+主机在范围内所有会话的消息总数
        for (s in sessions) {
            val key = SessionKey(s.source, s.hostname)
            agg.getOrPut(key) { Agg(0.0, 0L, 0) }.messages += s.messageCount
        }
        // 每组（工具+主机）有多少条会话：消息数全为 0 时要靠它做平摊
        val sessionCount = HashMap<SessionKey, Int>()
        for (s in sessions) {
            val key = SessionKey(s.source, s.hostname)
            sessionCount[key] = (sessionCount[key] ?: 0) + 1
        }
        return sessions
            .sortedByDescending { it.lastMessageAt }
            .map { s ->
                val key = SessionKey(s.source, s.hostname)
                val a = agg[key] ?: Agg(0.0, 0L, 0)
                // 按消息数占比分摊；这组会话的消息数全是 0（服务端没给消息数字段）时平摊，
                // 不能直接归零 —— 归零会让这一组会话显示 0 tokens / $0.00，
                // 和总览里的金额对不上。
                val share = when {
                    a.messages > 0 -> s.messageCount.toDouble() / a.messages.toDouble()
                    else -> {
                        val n = sessionCount[key] ?: 1
                        1.0 / n.toDouble()
                    }
                }
                DisplaySession(
                    source = s.source,
                    project = s.project,
                    hostname = s.hostname,
                    lastMessageAt = s.lastMessageAt,
                    durationSeconds = s.durationSeconds,
                    messageCount = s.messageCount,
                    // 用 Double 算：Long * Float 会先把 Long 转成 Float，
                    // 超过 2^24（约 1677 万）后精度就丢了 ——
                    // 本账号 token 量级是十亿级，会话明细会出现几百万的误差，
                    // 而且各会话加起来对不上总览。
                    tokens = (a.tokens.toDouble() * share).toLong(),
                    cost = a.cost * share
                )
            }
    }

    /**
     * 趋势百分比：当前范围 vs 等长的上一周期（按消耗金额）。
     * ALL 档取近 30 天 vs 再前 30 天；数据不足（上一周期无消耗）返回 null。
     */
    fun computeTrendPercent(
        data: UsageResponse,
        timeRange: TimeRange,
        custom: CustomRange? = null,
        devices: Set<String> = emptySet()
    ): Float? {
        val now = Date()
        val today = dayIndexToDate(beijingDayIndex(now))
        fun cal(base: Date, days: Int): Date = Calendar.getInstance(beijingTz).apply {
            time = base
            add(Calendar.DAY_OF_YEAR, days)
        }.time
        fun hoursAgo(h: Long): Date = Date(now.time - h * 3600_000L)

        // 当前周期窗口 [curStart, curEnd)；上一周期窗口 [prevStart, prevEnd)。
        // curEnd 为 null 表示"到数据末尾"（非自定义档的数据里不会有未来时间点）。
        val windows: Triple<Date, Date?, Date> = when (timeRange) {
            TimeRange.TODAY -> Triple(cal(today, 0), null, cal(today, -1))
            TimeRange.HOURS_24 -> Triple(hoursAgo(24), null, hoursAgo(48))
            TimeRange.DAYS_7 -> Triple(cal(today, -6), null, cal(today, -13))
            TimeRange.DAYS_30 -> Triple(cal(today, -29), null, cal(today, -59))
            TimeRange.DAYS_90 -> Triple(cal(today, -89), null, cal(today, -179))
            TimeRange.ALL -> Triple(cal(today, -29), null, cal(today, -59))
            TimeRange.CUSTOM -> {
                val from = custom?.let { dateFormat.parse(it.from) } ?: return null
                val to = custom?.let { dateFormat.parse(it.to) } ?: return null
                val len = ((to.time - from.time) / 86_400_000L).toInt() + 1
                // 当前周期必须**封顶**在 to（含当天）：不封顶的话，
                // 选一个过去的区间时，to 之后的消耗会被算进"当前周期"，趋势百分比虚高甚至翻倍。
                val curEnd = Calendar.getInstance(beijingTz).apply {
                    time = to
                    add(Calendar.DAY_OF_YEAR, 1)
                }.time
                Triple(from, curEnd, cal(from, -len))
            }
        }
        val (curStart, curEnd, prevStart) = windows

        var cur = 0.0
        var prev = 0.0
        // 设备筛选必须在这里也生效：趋势芯片显示的是「涨了百分之多少」，
        // 要是用全量设备算、而页面上的金额是筛选后的，用户看到的百分比和金额对不上。
        val base = if (devices.isEmpty()) data.buckets else data.buckets.filter { it.hostname in devices }
        for (bk in base) {
            val t = parseCached(bk.bucketStart) ?: continue
            val inCur = !t.before(curStart) && (curEnd == null || t.before(curEnd))
            val inPrev = !t.before(prevStart) && t.before(curStart)
            if (inCur) {
                cur += bk.estimatedCost
            } else if (inPrev) {
                prev += bk.estimatedCost
            }
        }
        if (prev <= 0.0 && cur <= 0.0) return null
        if (prev <= 0.0) return null
        return ((cur - prev) / prev).toFloat()
    }

    /** 月度预测：本月（北京时间）已消耗，按日均推算整月总额。 */
    fun computeMonthProjection(data: UsageResponse, devices: Set<String> = emptySet()): MonthProjection? {
        val base = deviceScope(data, devices)
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
        val monthStartDay = beijingDayIndex(monthStart)
        val monthCost = base.filter { bk ->
            // 1 号 00:00 的 bucket 也属于本月（天序号不早于月初）
            val t = parseCached(bk.bucketStart) ?: return@filter false
            beijingDayIndex(t) >= monthStartDay
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
