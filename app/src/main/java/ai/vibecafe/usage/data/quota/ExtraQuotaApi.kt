package ai.vibecafe.usage.data.quota

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 「额度」页各家额度查询客户端。除标注外端点均来自开源工具逆向（多项目交叉验证）。
 *
 * MiniMax / 智谱 GLM / Kimi / DeepSeek / 无问芯穹 / 阿里百炼 / 火山方舟均为国内可达域名，
 * 始终直连、不经 Cloudflare Worker 中转；失败时在多域名/多站点之间自动回退。
 */
object ExtraQuotaApi {

    internal val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    internal val gson = Gson()

    /** 一条额度明细：剩余百分比 + 已用百分比 + 次数 + 重置时间（尽量详细）。 */
    data class Bar(
        val label: String,
        val percentRemaining: Int,
        val usedPercent: Int? = null,
        val counts: String? = null,
        val reset: String? = null,
    )

    /**
     * 按时间窗口分组的额度快照（组名有序）。models = 当前账号实际可用的模型列表
     * （来自账号维度的动态接口，随账号实时变化；拿不到则留空，UI 隐藏模型区）。
     * modelsLabel 覆盖模型区标题（默认「可用模型」），供按定价分档的供应商注明口径。
     */
    data class Usage(
        val account: String?,
        val groups: Map<String, List<Bar>>,
        val models: List<String> = emptyList(),
        val modelsLabel: String? = null
    )

    class QuotaException(val code: Int, message: String) : Exception(message)

    /**
     * 通用「OpenAI 风格」/models 拉取（data[].id）。任何失败都返回空表 ——
     * 模型区整体隐藏，绝不影响额度展示。
     */
    internal fun fetchModelIds(
        url: String,
        bearer: String,
        proxyFirst: Boolean,
        ua: String = "VibeUsage/2.16",
        directOnly: Boolean = false
    ): List<String> = try {
        val obj = gson.fromJson(
            QuotaHttp.get(url, bearer = bearer, proxyFirst = proxyFirst, ua = ua, directOnly = directOnly),
            JsonObject::class.java
        )
        (obj.optArray("data") ?: JsonArray())
            .mapNotNull { (it as? JsonObject)?.get("id")?.takeIf { v -> v.isJsonPrimitive }?.asString }
            .distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }

    // ─── Agnes AI（免费全模态 API 平台；token 用量在平台后端，账号登录换 JWT。
    //      路由=中转优先：其 Cloudflare WAF 封 CF Worker 出口（1010），中转必 403 → 自动回退直连，
    // ─── 小米 MiMo（控制台接口：账户余额 + Token Plan 套餐额度）───
    //
    // 2026-09-22 用真实登录态实测的结论：
    //   · 网关侧查不到额度——api.xiaomimimo.com/v1/balance 直接 404，API Key 在这里没用；
    //     额度只认 platform.xiaomimimo.com 的 Cookie，且最小必需项只有两条：
    //     userId + api-platform_serviceToken（slh / ph / xiaomichatbot_* 均可省，值带不带
    //     双引号均可，但缺 userId 必 401 —— 单独只给 serviceToken 是查不动的）。
    //   · 三端点均为 GET + {code,message,data} 信封，code=0 才算成功；未登录时 HTTP 401，
    //     body 里甩 account.xiaomim.com/pass/serviceLogin 跳转。国内可达，始终直连。
    //   · 未订阅 Token Plan 时 monthUsage.percent 恒为 0 且 items 为 null，那是「没有套餐」
    //     而不是「满格」，所以一条都不画，只在胶囊位写明未订阅。
    //   · 已订阅时 items[] 的真字段名尚未实测（验证账号无套餐）。下面按控制台前端 i18n
    //     （「当前套餐用量 {{used}} / {{limit}}」「补偿积分」）与第三方逆向资料里的
    // ─── 共用格式化 ───

    internal fun formatCountdown(ms: Long): String {
        if (ms <= 0) return "已重置"
        val days = ms / 86_400_000
        val hours = (ms % 86_400_000) / 3_600_000
        val mins = (ms % 3_600_000) / 60_000
        return when {
            days > 0 -> "${days}天${hours}时"
            hours > 0 -> "${hours}时${mins}分"
            else -> "${mins}分"
        }
    }

