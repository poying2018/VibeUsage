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

// ─── Kimi For Coding（月之暗面）───

object Kimi {
    private val URLS = listOf(
        "https://api.kimi.com/coding/v1/usages",
        "https://api.kimi.com/coding/v1/usage",
    )

    /**
     * 单次查询的总耗时预算（毫秒）。
     *
     * 直连超时上限是 connect 15s + read 20s ≈ 35s，预算要比它大，
     * 否则第一个 URL 慢失败后第二个 URL 根本轮不到（回退就白做了）。
     */
    private const val TOTAL_BUDGET_MS = 40_000L

    /** usage{} 为周窗口，limits[] 中 window(300 MINUTE) 为 5 小时窗口；数值可能以字符串下发。 */
    fun fetchUsage(apiKey: String): Usage =
        withHostFallback(URLS, TOTAL_BUDGET_MS, emptyMessage = "Kimi 查询失败") { url ->
            val resp = QuotaHttp.get(
                url, bearer = apiKey, proxyFirst = false, directOnly = true, ua = "KimiCLI/1.6"
            )
            parseUsage(resp).copy(
                models = fetchModelIds("https://api.moonshot.cn/v1/models", apiKey, proxyFirst = false, directOnly = true, ua = "KimiCLI/1.6")
            )
        }

    internal fun parseUsage(resp: String): Usage {
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "响应为空")
        val groups = linkedMapOf<String, MutableList<Bar>>()
        obj.optObject("usage")?.let { u ->
            val limit = jnum(u, "limit")
            val remaining = jnum(u, "remaining")
            if (limit != null && limit > 0 && remaining != null) {
                val pctUsed = (limit - remaining).coerceAtLeast(0.0) * 100.0 / limit
                groups.getOrPut("每周窗口") { mutableListOf() } += Bar(
                    label = "周额度",
                    percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                    usedPercent = pctUsed.toInt().coerceIn(0, 100),
                    counts = "剩 ${compact(remaining)}/${compact(limit)}",
                    reset = jstr(u, "resetTime")?.let { formatReset(it) }
                )
            }
        }
        obj.optArray("limits")?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val detail = o.optObject("detail") ?: return@forEach
            val limit = jnum(detail, "limit") ?: return@forEach
            val remaining = jnum(detail, "remaining") ?: return@forEach
            if (limit <= 0) return@forEach
            val duration = jnum(o.optObject("window"), "duration")?.toLong()
            val unit = jstr(o.optObject("window"), "timeUnit") ?: "MINUTE"
            val label = when {
                unit == "MINUTE" && duration == 300L -> "5 小时窗口"
                unit == "HOUR" && duration == 5L -> "5 小时窗口"
                unit == "DAY" && duration == 1L -> "每日窗口"
                // MINUTE 单位本意是换算成小时（300 分钟 = 5 小时窗口）；不足 1 小时才退回分钟标签
                unit == "MINUTE" && duration != null ->
                    if (duration >= 60) "${duration / 60} 小时窗口" else "$duration 分钟窗口"
                unit == "HOUR" && duration != null -> "$duration 小时窗口"
                else -> return@forEach
            }
            val used = jnum(detail, "used") ?: (limit - remaining).coerceAtLeast(0.0)
            val pctUsed = used * 100.0 / limit
            groups.getOrPut(label) { mutableListOf() } += Bar(
                label = "积分额度",
                percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                usedPercent = pctUsed.toInt().coerceIn(0, 100),
                counts = "剩 ${compact(remaining)}/${compact(limit)}",
                reset = jstr(detail, "resetTime")?.let { formatReset(it) }
            )
        }
        // groups 为空说明这个 URL 没给出额度数据：抛出去让 fetchUsage 回退到第二个 URL，
        // 否则用户只会看到「计划未返回额度数据」而错过备用端点的真实结果
        if (groups.isEmpty()) throw QuotaException(0, "计划未返回额度数据")
        return Usage(null, groups)
    }
}
