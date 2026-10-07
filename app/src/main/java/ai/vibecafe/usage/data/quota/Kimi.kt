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

    /** usage{} 为周窗口，limits[] 中 window(300 MINUTE) 为 5 小时窗口；数值可能以字符串下发。 */
    fun fetchUsage(apiKey: String): Usage {
        var lastErr: Exception? = null
        for (url in URLS) {
            try {
                val resp = QuotaHttp.get(
                    url, bearer = apiKey, proxyFirst = false, directOnly = true, ua = "KimiCLI/1.6"
                )
                return parse(resp).copy(
                    models = fetchModelIds("https://api.moonshot.cn/v1/models", apiKey, proxyFirst = false, directOnly = true, ua = "KimiCLI/1.6")
                )
            } catch (e: QuotaException) {
                if (e.code != 404) throw e
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: QuotaException(0, "Kimi 查询失败")
    }

    private fun parse(resp: String): Usage {
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
                unit == "MINUTE" && duration != null -> "${duration / 60} 分钟窗口"
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
        return Usage(null, groups)
    }
}
