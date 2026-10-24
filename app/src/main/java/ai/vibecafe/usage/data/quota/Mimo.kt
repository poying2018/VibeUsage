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

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.compact
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.epochReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.fetchModelIds
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatCountdown
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

//     plan_total_token / compensation_total_token 做多键名容错取值；拿到真样本后应据实对齐。

object Mimo {
    private const val BASE = "https://platform.xiaomimimo.com/api/v1"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val NOT_SUBSCRIBED = "未订阅 Token Plan"
    private const val EXPIRED =
        "小米账号登录已失效，请重新复制 Cookie 里的 userId 与 api-platform_serviceToken"

    private val NUM_COOKIE = Regex("""userId["'\s]*[=:]["'\s]*(\d{4,})""")
    private val TOKEN_COOKIE = Regex("""api-platform_serviceToken["'\s]*[=:]["'\s]*"?([^";,\s]+)""")

    /**
     * 凭据归一：两格分别填 userId 与 serviceToken；但人往往整条 Cookie 一起复制，
     * 因此任一格出现 userId= / api-platform_serviceToken= 都按键名取，并去掉包裹的双引号。
     */
    internal fun normalizeCreds(a: String, b: String): Pair<String, String> {
        val blob = "$a;$b"
        val userId = NUM_COOKIE.find(blob)?.groupValues?.get(1)
            ?: a.trim().takeIf { it.matches(Regex("""\d{4,}""")) }
            ?: b.trim().takeIf { it.matches(Regex("""\d{4,}""")) }
            ?: throw QuotaException(
                0, "没找到 userId：platform.xiaomimimo.com 登录后 F12 → 网络 → 任意请求 → " +
                    "Cookie 里的 userId（纯数字）"
            )
        val token = TOKEN_COOKIE.find(blob)?.groupValues?.get(1)?.trim('"')
            ?: listOf(a, b).map { it.trim().trim('"') }.firstOrNull {
                it != userId && it.length >= 8 && it.any { c -> !c.isDigit() }
            }
            ?: throw QuotaException(
                0, "没找到 api-platform_serviceToken：同一份 Cookie 里最长的那串值（也可把整条 Cookie 粘进任一格）"
            )
        return userId to token
    }

    fun fetchUsage(userIdCred: String, tokenCred: String): Usage {
        val (userId, token) = normalizeCreds(userIdCred, tokenCred)
        val headers = mapOf(
            "Cookie" to "userId=$userId; api-platform_serviceToken=\"$token\"",
            "Referer" to "https://platform.xiaomimimo.com/console/balance"
        )
        fun get(path: String): String = try {
            QuotaHttp.get(
                BASE + path, bearer = null, proxyFirst = false, ua = UA,
                extraHeaders = headers, directOnly = true
            )
        } catch (e: QuotaException) {
            throw if (e.code == 401) QuotaException(401, EXPIRED) else e
        }
        val planText = get("/tokenPlan/usage")
        val detailText = runCatching { get("/tokenPlan/detail") }.getOrNull()
        val balanceText = runCatching { get("/balance") }.getOrNull()
        val groups = linkedMapOf<String, MutableList<Bar>>()
        parsePlan(planText, detailText).let { if (it.isNotEmpty()) groups["套餐额度"] = it.toMutableList() }
        // 余额与套餐互不牵连：余额拉不到不该把额度区一起带走（Agnes 赠费余额同款处理）
        balanceText?.let { t -> runCatching { parseBalance(t) }.getOrNull() }
            ?.let { if (it.isNotEmpty()) groups["账户余额"] = it.toMutableList() }
        if (groups.isEmpty())
            throw QuotaException(0, "小米 MiMo 没有返回余额或套餐额度数据（未订阅套餐时至少应有账户余额）")
        // detail 拉取失败时宁可不出胶囊，也不要把「没查到」说成「未订阅」
        return Usage(detailText?.let { accountOf(it) }, groups)
    }

    /** GET /balance：金额均为字符串，现金/赠费/冻结/透支拆在 counts 里。 */
    internal fun parseBalance(resp: String): List<Bar> {
        val data = envelope(resp).optObject("data") ?: return emptyList()
        val total = jnum(data, "balance") ?: return emptyList()
        val currency = jstr(data, "currency")
        val symbol = when (currency) {
            "USD" -> "$"
            "CNY" -> "¥"
            else -> ""
        }
        val parts = mutableListOf("现金 $symbol${money(jnum(data, "cashBalance") ?: total)}")
        for ((key, label) in listOf(
            "giftBalance" to "赠费", "frozenBalance" to "冻结", "overdraftLimit" to "可透支"
        )) {
            (jnum(data, key) ?: 0.0).takeIf { it > 0 }?.let { parts += "$label $symbol${money(it)}" }
        }
        return listOf(
            Bar(
                label = listOfNotNull(currency, "余额").joinToString(" ") + " $symbol${money(total)}",
                percentRemaining = 100,
                counts = parts.joinToString(" · ")
            )
        )
    }

