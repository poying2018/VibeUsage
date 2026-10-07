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

// ─── DeepSeek（API 余额，官方接口）───

object DeepSeek {
    /** GET /user/balance（官方文档接口）：balance_infos[] 各币种余额（字符串）。 */
    fun fetchUsage(apiKey: String): Usage {
        val resp = QuotaHttp.get(
            "https://api.deepseek.com/user/balance",
            bearer = apiKey, proxyFirst = false, directOnly = true, ua = "VibeUsage/2.11"
        )
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "响应为空")
        val groups = linkedMapOf<String, MutableList<Bar>>()
        obj.optArray("balance_infos")?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val currency = jstr(o, "currency") ?: return@forEach
            val total = jstr(o, "total_balance") ?: return@forEach
            val granted = jstr(o, "granted_balance")
            val topped = jstr(o, "topped_up_balance")
            val symbol = when (currency) {
                "CNY" -> "¥"
                "USD" -> "$"
                else -> ""
            }
            groups.getOrPut("账户余额") { mutableListOf() } += Bar(
                label = "$currency 余额 $symbol$total",
                percentRemaining = 100,
                counts = listOfNotNull(
                    granted?.takeIf { it != "0.00" && it != "0" }?.let { "赠 $symbol$it" },
                    topped?.takeIf { it != "0.00" && it != "0" }?.let { "充 $symbol$it" }
                ).joinToString(" · ").ifEmpty { null }
            )
        }
        if (groups.isEmpty()) throw QuotaException(0, "未返回余额数据")
        val available = obj.get("is_available")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        return Usage(
            if (available) null else "已停用", groups,
            fetchModelIds("https://api.deepseek.com/models", apiKey, proxyFirst = false, directOnly = true, ua = "VibeUsage/2.14")
        )
    }
}
