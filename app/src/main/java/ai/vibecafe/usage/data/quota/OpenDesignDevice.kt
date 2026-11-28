package ai.vibecafe.usage.data.quota

import ai.vibecafe.usage.BuildConfig
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// ─── OpenDesign Go 浏览器授权（Vela CLI 同款设备码流程）───
//
// 实测自官方 Vela CLI 0.1.11 的登录请求（本地 stub 抓包）：
//   POST https://amr-api.open-design.ai/api/v1/cli/device-authorizations
//        {"capabilities":{"completionReceipt":true},"cliVersion":"...","host":"...","profile":"prod"}
//   → 201 {
//       "deviceId":"...", "userCode":"ABC123", "deviceSecret":"...",
//       "activationUrl":"https://open-design.ai/cloud/cli/activate?deviceId=...&userCode=...",
//       "expiresAt":"...Z", "pollIntervalSeconds":2, "status":"pending"
//     }
//   轮询（每 2 秒）：
//   POST /api/v1/cli/device-authorizations/{deviceId}/token
//        {"claimRegistrationInvite":true,"deviceSecret":"..."}
//   → pending 时 controlKey 为 null；用户在浏览器确认后返回 controlKey / runtimeKey / user。
//   取消：POST /api/v1/cli/device-authorizations/{deviceId}/cancel {"deviceSecret":"..."}
//
// 只把 controlKey 存进既有的 opendesign_key 槽位，用 [PREFIX] 前缀与手工粘贴的
// API Key 区分；两条通道的额度查询完全共用 OpenDesign.fetchUsage。
object OpenDesignDevice {

    const val PREFIX = "od_console:"

    private const val API = "https://amr-api.open-design.ai/api/v1"
    private const val PROFILE = "prod"
    private const val UA = "VibeUsage/" + BuildConfig.VERSION_NAME

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 设备码发起结果。 */
    data class DeviceCode(
        val deviceId: String,
        val userCode: String,
        val deviceSecret: String,
        val activationUrl: String,
        val expiresAtMs: Long,
        val intervalSec: Int
    )

    /** 浏览器确认后拿到的凭据。 */
    data class Token(
        val controlKey: String,
        val runtimeKey: String? = null,
        val controlKeyId: String? = null,
        val userId: String? = null,
        val linkUrl: String? = null,
        val apiUrl: String? = null,
        val profile: String? = null,
        val userEmail: String? = null
    )

    fun isLoginCred(cred: String): Boolean = cred.trim().startsWith(PREFIX)

