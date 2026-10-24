package ai.vibecafe.usage.data.quota

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 额度历史快照（近 7 天）。
 *
 * 额度面板只有「当前剩余量」这一个点，看不出这周的消耗速度。这里在每次成功查询额度后
 * 落一条**当天**快照（同一天重复记录覆盖当天那条），用来画近 7 天的迷你趋势。
 *
 * 落地方式：
 * - **每个平台一个 JSON 文件**：「刷新全部」会并发写多个平台，共用一个文件必然互相覆盖；
 * - **原子写**（临时文件 + rename）：读到的要么是旧文件要么是完整的新文件，不会读到半截 JSON；
 * - **只保留近 [KEEP_DAYS] 天**：文件不会无限增长。
 *
 * 日期口径与项目其它额度展示一致：固定按北京时间（Asia/Shanghai）分日。
 */
object QuotaHistoryStore {

    /** 保留天数（含今天）。 */
    const val KEEP_DAYS = 7

    private const val FILE_PREFIX = "quota_history_"
    private const val FILE_SUFFIX = ".json"

    /** 额度接口的时间口径是北京时间，快照按同口径分日。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val gson = Gson()

    /** 进程内写锁：「刷新全部」并发写时，同一平台的读-改-写要串行，否则后写的会覆盖前一条。 */
    private val lock = Any()

    /**
     * 一天的快照。
     *
     * @param date ISO 日期（yyyy-MM-dd，北京时间）
     * @param summary key = 分组名，value = percentRemaining
     * @param used key = 分组名，value = usedPercent（接口没给就不记）
     */
    data class Snapshot(
        val date: String,
        val summary: Map<String, Int>,
        val used: Map<String, Int> = emptyMap()
    )

    /** 落盘结构（字段名不能改：跨版本要读旧文件）。 */
    private data class FileDto(
        val providerId: String? = null,
        val snapshots: List<SnapshotDto?>? = null
    )

    private data class SnapshotDto(
        val date: String? = null,
        val summary: Map<String, Int?>? = null,
        val used: Map<String, Int?>? = null
    )

    // ─── 对外 API ───

    /** 该平台的快照文件路径。 */
    fun file(context: Context, providerId: String): File =
        File(context.filesDir, "$FILE_PREFIX$providerId$FILE_SUFFIX")

    /** 记录今天的快照（同一天重复记录覆盖当天那条）。写盘失败不影响额度展示。 */
    fun record(context: Context, providerId: String, usage: ExtraQuotaApi.Usage) {
        runCatching {
            val today = today()
            recordTo(file(context, providerId), usage, today, today)
        }
    }

    /** 近 [KEEP_DAYS] 天快照（按日期升序）。文件不存在或损坏时返回空表。 */
    fun history(context: Context, providerId: String): List<Snapshot> =
        runCatching { historyFrom(file(context, providerId), today()) }.getOrDefault(emptyList())

    /**
     * 删除该平台的历史快照（解绑凭据时调用）。
     *
     * 不删的话：解绑 → 换账号重新接入，同一个平台的趋势图会把**上一个账号**的
     * 历史和**新账号**的混在一起画，读起来是错的。
     */
    fun delete(context: Context, providerId: String) {
        runCatching { file(context, providerId).delete() }
    }

    /** 今天的日期（北京时间）。 */
    fun today(): String = LocalDate.now(zone).toString()

    // ─── 纯文件实现：不依赖 Context，便于单测 ───

    /**
     * 写入一条快照。
     *
     * @param target 目标 JSON 文件
     * @param usage 本次查到的额度
     * @param date 这条快照属于哪一天
     * @param today 「今天」（用于裁剪保留窗口）
     */
    fun recordTo(target: File, usage: ExtraQuotaApi.Usage, date: String, today: String) {
        synchronized(lock) {
            val merged = mergeAndTrim(readAll(target), snapshotOf(usage, date), cutoff(today))
            writeAll(target, merged)
        }
    }

    /** 读取近 [KEEP_DAYS] 天快照（按日期升序）。 */
    fun historyFrom(target: File, today: String): List<Snapshot> {
        val c = cutoff(today)
        return readAll(target).filter { it.date >= c }.sortedBy { it.date }
    }

    /**
     * 同日覆盖 + 只保留近 N 天（纯函数，便于单测）。
     *
     * 日期是 ISO 串，字典序即时间序，直接比较即可。
     */
    internal fun mergeAndTrim(
        existing: List<Snapshot>,
        incoming: Snapshot,
        cutoff: String
    ): List<Snapshot> {
        val byDate = LinkedHashMap<String, Snapshot>()
        existing.forEach { byDate[it.date] = it }
        // 同一天重复记录 → 覆盖当天那条（一天只留一个点，趋势图才是「每天一根柱」）
        byDate[incoming.date] = incoming
        return byDate.values.filter { it.date >= cutoff }.sortedBy { it.date }
    }

    /** 快照口径：每个分组取第一条 Bar 的剩余 / 已用百分比。 */
    internal fun snapshotOf(usage: ExtraQuotaApi.Usage, date: String): Snapshot {
        val summary = LinkedHashMap<String, Int>()
        val used = LinkedHashMap<String, Int>()
        usage.groups.forEach { (name, bars) ->
            val bar = bars.firstOrNull() ?: return@forEach
            summary[name] = bar.percentRemaining
            bar.usedPercent?.let { used[name] = it }
        }
        return Snapshot(date, summary, used)
    }

    /** 近 N 天的起始日期（含）。 */
    internal fun cutoff(today: String): String =
        LocalDate.parse(today).minusDays((KEEP_DAYS - 1).toLong()).toString()

    // ─── 内部读写 ───

    private fun readAll(target: File): List<Snapshot> {
        if (!target.exists()) return emptyList()
        return runCatching {
            val dto = gson.fromJson(target.readText(), FileDto::class.java)
            dto?.snapshots.orEmpty().mapNotNull { s ->
                val date = s?.date ?: return@mapNotNull null
                Snapshot(date, s.summary.clean(), s.used.clean())
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(target: File, snapshots: List<Snapshot>) {
        target.parentFile?.mkdirs()
        val dto = FileDto(
            providerId = target.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX),
            snapshots = snapshots.map { SnapshotDto(it.date, it.summary.toDto(), it.used.toDto()) }
        )
        // 原子写：先写临时文件再改名，避免读到写了一半的 JSON
        // （读侧解析失败会被吞成空表，趋势图就整段消失）
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(gson.toJson(dto))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun Map<String, Int>.toDto(): Map<String, Int?> = mapValues { it.value }

    /** 丢掉 null 值（Gson 允许 JSON 里出现 null，取值时不能直接拆箱）。 */
    private fun Map<String, Int?>?.clean(): Map<String, Int> {
        if (this == null) return emptyMap()
        val out = LinkedHashMap<String, Int>(size)
        for ((k, v) in this) if (v != null) out[k] = v
        return out
    }
}

/**
 * 「额度窗口」口径：优先取组名含「窗口」的分组（各家的 5 小时 / 每周窗口），
 * 否则取第一个分组。
 *
 * 总览页的额度摘要和 7 天趋势都用这一个口径，两处读数才不会对不上。
 */
internal fun preferredQuotaGroup(groups: Map<String, List<ExtraQuotaApi.Bar>>): String? {
    if (groups.isEmpty()) return null
    return groups.keys.firstOrNull { it.contains("窗口") } ?: groups.keys.first()
}
