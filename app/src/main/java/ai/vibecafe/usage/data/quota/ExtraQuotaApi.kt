package ai.vibecafe.usage.data.quota

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Codex / Claude Code / MiniMax 三家额度查询客户端（端点均来自开源工具逆向：
 * openai/codex 官方 CLI、CCSeva、onWatch / minimax-usage-checker）。
 *
 * chatgpt.com 与 api.anthropic.com 在大陆无法直连，默认先走内置 Cloudflare Worker
 * 中转（需 worker 白名单包含对应域名），失败自动回退直连；MiniMax 为国内可达域名，
 * 直连优先。
 */
object ExtraQuotaApi {

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

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

    // ─── Codex（ChatGPT 计划额度）───

    object Codex {
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

        data class Auth(val accessToken: String, val accountId: String?, val refreshToken: String?)

        /** 解析 ~/.codex/auth.json 粘贴内容；也接受裸 access_token（无账号 ID 时查询会 401）。 */
        fun parseAuth(raw: String): Auth? = try {
            val input = raw.trim()
            if (!input.contains("{")) {
                val token = raw.filter { it in '!'..'~' }
                token.takeIf { it.length >= 40 }?.let { Auth(it, null, null) }
            } else {
                val obj = gson.fromJson(input, JsonObject::class.java)
                val tokens = obj?.getAsJsonObject("tokens")
                val access = tokens?.get("access_token")?.asString
                    ?: obj?.get("access_token")?.asString
                access?.takeIf { it.isNotBlank() }?.let {
                    Auth(
                        accessToken = it,
                        accountId = tokens?.get("account_id")?.asString
                            ?: obj?.get("account_id")?.asString,
                        refreshToken = tokens?.get("refresh_token")?.asString
                            ?: obj?.get("refresh_token")?.asString
                    )
                }
            }
        } catch (_: Exception) {
            null
        }

        /** 用 refresh_token 换新 access_token（官方 Codex CLI 客户端）。 */
        fun refreshAccessToken(refreshToken: String): String {
            val body = gson.toJson(
                JsonObject().apply {
                    addProperty("client_id", CLIENT_ID)
                    addProperty("grant_type", "refresh_token")
                    addProperty("refresh_token", refreshToken)
                    addProperty("scope", "openid profile email")
                }
            ).toRequestBody(JSON_TYPE)
            val resp = QuotaHttp.post(TOKEN_URL, body, bearer = null, proxyFirst = true, ua = "codex_cli_rs")
            val obj = gson.fromJson(resp, JsonObject::class.java)
            val access = obj?.get("access_token")?.asString
            return access ?: throw QuotaException(
                401,
                obj?.get("error_description")?.asString ?: obj?.get("error")?.asString ?: "刷新令牌失败"
            )
        }

        /** 查询额度：主窗口（5h 滚动）+ 次窗口（每周）+ 附加限额。 */
        fun fetchUsage(accessToken: String, accountId: String?): Usage {
            val resp = QuotaHttp.get(
                USAGE_URL,
                bearer = accessToken,
                proxyFirst = true,
                ua = "codex_cli_rs",
                extraHeaders = accountId?.let { mapOf("ChatGPT-Account-Id" to it) }.orEmpty()
            )
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            val groups = linkedMapOf<String, MutableList<Bar>>()

            val rateLimit = obj.getAsJsonObject("rate_limit")
            parseWindow(rateLimit?.getAsJsonObject("primary_window"), short = true)?.let {
                groups.getOrPut("5 小时滚动窗口") { mutableListOf() } += it
            }
            parseWindow(rateLimit?.getAsJsonObject("secondary_window"), short = false)?.let {
                groups.getOrPut("每周窗口") { mutableListOf() } += it
            }
            // 附加限额（如 codex_other 等计量桶），结构可能演进，逐个防御解析
            obj.getAsJsonArray("additional_rate_limits")?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val name = o.get("display_name")?.asString
                    ?: o.get("limit_id")?.asString
                    ?: return@forEach
                val rl = o.getAsJsonObject("rate_limit") ?: o
                parseWindow(rl?.getAsJsonObject("primary_window"), short = true)?.let {
                    groups.getOrPut("其他限额") { mutableListOf() } += it.copy(label = name)
                }
            }
            return Usage(obj.get("plan_type")?.asString?.uppercase(), groups)
        }

