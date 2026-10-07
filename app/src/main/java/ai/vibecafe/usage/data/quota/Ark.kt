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

// ─── 火山方舟 Coding Plan（火山引擎 OpenAPI，AK/SK V4 签名）───

object Ark {
    private const val ENDPOINT = "https://open.volcengineapi.com/?Action=GetCodingPlanUsage&Version=2024-01-01"
    private const val REGION = "cn-beijing"
    private const val SERVICE = "ark"
    private const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
    private const val SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"

    /** Result.QuotaUsage[]：Level(session/5h/weekly/monthly) + Percent(已用) + ResetTimestamp。 */
    fun fetchUsage(accessKey: String, secretKey: String): Usage {
        val payload = ""
        val payloadHash = sha256Hex(payload.toByteArray())
        val xDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        val dateStamp = xDate.take(8)
        val uri = java.net.URI(ENDPOINT)
        val host = uri.host
        val canonicalQuery = (uri.rawQuery ?: "").split("&")
            .filter { it.isNotEmpty() }
            .map { kv ->
                val i = kv.indexOf('=')
                if (i < 0) percentEncode(kv) to ""
                else percentEncode(kv.take(i)) to percentEncode(kv.substring(i + 1))
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val canonicalRequest = listOf(
            "POST", "/", canonicalQuery,
            "content-type:$CONTENT_TYPE",
            "host:$host",
            "x-content-sha256:$payloadHash",
            "x-date:$xDate",
            "",
            SIGNED_HEADERS,
            payloadHash
        ).joinToString("\n")
        val scope = "$dateStamp/$REGION/$SERVICE/request"
        val stringToSign = "HMAC-SHA256\n$xDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
        val signature = hex(
            hmac(hmac(hmac(hmac(secretKey.toByteArray(), dateStamp), REGION), SERVICE), "request"),
            stringToSign
        )
        val authorization = "HMAC-SHA256 Credential=$accessKey/$scope, " +
            "SignedHeaders=$SIGNED_HEADERS, Signature=$signature"
        val resp = QuotaHttp.post(
            ENDPOINT,
            payload.toByteArray().toRequestBody(CONTENT_TYPE.toMediaType()),
            bearer = null,
            proxyFirst = false,
            ua = "VibeUsage/2.11",
            extraHeaders = mapOf(
                "Authorization" to authorization,
                "X-Date" to xDate,
                "X-Content-Sha256" to payloadHash,
                "Content-Type" to CONTENT_TYPE,
                "Accept" to "application/json"
            ),
            directOnly = true
        )
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "响应为空")
        obj.optObject("ResponseMetadata")?.optObject("Error")?.let { err ->
            val code = jstr(err, "Code")
            val msg = jstr(err, "Message")
            throw QuotaException(
                403,
                listOfNotNull(code, msg).joinToString("：").ifEmpty { "方舟签名校验失败" }
            )
        }
        val quotaUsage = obj.optObject("Result")?.optArray("QuotaUsage")
            ?: throw QuotaException(0, "响应缺少 QuotaUsage 数据")
        val groups = linkedMapOf<String, MutableList<Bar>>()
        for (el in quotaUsage) {
            val o = el as? JsonObject ?: continue
            val level = jstr(o, "Level") ?: continue
            val percent = jnum(o, "Percent") ?: continue
            val label = when (level.lowercase()) {
                "session" -> "会话窗口"
                "5h", "5-hour", "five_hour" -> "5 小时窗口"
                "weekly", "week" -> "每周窗口"
                "monthly", "month" -> "每月窗口"
                else -> level
            }
            val resetMs = jnum(o, "ResetTimestamp")?.toLong()?.let { if (it > 1e11) it else it * 1000 }
            groups.getOrPut(label) { mutableListOf() } += Bar(
                label = label,
                percentRemaining = (100 - percent).toInt().coerceIn(0, 100),
                usedPercent = percent.toInt().coerceIn(0, 100),
                reset = resetMs?.takeIf { it > 0 }?.let { epochReset(it) }
            )
        }
        if (groups.isEmpty()) throw QuotaException(0, "套餐未返回额度窗口")
        return Usage(null, groups)
    }

    private fun percentEncode(v: String): String {
        val sb = StringBuilder()
        for (b in v.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(String.format("%02X", b))
            }
        }
        return sb.toString()
    }

    private fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray())

    private fun hex(key: ByteArray, msg: String): String =
        hmac(key, msg).joinToString("") { String.format("%02x", it) }

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { String.format("%02x", it) }
}
