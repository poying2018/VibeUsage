package ai.vibecafe.usage.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * vibecafe.ai 设备码登录（官网 /usage「输入验证码链接设备」通道）。
 *
 * 官方收紧 API Key 自助创建后，第三方客户端改走设备码授权：App 发起拿到
 * 8 位验证码（ABCD-EFGH），自动打开 verificationUriComplete；用户在已登录
 * vibecafe.ai 的浏览器里点「批准」，App 轮询换取可直接用于 /api/usage
 * Bearer 鉴权的凭据，落盘逻辑与手动粘贴 Key 完全一致。
 *
 * 接口行为（2026-11-08 无鉴权实测）：
 * - POST /api/usage/device/code  {} → {deviceCode, userCode, verificationUri,
 *   verificationUriComplete, expiresIn: 900, interval: 5}
 * - POST /api/usage/device/poll  {deviceCode} → 等待时 200 {"error":"authorization_pending"}；
 *   未知/失效码 400 {"error":"invalid_grant"}
 * - 批准后的成功报文无账号侧样本可抓，凭据字段按 apiKey/api_key/accessToken/
 *   access_token/token/key 兜底，再回退扫描顶层 vibe_ 前缀字符串。
 */
object VibeCafeDeviceLogin {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 发起结果。 */
    data class Start(
        val deviceCode: String,
        val userCode: String,
        val verifyUrl: String,
        val expiresInSec: Int,
        val intervalSec: Int
    )

    /** 一次轮询的结论。 */
    sealed interface PollResult {
        data class Approved(val apiKey: String) : PollResult
        data object Pending : PollResult
        data object Denied : PollResult
        data object Expired : PollResult
        /** [transient] 的失败按网络抖动处理：继续轮询，不当成授权失败终止流程。 */
        data class Failed(val message: String, val transient: Boolean = false) : PollResult
    }

    /** 发起设备码授权（网络错误抛 IOException，由调用方给出用户可读文案）。 */
    suspend fun start(): Start = withContext(Dispatchers.IO) {
        val resp = RetrofitClient.api.startDeviceLogin(jsonBody())
        val body = resp.body()?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
        parseStart(body) ?: throw IOException("响应缺少 deviceCode")
    }

    /** 轮询一次授权结果；网络抖动抛异常，等待/终态由返回值表达。 */
    suspend fun poll(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val resp = RetrofitClient.api.pollDeviceLogin(jsonBody("deviceCode" to deviceCode))
        val body = resp.body()?.string().orEmpty()
        parsePoll(resp.code(), body)
    }

    /** 驼峰为主、蛇形兜底地解析发起报文。 */
    fun parseStart(body: String): Start? {
        val obj = parseObject(body) ?: return null
        val deviceCode = str(obj, "deviceCode", "device_code") ?: return null
        val userCode = str(obj, "userCode", "user_code") ?: return null
        val verifyUrl = str(obj, "verificationUriComplete", "verification_uri_complete")
            ?: str(obj, "verificationUri", "verification_uri")
                ?.let { base -> if (base.contains("?")) base else "$base?code=${URLEncoder.encode(userCode, "UTF-8")}" }
            ?: "https://vibecafe.ai/usage/device?code=${URLEncoder.encode(userCode, "UTF-8")}"
        return Start(
            deviceCode = deviceCode,
            userCode = userCode,
            verifyUrl = verifyUrl,
            expiresInSec = num(obj, "expiresIn", "expires_in")?.toInt() ?: 900,
            intervalSec = num(obj, "interval")?.toInt()?.coerceAtLeast(3) ?: 5
        )
    }

    /**
     * 解析轮询响应。pending 不是终态；批准后没认出凭据字段时报 Failed 而不是
     * 静默失败——上线初期响应格式如有出入，第一轮真机授权就能暴露出来。
     */
    fun parsePoll(status: Int, body: String): PollResult {
        val obj = parseObject(body) ?: run {
            // 5xx / 429 多为网关或限流抖动，轮询还有十几分钟窗口，不值得就此终止
            val transient = status >= 500 || status == 429
            return PollResult.Failed("HTTP $status：响应异常", transient)
        }
        when (str(obj, "error")?.lowercase()) {
            "authorization_pending", "slow_down" -> return PollResult.Pending
            "access_denied" -> return PollResult.Denied
            "expired_token", "invalid_grant" -> return PollResult.Expired
        }
        credential(obj)?.let { return PollResult.Approved(it) }
        if (status in 200..299) {
            // 凭据字段名不含敏感值；上线初期真实批准一次就能校准解析
            val names = obj.keySet().take(6).joinToString(",")
            return PollResult.Failed("授权完成但响应中没有凭据字段（字段: $names）")
        }
        val error = str(obj, "error")?.lowercase().orEmpty()
        return PollResult.Failed("HTTP $status：${error.ifEmpty { "未知错误" }}", status >= 500 || status == 429)
    }

    /** 已知凭据字段优先，再按服务端 Key 前缀 vibe_ 全顶层扫描兜底。 */
    private fun credential(obj: JsonObject): String? {
        for (name in listOf("apiKey", "api_key", "accessToken", "access_token", "token", "key")) {
            str(obj, name)?.let { return it }
        }
        for ((_, value) in obj.entrySet()) {
            val prim = value as? JsonPrimitive ?: continue
            if (prim.isString && prim.asString.trim().startsWith("vibe_")) return prim.asString.trim()
        }
        return null
    }

    private fun parseObject(body: String): JsonObject? = runCatching {
        JsonParser.parseString(body).asJsonObject
    }.getOrNull()

    private fun str(obj: JsonObject, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            (obj.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun num(obj: JsonObject, vararg names: String): Double? =
        names.firstNotNullOfOrNull { name ->
            (obj.get(name) as? JsonPrimitive)?.takeIf { it.isNumber }?.asDouble
        }

    private fun jsonBody(vararg pairs: Pair<String, String>) =
        JsonObject().apply { pairs.forEach { (k, v) -> addProperty(k, v) } }
            .toString()
            .toRequestBody(JSON)
}
