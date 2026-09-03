package ai.vibecafe.usage.data.quota

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MiniMax 额度查询客户端（端点来自开源工具 minimax-usage-checker 逆向）。
 *
 * MiniMax 为国内服务，始终直连、不经中转（国内域名优先，失败回退国际域名）。
 */
object ExtraQuotaApi {

    private val gson = Gson()

    /** 一条额度明细：剩余百分比 + 已用百分比 + 次数 + 重置时间（尽量详细）。 */
    data class Bar(
        val label: String,
        val percentRemaining: Int,
        val usedPercent: Int? = null,
        val counts: String? = null,
        val reset: String? = null,
    )

    /** 按时间窗口分组的额度快照（组名有序）。 */
    data class Usage(val account: String?, val groups: Map<String, List<Bar>>)

    class QuotaException(val code: Int, message: String) : Exception(message)

    // ─── MiniMax（Token Plan / Coding Plan 请求次数额度）───

    object MiniMax {
        private val URLS = listOf(
            "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains",
            "https://api.minimax.io/v1/api/openplatform/coding_plan/remains",
        )

        /**
         * 查询额度。响应 model_remains[] 中 current_interval_usage_count /
         * current_weekly_usage_count 实为「剩余次数」（官方字段命名 bug，见 MiniMax-M2#99）。
         * 国内服务始终直连不走中转：先试国内域名，单个域名失败（含业务错误）继续试下一个。
         */
        fun fetchUsage(apiKey: String): Usage {
            var lastErr: Exception? = null
            var businessErr: QuotaException? = null
            for (url in URLS) {
                try {
                    val resp = QuotaHttp.get(
                        url,
                        bearer = apiKey,
                        proxyFirst = false,
                        directOnly = true,
                        ua = "MiniMax-Usage-Checker"
                    )
                    val obj = gson.fromJson(resp, JsonObject::class.java) ?: continue
                    val base = obj.optObject("base_resp")
                    val code = base?.get("status_code")?.asInt
                    if (code != null && code != 0) {
                        throw QuotaException(
                            code,
                            base.get("status_msg")?.asString ?: "MiniMax 错误 $code"
                        )
                    }
                    val groups = linkedMapOf<String, MutableList<Bar>>()
                    val remains = obj.optArray("model_remains") ?: continue
                    for (el in remains) {
                        val m = el as? JsonObject ?: continue
                        val name = m.get("model_name")?.asString ?: continue
                        val total5h = m.get("current_interval_total_count")?.asInt
                        val remain5h = m.get("current_interval_usage_count")?.asInt
                        if (total5h != null && total5h > 0 && remain5h != null) {
                            val pct = (remain5h * 100.0 / total5h).toInt().coerceIn(0, 100)
                            val resetMs = m.get("remains_time")?.asLong
                            val endMs = m.get("end_time")?.asLong
                            groups.getOrPut("5 小时窗口") { mutableListOf() } += Bar(
                                label = name,
                                percentRemaining = pct,
                                usedPercent = (100 - pct).coerceIn(0, 100),
                                counts = "剩 $remain5h/$total5h 次",
                                reset = buildString {
                                    if (endMs != null && endMs > 0) {
                                        append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endMs)))
                                        append(" 重置")
                                        if (resetMs != null && resetMs > 0) append(" · ${formatCountdown(resetMs)}后")
                                    } else if (resetMs != null && resetMs > 0) {
                                        append("重置：${formatCountdown(resetMs)}后")
                                    }
                                }.ifEmpty { null }
                            )
                        }
                        val totalW = m.get("current_weekly_total_count")?.asInt
                        val remainW = m.get("current_weekly_usage_count")?.asInt
                        if (totalW != null && totalW > 0 && remainW != null) {
                            val pct = (remainW * 100.0 / totalW).toInt().coerceIn(0, 100)
                            groups.getOrPut("每周窗口") { mutableListOf() } += Bar(
                                label = name,
                                percentRemaining = pct,
                                usedPercent = (100 - pct).coerceIn(0, 100),
                                counts = "剩 $remainW/$totalW 次"
                            )
                        }
                    }
                    return Usage(null, groups)
                } catch (e: QuotaException) {
                    if (businessErr == null) businessErr = e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw businessErr ?: lastErr ?: QuotaException(0, "MiniMax 查询失败")
        }
    }

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

    /** ISO8601 → 「MM-dd HH:mm · X后重置」；解析失败返回 null。 */
    internal fun formatReset(iso: String): String? {
        val remaining = ai.vibecafe.usage.data.ag.AgQuotaApi.remainingMillis(iso) ?: return null
        val abs = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val cleaned = iso.let { s ->
                val dot = s.indexOf('.')
                (if (dot > 0) s.substring(0, dot) else s).trimEnd('Z')
            }
            val t = sdf.parse(cleaned)?.time ?: return null
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
        } catch (_: Exception) {
            null
        }
        return when {
            remaining <= 0 -> "已重置"
            abs != null -> "$abs 重置 · ${formatCountdown(remaining)}后"
            else -> "重置：${formatCountdown(remaining)}后"
        }
    }
}

/** 防御式取值：成员存在但类型不符时返回 null，而不是抛 ClassCastException */
internal fun JsonObject.optObject(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.optArray(key: String): com.google.gson.JsonArray? = get(key) as? com.google.gson.JsonArray

/** 把服务端错误响应体转成适合展示的短文案：HTML/空 body 换成友好提示，
 *  JSON 错误尽量提取 message 字段（兼容 OpenAI 嵌套 / OAuth error_description 等风格）。 */
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
        directOnly: Boolean = false
    ): String = execute(url, bearer, proxyFirst, ua, emptyMap(), directOnly) { u, b ->
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
        val urls = when {
            directOnly -> listOf(target)
            proxyFirst -> listOf(proxied(target), target)
            else -> listOf(target, proxied(target))
        }
        for ((index, u) in urls.distinct().withIndex()) {
            try {
                val req = build(u, bearer).newBuilder()
                // 中转会覆盖 UA 并丢弃业务头：带 X-Proxy-UA / X-Quota-Pass-* 供扩展版 worker 透传
                req.header("User-Agent", ua)
                req.header("X-Proxy-UA", ua)
                if (u.contains("/proxy?")) {
                    extraHeaders.forEach { (k, v) -> req.header("X-Quota-Pass-$k", v) }
                } else {
                    extraHeaders.forEach { (k, v) -> req.header(k, v) }
                }
                client.newCall(req.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ExtraQuotaApi.QuotaException(resp.code, httpErrorBody(text, resp.code))
                    }
                    return text
                }
            } catch (e: ExtraQuotaApi.QuotaException) {
                // 鉴权类错误（401）在直连/中转下语义一致，直接抛；
                // 403 可能只是某条线路被拦（如 Cloudflare 质询页），要给备用线路机会，试完所有线路再抛
                if (e.code == 401 || index == urls.distinct().lastIndex) throw e
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: ExtraQuotaApi.QuotaException(0, "请求失败")
    }

    private fun proxied(target: String): String =
        "$BUILTIN_PROXY/proxy?url=" + java.net.URLEncoder.encode(target, "UTF-8")
}
