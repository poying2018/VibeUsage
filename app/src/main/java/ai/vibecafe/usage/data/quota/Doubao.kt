package ai.vibecafe.usage.data.quota

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
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
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.JSON_TYPE
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.compact
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.epochReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.fetchModelIds
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatCountdown
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

// ─── 豆包（消费者订阅额度：commerce 端点纯 Cookie 鉴权，实测无需 msToken/a_bogus 签名）───

object Doubao {
    private const val SUMMARY_URL =
        "https://www.doubao.com/alice/commerce/sale/subscription/quota/summary/"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 兼容三种粘贴形态：裸 sessionid 值 / sessionid=xxx / 整条 Cookie 串。 */
    private fun cookieOf(raw: String): String {
        val t = raw.trim().trim('"').trim().let { if (it.endsWith(";")) it.dropLast(1) else it }
        return if (t.isEmpty()) "" else if (t.contains("sessionid")) t else "sessionid=$t"
    }

    /**
     * 查询豆包订阅额度（标准套餐等消费者订阅，非火山方舟）。
     * POST /alice/commerce/sale/subscription/quota/summary/，body {}，仅凭 Cookie；
     * 响应 window_limits[].used_percent 即已用百分比，window_type 1=当前时段、2=近 7 天。
     */
    fun fetchUsage(credential: String): Usage {
        val cookie = cookieOf(credential)
        if (cookie.removePrefix("sessionid=").isBlank())
            throw QuotaException(0, "请粘贴豆包网页版 Cookie 里的 sessionid 值（F12 → 应用 → Cookie → doubao.com → sessionid）")
        val resp = try {
            QuotaHttp.post(
                SUMMARY_URL, "{}".toRequestBody(JSON_TYPE),
                bearer = null,
                proxyFirst = false,
                ua = UA,
                extraHeaders = mapOf(
                    "Cookie" to cookie,
                    "Referer" to "https://www.doubao.com/member/quota-management"
                ),
                directOnly = true
            )
        } catch (e: QuotaException) {
            throw if (e.code == 401) QuotaException(401, "豆包登录已失效，请重新复制网页版 Cookie 里的 sessionid") else e
        } catch (e: Exception) {
            throw QuotaException(0, e.message ?: "豆包查询失败")
        }
        return parse(resp)
    }

    internal fun parse(resp: String): Usage {
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        val code = jnum(obj, "code")?.toInt() ?: 0
        if (code != 0) {
            val msg = jstr(obj, "msg") ?: jstr(obj, "message") ?: "未知错误"
            // 710012001 = 登录已过期（实测无 Cookie 时的返回）
            throw QuotaException(
                if (code == 710012001 || msg.contains("登录")) 401 else code,
                when {
                    code == 710012001 || msg.contains("登录") ->
                        "豆包登录已失效，请重新复制网页版 Cookie 里的 sessionid"
                    else -> "豆包错误 $code：$msg"
                }
            )
        }
        val data = obj.optObject("data") ?: throw QuotaException(0, "响应中没有额度数据")
        val sub = data.optObject("current_subscription")
        // hasActiveSubscription 可能是 null：JsonNull 取 asBoolean 会抛 UnsupportedOperationException，
        // 整个解析崩掉、额度全丢，所以先确认是普通值再取（字符串 "true" 也认）
        val hasActiveSub = (data.optObject("member_info")?.get("hasActiveSubscription") as? JsonPrimitive)
            ?.let { if (it.isBoolean) it.asBoolean else it.asString.trim().equals("true", ignoreCase = true) }
            ?: false
        val account = sub?.optObject("display")?.let { jstr(it, "short_name") ?: jstr(it, "product_name") }
            ?: if (hasActiveSub) "豆包订阅" else "未订阅"
        val groups = linkedMapOf<String, MutableList<Bar>>()
        val bars = mutableListOf<Bar>()
        // window_limit_section：个人订阅窗口额度；企业窗口（enterprise_*）不在消费者订阅范围
        for (group in data.optObject("window_limit_section")?.optArray("window_limit_groups") ?: JsonArray()) {
            val g = group as? JsonObject ?: continue
            for (el in g.optArray("window_limits") ?: JsonArray()) {
                val w = el as? JsonObject ?: continue
                val used = jnum(w, "used_percent")?.toInt()?.coerceIn(0, 100) ?: continue
                val type = jnum(w, "window_type")?.toInt() ?: 0
                // 秒/毫秒归一：服务端可能下发秒级时间戳，直接当毫秒会落到 1970 年、倒计时为负
                val endMs = jnum(w, "end_time")?.toLong()?.let { normalizeEpochMs(it) } ?: 0L
                val startMs = jnum(w, "start_time")?.toLong()?.let { normalizeEpochMs(it) } ?: 0L
                val label = when (type) {
                    1 -> "当前时段"
                    2 -> "近 7 天"
                    else -> "窗口 $type"
                }
                bars += Bar(
                    label = label,
                    percentRemaining = (100 - used).coerceIn(0, 100),
                    usedPercent = used,
                    counts = if (used == 0 && startMs == 0L && endMs == 0L) "未消耗" else null,
                    reset = if (endMs > 0) epochReset(endMs)
                    else if (type == 1 && startMs == 0L) "开始使用后计时"
                    else null
                )
            }
        }
        if (bars.isNotEmpty()) groups["订阅额度"] = bars
        if (groups.isEmpty()) throw QuotaException(0, "响应中没有额度数据")
        return Usage(account, groups)
    }
}
