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

// ─── 无问芯穹 Infini AI（Coding Plan）───

object Infini {
    /** {"5_hour":{quota,used,remain},"7_day":{...},"30_day":{...}} */
    fun fetchUsage(apiKey: String): Usage {
        val resp = QuotaHttp.get(
            "https://cloud.infini-ai.com/maas/coding/usage",
            bearer = apiKey, proxyFirst = false, directOnly = true, ua = "VibeUsage/2.11"
        )
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "响应为空")
        val groups = linkedMapOf<String, MutableList<Bar>>()
        fun window(key: String, group: String) {
            val w = obj.optObject(key) ?: return
            val quota = jnum(w, "quota") ?: return
            if (quota <= 0) return
            val used = jnum(w, "used") ?: return
            val remain = jnum(w, "remain") ?: (quota - used)
            val pctUsed = used * 100.0 / quota
            groups.getOrPut(group) { mutableListOf() } += Bar(
                label = "Token 额度",
                percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                usedPercent = pctUsed.toInt().coerceIn(0, 100),
                counts = "剩 ${compact(remain)}/${compact(quota)}"
            )
        }
        window("5_hour", "5 小时窗口")
        window("7_day", "每周窗口")
        window("30_day", "每月窗口")
        return Usage(
            null, groups,
            fetchModelIds("https://cloud.infini-ai.com/maas/v1/models", apiKey, proxyFirst = false, directOnly = true)
        )
    }
}