    /**
     * 毫秒时间戳 → 「MM-dd HH:mm 重置 · X后」。
     *
     * 固定按北京时间格式化：各家额度接口的时间口径都是 Asia/Shanghai
     * （Mimo 解析侧就显式用了 Asia/Shanghai），若这里跟随设备默认时区，
     * 非东八区设备显示的重置时刻会整体偏移，单测也会随机器时区而红。
     */
    internal fun epochReset(ms: Long): String =
        "${SimpleDateFormat("MM-dd HH:mm", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(ms))} 重置 · ${formatCountdown(ms - System.currentTimeMillis())}后"

    /** ISO8601 → 「MM-dd HH:mm · X后重置」；解析失败返回 null。 */
    internal fun formatReset(iso: String): String? {
        val remaining = ai.vibecafe.usage.data.ag.AgQuotaApi.remainingMillis(iso) ?: return null
        val abs = parseIsoMillis(iso)?.let { ms ->
            SimpleDateFormat("MM-dd HH:mm", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            }.format(Date(ms))
        }
        return when {
            remaining <= 0 -> "已重置"
            abs != null -> "$abs 重置 · ${formatCountdown(remaining)}后"
            else -> "重置：${formatCountdown(remaining)}后"
        }
    }

    /**
     * 大数紧凑显示：1234567 → 123.5万。
     *
     * 固定用 Locale.US：数字格式随默认 Locale 变化（de/ru 会用逗号当小数点），
     * 会把同一数值渲染成不同文本。
     */
    internal fun compact(n: Double): String = when {
        n >= 1e8 -> String.format(Locale.US, "%.1f亿", n / 1e8)
        n >= 1e4 -> String.format(Locale.US, "%.1f万", n / 1e4)
        n == n.toLong().toDouble() -> "${n.toLong()}"
        else -> String.format(Locale.US, "%.1f", n)
    }
}

/**
 * ISO8601 时间戳 → 绝对毫秒（UTC 纪元）；解析失败返回 null。
 *
 * 服务端可能下发三种形态：`…Z`（UTC）、`…+08:00`（带数字时区偏移）、不带时区标记。
 * 带数字偏移时必须按偏移换算成绝对时间——把尾部偏移直接截掉再当 UTC 解析，
 * 倒计时会整整差一个时区（东八区差 8 小时）。
 */
