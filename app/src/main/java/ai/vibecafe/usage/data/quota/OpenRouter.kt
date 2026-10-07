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
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.JSON_TYPE
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.compact
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.epochReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.fetchModelIds
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatCountdown
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

// ─── OpenRouter（官方 /api/v1/key：Key 限额与用量）───

object OpenRouter {

    private const val API = "https://openrouter.ai/api/v1"
    private const val UA = "VibeUsage/2.14"

    /** OAuth 授权码换 Key（POST /auth/keys，官方 PKCE 流程）：返回 sk-or-v1-...。 */
    fun exchangeOAuthCode(code: String, verifier: String): String {
        val body = gson.toJson(
            JsonObject().apply {
                addProperty("code", code)
                addProperty("code_verifier", verifier)
                addProperty("code_challenge_method", "S256")
            }
        ).toRequestBody(JSON_TYPE)
        val resp = QuotaHttp.post(
            "$API/auth/keys", body,
            bearer = null, proxyFirst = true, ua = UA
        )
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        return jstr(obj, "key") ?: jstr(obj.optObject("data"), "key")
            ?: throw QuotaException(0, "授权成功但未返回 Key，请重试")
    }

    /** GET /api/v1/key：data.usage（已花费 USD）/ limit（限额，null = 无限额）/ is_free_tier。 */
    fun fetchUsage(apiKey: String): Usage {
        val resp = QuotaHttp.get(
            "$API/key", bearer = apiKey, proxyFirst = true, ua = "VibeUsage/2.14"
        )
        // 标准 Key 可调用整个目录，目录即账号可用范围；域名需中转兜底
        return parse(resp).copy(
            models = fetchModelIds("$API/models", apiKey, proxyFirst = true, ua = "VibeUsage/2.14")
        )
    }

    internal fun parse(resp: String): Usage {
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        val data = obj.optObject("data") ?: throw QuotaException(0, "响应缺少 data")
        val label = jstr(data, "label")
        val freeTier = data.get("is_free_tier")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val usage = jnum(data, "usage") ?: 0.0
        val limit = jnum(data, "limit")
        val groups = linkedMapOf<String, MutableList<Bar>>()
        groups["Key 额度"] = mutableListOf(
            if (limit != null && limit > 0) {
                val pctUsed = (usage / limit * 100.0).coerceIn(0.0, 100.0)
                Bar(
                    label = label ?: "Default Key",
                    percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                    usedPercent = pctUsed.toInt().coerceIn(0, 100),
                    counts = String.format(Locale.US, "已用 $%.2f / 限额 $%.2f", usage, limit)
                )
            } else {
                Bar(
                    label = label ?: "Default Key",
                    percentRemaining = 100,
                    counts = String.format(
                        Locale.US, "已用 $%.2f · 无限额%s", usage,
                        if (freeTier) "（免费层）" else ""
                    )
                )
            }
        )
        jnum(data, "usage_daily")?.let { daily ->
            groups["今日用量"] = mutableListOf(
                Bar(
                    label = "今日消耗",
                    percentRemaining = 100,
                    counts = String.format(Locale.US, "$%.2f", daily)
                )
            )
        }
        return Usage(if (freeTier) "Free 层" else null, groups)
    }
}