    /**
     * GET /tokenPlan/usage（+ /tokenPlan/detail 供订阅判定与周期结束时间）。
     *
     * 已订阅 Lite 账号的实测形态：套餐周期额度在 `data.usage.items`、自然月累计在
     * `data.monthUsage.items`，条目键叫 **name**（plan_total_token / compensation_total_token /
     * month_total_token），而 **percent 是 0–1 的小数**（0.033 就是 3.3%）。所以一律以
     * used/limit 折算百分比，比例字段只在拿不到绝对量时兜底——直接对 0.033 取整会永远显示 0%。
     */
    internal fun parsePlan(resp: String, detailResp: String?): List<Bar> {
        val data = envelope(resp).optObject("data") ?: return emptyList()
        val detail = detailResp?.let { runCatching { envelope(it).optObject("data") }.getOrNull() }
        val items = listOf(data.optObject("usage"), data.optObject("monthUsage")).flatMap { group ->
            (group?.optArray("items") ?: JsonArray()).mapNotNull { it as? JsonObject }
        }
        val subscribed = detail?.let { jstr(it, "planName") != null } ?: items.isNotEmpty()
        if (!subscribed) return emptyList()
        val reset = detail?.let { periodEnd(it) }
        return items.mapNotNull { item ->
            val name = jstr(item, "name") ?: jstr(item, "type") ?: return@mapNotNull null
            val used = item.firstNum("used", "consumed", "usedCredits")
            val limit = item.firstNum("limit", "total", "quota")
            val percent = percentOf(used, limit, item.firstNum("percent", "usedPercent", "usagePercent"))
                ?: return@mapNotNull null
            // used 与 limit 都明确为 0 才是「这份额度没发放」（真值里的补偿积分）；
            // 字段缺失只表示服务端没给量，仍要靠 percent 画出来，不能一起当空丢掉
            if (used != null && limit != null && used <= 0 && limit <= 0) return@mapNotNull null
            Bar(
                label = when (name) {
                    "plan_total_token" -> "当前套餐用量"
                    "compensation_total_token" -> "补偿积分"
                    "month_total_token" -> "本月累计用量"
                    else -> name
                },
                percentRemaining = 100 - percent,
                usedPercent = percent,
                counts = if (used != null && limit != null && limit > 0)
                    "${credits(used)} / ${credits(limit)}" else null,
                // 只有套餐周期条挂重置时间，累计条再挂一遍只会看花眼
                reset = if (name == "plan_total_token") reset else null
            )
        }
    }

    /**
     * 已用百分比（0–100，按仓库惯例截断取整）。
     *
     * 有绝对量（used/limit）就一律按绝对量折算：比例字段在 1.0 附近有 0–1 与 0–100 两种
     * 口径（满额套餐可能显示成「已用 1%」），绝对量没有这种歧义。
     * 只有完全没有绝对量时才用比例字段：> 1 视为已是 0–100，否则视为 0–1 放大 100 倍。
     */
    private fun percentOf(used: Double?, limit: Double?, raw: Double?): Int? {
        if (used != null && limit != null) {
            if (limit > 0) return (used / limit * 100).toInt().coerceIn(0, 100)
            // 没有额度上限：已消耗即视为用尽
            return if (used > 0) 100 else 0
        }
        val r = raw ?: return null
        return (if (r > 1.0) r else r * 100).toInt().coerceIn(0, 100)
    }

    /** detail.currentPeriodEnd 实测是「2026-10-22 23:59:59」北京时间且不带时区标记。 */
    private fun periodEnd(detail: JsonObject): String? {
        val raw = jstr(detail, "currentPeriodEnd") ?: return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(raw)?.time?.let { epochReset(it) } ?: formatReset(raw)
        } catch (_: Exception) {
            formatReset(raw)
        }
    }

    /** 套餐名胶囊；订阅状态查不清（响应异常）时不出胶囊，明确未订阅才写「未订阅」。 */
    internal fun accountOf(detailResp: String): String? {
        val data = runCatching { envelope(detailResp).optObject("data") }.getOrNull() ?: return null
        val name = jstr(data, "planName") ?: return NOT_SUBSCRIBED
        val flags = listOfNotNull(
            if (data.bool("expired")) "已过期" else null,
            if (data.bool("enableAutoRenew")) "自动续订" else null
        )
        return if (flags.isEmpty()) name else "$name · ${flags.joinToString(" · ")}"
    }

    private fun envelope(resp: String): JsonObject {
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        val code = jnum(obj, "code")?.toInt() ?: 0
        if (code != 0) {
            val msg = jstr(obj, "message")?.takeIf { it.isNotBlank() } ?: "未知错误"
            throw QuotaException(code, if (code == 401) EXPIRED else "小米 MiMo 错误 $code：$msg")
        }
        return obj
    }

    private fun JsonObject.firstNum(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { jnum(this, it) }

    private fun JsonObject.bool(key: String): Boolean =
        (get(key) as? com.google.gson.JsonPrimitive)?.let { it.isBoolean && it.asBoolean } == true

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** Credits 紧凑记数：15170000000 → 151.7亿；整数量不带小数点。 */
    private fun credits(n: Double): String {
        val (v, unit) = when {
            n >= 1e8 -> n / 1e8 to "亿"
            n >= 1e4 -> n / 1e4 to "万"
            else -> return n.toLong().toString()
        }
        return String.format(Locale.US, if (v % 1.0 == 0.0) "%.0f$unit" else "%.1f$unit", v)
    }
}