        /** window: used_percent → 剩余/已用百分比；resets_after_seconds → 倒计时说明。 */
        private fun parseWindow(w: JsonObject?, short: Boolean): Bar? {
            val used = w?.get("used_percent")?.asDouble ?: return null
            val usedPct = used.toInt().coerceIn(0, 100)
            val remaining = (100.0 - used).coerceIn(0.0, 100.0).toInt()
            val resetSec = w.get("resets_after_seconds")?.asLong
            val mins = w.get("window_minutes")?.asLong
            val windowName = when {
                mins != null && mins >= 10000 -> "本周"
                mins != null -> "${mins / 60}小时窗口"
                short -> "5小时窗口"
                else -> "每周窗口"
            }
            val reset = resetSec?.takeIf { it > 0 }?.let { "重置：${formatCountdown(it * 1000)}后" }
            return Bar(label = windowName, percentRemaining = remaining, usedPercent = usedPct, reset = reset)
        }
    }

    // ─── Claude Code（OAuth 订阅额度）───

    object Claude {
        private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        data class Auth(val accessToken: String, val refreshToken: String?)

        /** 解析 ~/.claude/.credentials.json 粘贴内容（claudeAiOauth 嵌套或平铺）。 */
        fun parseCredentials(raw: String): Auth? = try {
            val input = raw.trim()
            if (!input.contains("{")) {
                val token = raw.filter { it in '!'..'~' }
                token.takeIf { it.length >= 40 }?.let { Auth(it, null) }
            } else {
                val obj = gson.fromJson(input, JsonObject::class.java)
                val oauth = obj?.getAsJsonObject("claudeAiOauth") ?: obj
                val access = oauth?.get("accessToken")?.asString
                access?.takeIf { it.isNotBlank() }?.let {
                    Auth(
                        accessToken = it,
                        refreshToken = oauth.get("refreshToken")?.asString
                    )
                }
            }
        } catch (_: Exception) {
            null
        }

        /** Claude Code OAuth refresh。 */
        fun refreshAccessToken(refreshToken: String): String {
            val body = gson.toJson(
                JsonObject().apply {
                    addProperty("client_id", CLIENT_ID)
                    addProperty("grant_type", "refresh_token")
                    addProperty("refresh_token", refreshToken)
                }
            ).toRequestBody(JSON_TYPE)
            val resp = QuotaHttp.post(TOKEN_URL, body, bearer = null, proxyFirst = true, ua = "claude-cli")
            val obj = gson.fromJson(resp, JsonObject::class.java)
            val access = obj?.get("access_token")?.asString
            return access ?: throw QuotaException(
                401,
                obj?.get("error_description")?.asString ?: obj?.get("error")?.asString ?: "刷新令牌失败"
            )
        }

        /**
         * 查询额度。响应为 { five_hour: {utilization, resets_at}|null, seven_day: ...,
         * seven_day_opus / seven_day_sonnet ... }，utilization 为已用百分比。
         */
        fun fetchUsage(accessToken: String): Usage {
            val resp = QuotaHttp.get(
                USAGE_URL,
                bearer = accessToken,
                proxyFirst = true,
                ua = "claude-cli/2.1.175 (external, cli)",
                extraHeaders = mapOf("anthropic-beta" to "oauth-2025-04-20")
            )
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            val groups = linkedMapOf<String, MutableList<Bar>>()

            fun barOf(key: String, label: String): Bar? {
                val w = obj.getAsJsonObject(key) ?: return null
                val util = w.get("utilization")?.asDouble ?: return null
                val used = if (util in 0.0..1.0 && util != util.toLong().toDouble()) util * 100 else util
                val usedPct = used.toInt().coerceIn(0, 100)
                val remaining = (100.0 - used).coerceIn(0.0, 100.0).toInt()
                val reset = w.get("resets_at")?.asString?.let { formatReset(it) }
                return Bar(label, remaining, usedPct, reset = reset)
            }

            barOf("five_hour", "5 小时会话")?.let { groups.getOrPut("滚动窗口") { mutableListOf() } += it }
            barOf("seven_day", "7 天总额")?.let { groups.getOrPut("滚动窗口") { mutableListOf() } += it }
            barOf("seven_day_opus", "Opus")?.let { groups.getOrPut("模型细分（7 天）") { mutableListOf() } += it }
            barOf("seven_day_sonnet", "Sonnet")?.let { groups.getOrPut("模型细分（7 天）") { mutableListOf() } += it }
            return Usage(null, groups)
        }
    }

    // ─── MiniMax（Token Plan / Coding Plan 请求次数额度）───

    object MiniMax {
        private val URLS = listOf(
            "https://api.minimax.io/v1/api/openplatform/coding_plan/remains",
            "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains",
        )

        /**
         * 查询额度。响应 model_remains[] 中 current_interval_usage_count /
         * current_weekly_usage_count 实为「剩余次数」（官方字段命名 bug，见 MiniMax-M2#99）。
         * 国际域名直连优先，失败回退国内域名与中转。
         */
        fun fetchUsage(apiKey: String): Usage {
            var lastErr: Exception? = null
            for (url in URLS) {
                try {
                    val resp = QuotaHttp.get(
                        url,
                        bearer = apiKey,
                        proxyFirst = false,
                        ua = "MiniMax-Usage-Checker"
                    )
                    val obj = gson.fromJson(resp, JsonObject::class.java) ?: continue
                    val base = obj.getAsJsonObject("base_resp")
                    val code = base?.get("status_code")?.asInt
                    if (code != null && code != 0) {
                        throw QuotaException(
                            code,
                            base.get("status_msg")?.asString ?: "MiniMax 错误 $code"
                        )
                    }
                    val groups = linkedMapOf<String, MutableList<Bar>>()
                    val remains = obj.getAsJsonArray("model_remains") ?: continue
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
                    throw e  // 业务错误（如 key 无效）不换端点重试
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: QuotaException(0, "MiniMax 查询失败")
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

/** HTTP 底座：直连 ⇄ 中转双路。 */
internal object QuotaHttp {

    private const val BUILTIN_PROXY = "https://proxy.20050912.xyz"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * [proxyFirst] = true：先走 Worker 中转（大陆可达），失败回退直连（有 VPN 时）；
     * false 则直连优先、中转兜底。
     */
    fun get(
        url: String,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String = execute(url, bearer, proxyFirst, ua, extraHeaders) { u, b ->
        Request.Builder().url(u).get().apply { b?.let { header("Authorization", "Bearer $it") } }.build()
    }

    fun post(
        url: String,
        body: okhttp3.RequestBody,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String
    ): String = execute(url, bearer, proxyFirst, ua, emptyMap()) { u, b ->
        Request.Builder().url(u).post(body).apply { b?.let { header("Authorization", "Bearer $it") } }.build()
    }

    private inline fun execute(
        target: String,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String>,
        build: (String, String?) -> Request
    ): String {
        var lastErr: Exception? = null
        val urls = if (proxyFirst) listOf(proxied(target), target) else listOf(target, proxied(target))
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
                        throw ExtraQuotaApi.QuotaException(resp.code, text.take(300).ifEmpty { "HTTP ${resp.code}" })
                    }
                    return text
                }
            } catch (e: ExtraQuotaApi.QuotaException) {
                // 鉴权类错误在直连/中转下语义一致，直接抛；其余错误试完所有线路再抛
                if (e.code == 401 || e.code == 403 || index == urls.distinct().lastIndex) throw e
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