    /** 发起设备码授权（在 IO 线程调用）。 */
    fun startDeviceCode(): DeviceCode {
        val caps = JsonObject().apply { addProperty("completionReceipt", true) }
        val body = JsonObject().apply {
            add("capabilities", caps)
            addProperty("cliVersion", BuildConfig.VERSION_NAME)
            addProperty("host", android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "VibeUsage")
            addProperty("profile", PROFILE)
        }
        val resp = try {
            QuotaHttp.post(
                "$API/cli/device-authorizations",
                body.toString().toRequestBody(JSON_TYPE),
                bearer = null,
                proxyFirst = false,
                ua = UA
            )
        } catch (e: QuotaException) {
            throw QuotaException(e.code, "发起 OpenDesign 授权失败：${e.message}")
        }
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "OpenDesign 授权接口返回空响应")
        return parseDeviceCode(obj)
            ?: throw QuotaException(0, "OpenDesign 授权接口未返回完整的设备码数据")
    }

    /** 纯函数，便于单测锁定字段口径。 */
    internal fun parseDeviceCode(obj: JsonObject?): DeviceCode? {
        if (obj == null) return null
        val deviceId = jstr(obj, "deviceId")?.takeIf { it.isNotBlank() } ?: return null
        val userCode = jstr(obj, "userCode")?.takeIf { it.isNotBlank() } ?: return null
        val secret = jstr(obj, "deviceSecret")?.takeIf { it.isNotBlank() } ?: return null
        val activation = jstr(obj, "activationUrl")?.takeIf { it.isNotBlank() }
            ?: "https://open-design.ai/cloud/cli/activate?deviceId=${OAuthFlow.enc(deviceId)}" +
                "&userCode=${OAuthFlow.enc(userCode)}"
        val expiresAt = jstr(obj, "expiresAt")?.let(::parseIsoMillis)
            ?: (System.currentTimeMillis() + 15 * 60_000L)
        val interval = (jnum(obj, "pollIntervalSeconds") ?: 2.0).toInt().coerceIn(2, 10)
        return DeviceCode(deviceId, userCode, secret, activation, expiresAt, interval)
    }

    /**
     * 轮询一次。null = 用户还没在浏览器确认；非 pending 的终止状态直接抛，
     * 避免 ViewModel 把「已拒绝 / 已过期」当网络抖动空转到超时。
     */
    fun pollToken(dc: DeviceCode): Token? {
        val body = JsonObject().apply {
            addProperty("claimRegistrationInvite", true)
            addProperty("deviceSecret", dc.deviceSecret)
        }
        val url = "$API/cli/device-authorizations/${OAuthFlow.enc(dc.deviceId)}/token"
        val resp = QuotaHttp.post(url, body.toString().toRequestBody(JSON_TYPE), null, false, UA)
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "OpenDesign 授权轮询返回空响应")
        val status = jstr(obj, "status")
        if (jstr(obj, "controlKey").isNullOrBlank()) {
            when (status) {
                "pending" -> return null
                "canceled" -> throw QuotaException(0, "已取消 OpenDesign 授权")
                "expired" -> throw QuotaException(0, "OpenDesign 授权码已过期，请重新发起")
                "consumed" -> throw QuotaException(0, "该 OpenDesign 授权已被使用，请重新发起")
                else -> throw QuotaException(0, "OpenDesign 授权状态异常：${status ?: "未知"}")
            }
        }
        return parseToken(obj)
            ?: throw QuotaException(0, "OpenDesign 授权未返回有效凭据")
    }

    /** 纯解析；controlKey 缺失即为无效响应。 */
    internal fun parseToken(obj: JsonObject?): Token? {
        if (obj == null) return null
        val key = jstr(obj, "controlKey")?.takeIf { it.isNotBlank() } ?: return null
        return Token(
            controlKey = key,
            runtimeKey = jstr(obj, "runtimeKey")?.takeIf { it.isNotBlank() },
            controlKeyId = jstr(obj, "controlKeyId")?.takeIf { it.isNotBlank() },
            userId = jstr(obj, "userId")?.takeIf { it.isNotBlank() },
            linkUrl = jstr(obj, "linkUrl")?.takeIf { it.isNotBlank() },
            apiUrl = jstr(obj, "apiUrl")?.takeIf { it.isNotBlank() },
            profile = jstr(obj, "profile")?.takeIf { it.isNotBlank() },
            userEmail = jstr(obj.optObject("user"), "email")?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * 官方 CLI 拿到 approved 令牌后还会调一次 /complete 收尾；缺了这一步服务端
     * 可能仍未把凭据标记为生效。返回 completionReceipt，失败直接抛终止错误。
     */
    fun complete(dc: DeviceCode): String? {
        val body = JsonObject().apply { addProperty("deviceSecret", dc.deviceSecret) }
        val url = "$API/cli/device-authorizations/${OAuthFlow.enc(dc.deviceId)}/complete"
        val resp = QuotaHttp.post(url, body.toString().toRequestBody(JSON_TYPE), null, false, UA)
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "OpenDesign 授权收尾返回空响应")
        return parseCompletionReceipt(obj)
            ?: throw QuotaException(0, "OpenDesign 授权收尾失败，请重新授权")
    }

    internal fun parseCompletionReceipt(obj: JsonObject?): String? {
        if (obj == null) return null
        if (jstr(obj, "status") != "completed") return null
        return jstr(obj, "completionReceipt")?.takeIf { it.isNotBlank() }
    }

    /** 授权成功后先用 controlKey 读一次身份，失败就不落盘。 */
    fun verify(token: Token) {
        try {
            QuotaHttp.get("$API/me", bearer = token.controlKey, proxyFirst = false, ua = UA)
        } catch (e: QuotaException) {
            if (e.code == 401) throw QuotaException(401, "OpenDesign 授权凭据无效，请重新授权")
            throw e
        }
    }

    /** 令牌包 → 存盘字符串（与粘贴 Key 共用一条 prefs 槽位）。 */
    internal fun encode(t: Token): String = PREFIX + gson.toJson(JsonObject().apply {
        addProperty("c", t.controlKey)
        t.runtimeKey?.let { addProperty("r", it) }
        t.controlKeyId?.let { addProperty("k", it) }
        t.userId?.let { addProperty("i", it) }
        t.linkUrl?.let { addProperty("l", it) }
        t.apiUrl?.let { addProperty("u", it) }
        t.profile?.let { addProperty("p", it) }
        t.userEmail?.let { addProperty("e", it) }
    })

    /** 非登录态返回 null，粘贴的 API Key 原样交给 [OpenDesign]。 */
    internal fun decode(cred: String): Token? {
        val raw = cred.trim()
        if (!raw.startsWith(PREFIX)) return null
        val obj = runCatching {
            gson.fromJson(raw.removePrefix(PREFIX), JsonObject::class.java)
        }.getOrNull() ?: return null
        val key = jstr(obj, "c")?.takeIf { it.isNotBlank() } ?: return null
        return Token(
            controlKey = key,
            runtimeKey = jstr(obj, "r")?.takeIf { it.isNotBlank() },
            controlKeyId = jstr(obj, "k")?.takeIf { it.isNotBlank() },
            userId = jstr(obj, "i")?.takeIf { it.isNotBlank() },
            linkUrl = jstr(obj, "l")?.takeIf { it.isNotBlank() },
            apiUrl = jstr(obj, "u")?.takeIf { it.isNotBlank() },
            profile = jstr(obj, "p")?.takeIf { it.isNotBlank() },
            userEmail = jstr(obj, "e")?.takeIf { it.isNotBlank() }
        )
    }
}