internal fun parseIsoMillis(iso: String): Long? {
    val raw = iso.trim()
    if (raw.isEmpty()) return null
    val s = raw.replace('t', 'T').replace('z', 'Z')
    // 带数字偏移（含 Z）：按偏移换算绝对时间；"+0800" 这种没冒号的先补上
    val candidates = listOf(s, s.replace(Regex("([+-]\\d{2})(\\d{2})$")) { m ->
        m.groupValues[1] + ":" + m.groupValues[2]
    })
    for (candidate in candidates) {
        runCatching {
            java.time.OffsetDateTime.parse(candidate, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
    }
    // 不带偏移：按 UTC 本地时间解析（各家接口未标注时区时口径都是 UTC）
    return runCatching {
        java.time.LocalDateTime.parse(
            s.removeSuffix("Z"),
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
}

/**
 * 秒 / 毫秒归一：> 1e11 视为毫秒，否则视为秒（与 Ark.kt、Bailian.kt 同款判定）。
 * 服务端秒级时间戳（约 1.7e9）直接当毫秒用会落到 1970 年，倒计时变成负数。
 */
internal fun normalizeEpochMs(v: Long): Long = if (v > 100_000_000_000L) v else v * 1000L

/** 防御式取值：成员存在但类型不符时返回 null，而不是抛 ClassCastException */
internal fun JsonObject.optObject(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.optArray(key: String): JsonArray? = get(key) as? JsonArray
internal fun jstr(o: JsonObject?, key: String): String? =
    ((o?.get(key)) as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString

/** 数值或数字字符串 → Double（Kimi 等接口数值可能以字符串下发）。 */
internal fun jnum(o: JsonObject?, key: String): Double? =
    (o?.get(key) as? com.google.gson.JsonPrimitive)?.asString?.trim()?.toDoubleOrNull()

/** 把服务端错误响应体转成适合展示的短文案：HTML/空 body 换成友好提示，
 *  JSON 错误尽量提取 message 字段（兼容嵌套 error.message / OAuth error_description 等风格）。 */
internal fun httpErrorBody(text: String, code: Int): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "HTTP $code"
    if (trimmed.startsWith("<")) return "HTTP $code（服务端返回异常页面）"
    if (trimmed.startsWith("{")) {
        try {
            val obj = com.google.gson.JsonParser.parseString(trimmed).asJsonObject
            val err = obj.get("error")
            val candidates = listOfNotNull(
                (err as? com.google.gson.JsonObject)?.get("message")?.takeIf { it.isJsonPrimitive }?.asString,
                obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString,
                obj.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString,
                (err as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString,
            )
            candidates.firstOrNull { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
            // 非 JSON 错误结构 → 原样截断
        }
    }
    return trimmed.take(300)
}

/**
 * 多 host 顺序兜底：按 [hosts] 顺序逐个尝试，第一个成功即返回其结果。
 *
 * 各家供应商都是「同一把 key 在不同站点/大区表现不一致，靠顺序重试兜住」，
 * 这个模式共用一份，避免每个 provider 各写一遍、再各错一遍。
 *
 * - [budgetMs] 是**软预算**：只在换 host 前检查，已经发出的请求不打断。
 *   没有它的话 N 台 host × (connect 15s + read 20s) 能让额度页转圈好几分钟。
 * - 鉴权类错误（[isAuthError] 判定的那几个码）换 host 也救不回来，但比
 *   「最后撞上的那个错」更有诊断价值（用户看到"key 不对"而不是"500"），整轮失败时优先抛它。
 * - [now] 注入是为了让单测能确定性地覆盖「预算耗尽提前退出」，不依赖真实时钟。
 */
internal fun <T> withHostFallback(
    hosts: List<String>,
    budgetMs: Long,
    isAuthError: (Int) -> Boolean = { it == 2049 || it == 1004 || it == 401 },
    emptyMessage: String = "查询失败",
    now: () -> Long = System::currentTimeMillis,
    fetch: (String) -> T
): T {
    val deadline = now() + budgetMs
    var lastErr: Exception? = null
    var authErr: ExtraQuotaApi.QuotaException? = null
    for (host in hosts) {
        if (now() > deadline) break
        try {
            return fetch(host)
        } catch (e: ExtraQuotaApi.QuotaException) {
            if (isAuthError(e.code)) authErr = e
            lastErr = e
        } catch (e: Exception) {
            lastErr = e
        }
    }
    throw authErr ?: lastErr ?: ExtraQuotaApi.QuotaException(0, emptyMessage)
}

/** HTTP 底座：直连 ⇄ 中转双路。 */
internal object QuotaHttp {

    private const val BUILTIN_PROXY = "https://proxy.20050912.xyz"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DnsResolver)
            .build()
    }

    /**
     * [proxyFirst] = true：先走 Worker 中转（大陆可达），失败回退直连（有 VPN 时）；
     * false 则直连优先、中转兜底；[directOnly] = true 时完全直连（国内服务，不经中转）。
     */
    fun get(
        url: String,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String> = emptyMap(),
        directOnly: Boolean = false
    ): String = execute(url, bearer, proxyFirst, ua, extraHeaders, directOnly) { u, b ->
        Request.Builder().url(u).get().apply { b?.let { header("Authorization", "Bearer $it") } }.build()
    }

    fun post(
        url: String,
        body: okhttp3.RequestBody,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String> = emptyMap(),
        directOnly: Boolean = false
    ): String = execute(url, bearer, proxyFirst, ua, extraHeaders, directOnly) { u, b ->
        Request.Builder().url(u).post(body).apply { b?.let { header("Authorization", "Bearer $it") } }.build()
    }

    private inline fun execute(
        target: String,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String>,
        directOnly: Boolean,
        build: (String, String?) -> Request
    ): String {
        var lastErr: Exception? = null
        val host = try { java.net.URI(target).host ?: "" } catch (_: Exception) { "" }
        // 中转熔断：某主机经中转连续失败（如 Agnes WAF 封 CF 出口，任何 UA 都 403 1010）
        // 达到阈值后，TTL 内不再尝试该主机的中转线路，全部直连，省掉每次刷新的空跳。
        val useRelay = !relayTripped(host)
        val urls = when {
            directOnly -> listOf(target)
            proxyFirst -> if (useRelay) listOf(proxied(target), target) else listOf(target)
            else -> if (useRelay) listOf(target, proxied(target)) else listOf(target)
        }
        for ((index, u) in urls.distinct().withIndex()) {
            val viaRelay = u.startsWith(BUILTIN_PROXY)
            try {
                val req = build(u, bearer).newBuilder()
                // 中转会覆盖 UA 并丢弃业务头：带 X-Proxy-UA / X-Quota-Pass-* 供扩展版 worker 透传
                req.header("User-Agent", ua)
                req.header("X-Proxy-UA", ua)
                if (viaRelay) {
                    extraHeaders.forEach { (k, v) -> req.header("X-Quota-Pass-$k", v) }
                } else {
                    extraHeaders.forEach { (k, v) -> req.header(k, v) }
                }
                client.newCall(req.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ExtraQuotaApi.QuotaException(resp.code, httpErrorBody(text, resp.code))
                    }
                    if (viaRelay) noteRelayResult(host, ok = true, relayFault = false)
                    return text
                }
            } catch (e: ExtraQuotaApi.QuotaException) {
                // HTTP 状态码错误来自目标站本身（403/500 等），不是中转线路的锅
                if (viaRelay) noteRelayResult(host, ok = false, relayFault = false)
                // 鉴权类错误（401）在直连/中转下语义一致，直接抛；
                // 403 可能只是某条线路被拦（如 Cloudflare 质询页），要给备用线路机会，试完所有线路再抛
                if (e.code == 401 || index == urls.distinct().lastIndex) throw e
                lastErr = e
            } catch (e: Exception) {
                // 连接异常/超时才是中转线路本身的问题，计入熔断计数
                if (viaRelay) noteRelayResult(host, ok = false, relayFault = e is java.io.IOException)
                lastErr = e
            }
        }
        throw lastErr ?: ExtraQuotaApi.QuotaException(0, "请求失败")
    }

    // ─── 中转熔断状态（进程内，不持久化：重启后最多再吃 3 次空跳）───

    private const val RELAY_TRIP_THRESHOLD = 3
    private const val RELAY_TRIP_TTL_MS = 10 * 60 * 1000L
    private val relayFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val relayBlockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun relayTripped(host: String): Boolean =
        (relayBlockedUntil[host] ?: 0L) > System.currentTimeMillis()

    /** 中转成功清零；失败累计，达到阈值拉闸 TTL。
     *
     *  只有**中转线路本身**的故障才计数：
     *  - 连接异常/超时（IOException 类）→ 计（中转挂了或链路不通）
     *  - HTTP 状态码错误（401/403/500…）→ 不计（那是目标站的应答，说明请求已送达目标站）
     *  401 一律不计（中转已把请求送达目标站，只是目标站不认这个凭据）。
     *
     *  熔断阈值与 TTL 的行为保持不变：连续 [RELAY_TRIP_THRESHOLD] 次中转故障后拉闸
     *  [RELAY_TRIP_TTL_MS]，TTL 内不再走该主机的中转线路。
     */
    private fun noteRelayResult(host: String, ok: Boolean, relayFault: Boolean) {
        if (host.isEmpty()) return
        if (ok) {
            relayFailures.remove(host)
            relayBlockedUntil.remove(host)
            return
        }
        if (!relayFault) return
        // 原子自增：读-改-写会丢计数（并发刷新时两次失败只算一次）
        val n: Int = relayFailures.merge(host, 1) { old, _ -> (old ?: 0) + 1 } ?: 0
        if (n >= RELAY_TRIP_THRESHOLD) {
            relayBlockedUntil[host] = System.currentTimeMillis() + RELAY_TRIP_TTL_MS
            relayFailures.remove(host)
        }
    }

    private fun proxied(target: String): String =
        "$BUILTIN_PROXY/proxy?url=" + java.net.URLEncoder.encode(target, "UTF-8")
}
