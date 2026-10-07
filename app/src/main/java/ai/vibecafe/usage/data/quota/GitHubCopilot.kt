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

// ─── GitHub Copilot（copilot_internal/user + 设备码授权）───

object GitHubCopilot {

    /** VS Code Copilot 扩展的公开 client_id（GitHub App 设备码流程，非机密）。 */
    private const val CLIENT_ID = "Iv1.b507a08c87ecfe98"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verifyUrl: String,
        val intervalSec: Long,
        val expiresSec: Long
    )

    /** 发起设备码授权：用户在浏览器打开 verification_uri 输入 user_code。 */
    fun startDeviceCode(): DeviceCode {
        val form = FormBody.Builder().add("client_id", CLIENT_ID).build()
        val resp = QuotaHttp.post(
            "https://github.com/login/device/code", form,
            bearer = null, proxyFirst = false, ua = "VibeUsage/2.14",
            extraHeaders = mapOf("Accept" to "application/json")
        )
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        return DeviceCode(
            deviceCode = jstr(obj, "device_code")
                ?: throw QuotaException(0, jstr(obj, "error_description") ?: "未返回 device_code"),
            userCode = jstr(obj, "user_code") ?: "",
            verifyUrl = jstr(obj, "verification_uri") ?: "https://github.com/login/device",
            intervalSec = jnum(obj, "interval")?.toLong() ?: 5L,
            expiresSec = jnum(obj, "expires_in")?.toLong() ?: 899L
        )
    }

    /**
     * 轮询一次授权结果：用户完成后返回 access_token；
     * 尚未确认（authorization_pending / slow_down）返回 null，其余错误抛出。
     */
    fun pollToken(deviceCode: String): String? {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val resp = QuotaHttp.post(
            "https://github.com/login/oauth/access_token", form,
            bearer = null, proxyFirst = false, ua = "VibeUsage/2.14",
            extraHeaders = mapOf("Accept" to "application/json")
        )
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        jstr(obj, "access_token")?.let { return it }
        return when (val err = jstr(obj, "error")) {
            "authorization_pending", "slow_down" -> null
            null -> throw QuotaException(0, "授权响应缺少 access_token")
            else -> throw QuotaException(0, jstr(obj, "error_description") ?: err)
        }
    }

    /** GET /copilot_internal/user：quota_snapshots（chat / completions / premium_interactions）。 */
    fun fetchUsage(token: String): Usage {
        val resp = QuotaHttp.get(
            "https://api.github.com/copilot_internal/user",
            bearer = token, proxyFirst = false, ua = "VibeUsage/2.14",
            extraHeaders = mapOf("Accept" to "application/vnd.github+json")
        )
        return parse(resp).copy(models = copilotModels(token))
    }

    /**
     * 账号可用模型：ghu token 换 copilot token（官方只认「已批准的客户端」，必须带编辑器头，
     * 否则 403 approved clients），再 GET api.githubcopilot.com/models（IDE 模型选择器同源，
     * 即账号/套餐维度，返回的已是该账号可调用集合，不做二次过滤）。
     * api.github.com 大陆直连不可达，走 QuotaHttp 直连→中转双路；失败返回空表。
     */
    private fun copilotModels(token: String): List<String> = try {
        val tk = gson.fromJson(
            QuotaHttp.get(
                "https://api.github.com/copilot_internal/v2/token",
                bearer = token, proxyFirst = false, ua = "GitHubCopilotChat/0.24.0",
                extraHeaders = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "Editor-Version" to "vscode/1.95.0",
                    "Editor-Plugin-Version" to "copilot-chat/0.24.0"
                )
            ), JsonObject::class.java
        )
        val ct = jstr(tk, "token") ?: return emptyList()
        val models = gson.fromJson(
            QuotaHttp.get(
                "https://api.githubcopilot.com/models",
                bearer = ct, proxyFirst = false, ua = "GitHubCopilotChat/0.24.0",
                extraHeaders = mapOf(
                    "Accept" to "application/json",
                    "Editor-Version" to "vscode/1.95.0",
                    "Editor-Plugin-Version" to "copilot-chat/0.24.0"
                )
            ), JsonObject::class.java
        )
        (models.optArray("data") ?: JsonArray())
            .mapNotNull { (it as? JsonObject)?.get("id")?.takeIf { v -> v.isJsonPrimitive }?.asString }
            .distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }

    internal fun parse(resp: String): Usage {
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
        val plan = jstr(obj, "copilot_plan") ?: jstr(obj, "access_type_sku")
        val snapshots = obj.optObject("quota_snapshots")
            ?: throw QuotaException(0, "响应缺少 quota_snapshots（确认 Token 已开通 Copilot 权限）")
        val resetDate = listOf("quota_reset_date_utc", "quota_reset_date")
            .firstNotNullOfOrNull { jstr(obj, it) }?.let { r ->
                try {
                    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(r.take(10))
                    d?.let { SimpleDateFormat("MM-dd", Locale.getDefault()).format(it) + " 重置" }
                } catch (_: Exception) { null }
            }
        val groups = linkedMapOf<String, MutableList<Bar>>()
        fun snap(key: String, label: String) {
            val s = snapshots.optObject(key) ?: return
            val unlimited = s.get("unlimited")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val entitlement = jnum(s, "entitlement") ?: 0.0
            val remaining = jnum(s, "remaining")
            val percent = jnum(s, "percent_remaining")
            val pctRemain = when {
                unlimited -> 100
                percent != null && percent >= 0 -> percent
                remaining != null && entitlement > 0 -> remaining * 100.0 / entitlement
                else -> 100.0
            }.toInt().coerceIn(0, 100)
            groups.getOrPut("订阅额度") { mutableListOf() } += Bar(
                label = label,
                percentRemaining = pctRemain,
                usedPercent = (100 - pctRemain).coerceIn(0, 100),
                counts = when {
                    unlimited -> "无限制"
                    remaining != null && entitlement > 0 -> "剩 ${compact(remaining)}/${compact(entitlement)}"
                    else -> null
                },
                reset = resetDate
            )
        }
        snap("chat", "聊天对话")
        snap("completions", "代码补全")
        snap("premium_interactions", "高级请求")
        if (groups.isEmpty()) throw QuotaException(0, "套餐未返回额度快照")
        return Usage(
            when (plan) {
                "free" -> "Free"
                "individual", "professional" -> "Pro"
                "pro_plus" -> "Pro+"
                "business" -> "Business"
                "enterprise" -> "Enterprise"
                null -> null
                else -> plan.replaceFirstChar { it.uppercase() }
            },
            groups
        )
    }
}
