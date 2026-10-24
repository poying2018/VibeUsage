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

//      平时仅有一次额外跳的延迟；worker-quota.js 白名单已含 agnes 两域名）───

object Agnes {
    private const val BASE = "https://platform-backend.agnes-ai.cn"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126"

    /**
     * 网关(api.agnes-ai.cn)的 /v1 计费端点只有金额（美分）且其 /api/ 各路径被它自己的
     * Cloudflare WAF 挡死，token 数只能走平台后端：账号密码 POST /api/auth/token_by_username
     * 换 JWT（每次现登，免管过期），再 GET /api/usage/series?range=custom&start_date=&end_date=
     * 取逐日 text_tokens / request_count（时区 Asia/Shanghai）。
     */
    fun fetchUsage(username: String, password: String): Usage {
        val loginBody = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }.toString().toRequestBody(JSON_TYPE)
        val jwt = try {
            jstr(
                envelope(
                    QuotaHttp.post(
                        "$BASE/api/auth/token_by_username", loginBody,
                        bearer = null, proxyFirst = true, ua = UA
                    )
                ), "access_token"
            )
        } catch (e: QuotaException) {
            throw if (e.code == 401) QuotaException(401, "Agnes 登录失败，请检查邮箱/用户名和密码") else e
        } catch (e: Exception) {
            throw QuotaException(0, e.message ?: "Agnes 登录失败")
        } ?: throw QuotaException(0, "Agnes 登录响应中没有 token")

        val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        // 服务端口径是 Asia/Shanghai：按设备本地时区算「今天 / 本月」会差一天
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        fun window(from: java.time.LocalDate): Pair<Double, Double> {
            val data = call("/api/usage/series?range=custom&start_date=${from.format(fmt)}&end_date=${today.format(fmt)}", jwt)
            var tokens = 0.0
            var reqs = 0.0
            for (el in data.optArray("items") ?: JsonArray()) {
                val o = el as? JsonObject ?: continue
                tokens += jnum(o, "text_tokens") ?: 0.0
                reqs += jnum(o, "request_count") ?: 0.0
            }
            return tokens to reqs
        }
        val (dayTokens, dayReqs) = window(today)
        val (monTokens, monReqs) = window(today.withDayOfMonth(1))

        fun bar(tokens: Double, reqs: Double, emptyText: String) = Bar(
            label = if (tokens > 0) "已用 ${compact(tokens)} tokens" else emptyText,
            percentRemaining = 100,
            counts = if (reqs > 0) "${compact(reqs)} 次请求 · 免费不限量" else "免费档 · 不限量"
        )
        // 赠费余额：available_bonus_minor 字段名带 minor 但实为元单位（实测随调用扣减），
        // 非 ¥0 模型调用就从这里按量扣；拿不到不阻塞面板
        val bonus = try {
            jnum(call("/api/user/balance", jwt), "available_bonus_minor")
        } catch (_: Exception) {
            null
        }
        val groups = linkedMapOf(
            "今日消耗" to listOf(bar(dayTokens, dayReqs, "今日无消耗")),
            "本月消耗" to listOf(bar(monTokens, monReqs, "本月无消耗"))
        )
        bonus?.let {
            groups["赠费余额"] = listOf(
                Bar(
                    label = "¥${String.format(Locale.US, "%.2f", it)} 可用",
                    percentRemaining = 100,
                    counts = "非 ¥0 模型调用按量从赠费扣除"
                )
            )
        }
        return Usage(
            "免费档",
            groups,
            models = agnesModels(jwt),
            modelsLabel = "免费模型（¥0）"
        )
    }

    /**
     * 官方文档（agnes-ai.cn/doc/<id>）RMB Pricing 卡「Current Price」为 ¥0 的模型，
     * 即免费档真正 0 元可用的全部：flash 文本（原价 ¥0.2/¥1 已划线）、image-2.1-flash
     * （全分辨率档现价 ¥0）、video-v2.0（¥0/秒）。pro/pro-alpha/pro-beta 官方明示付费
     * （输入 ¥3/输出 ¥6），image-2.5-flash、video-2.5、video-2.5-flash 连定价页都没有
     * ——计费与未定价模型一律不算免费档支持。agnes-2.0-flash 虽同为 ¥0 但已弃用，不在集合内。
     */
    private val FREE_MODELS = setOf("agnes-2.5-flash", "agnes-image-2.1-flash", "agnes-video-v2.0")

    /**
     * 免费档模型列表：用 JWT 找到名下第一个 API Token 并换出完整密钥，问网关 /v1/models
     * 拿该 Key 真实可调用的模型，再与官方 0 元定价取交集（网关全列表含计费模型，不做分档依据）。
     * 任一步失败不影响额度显示（返回空列表 → 面板不渲染模型区）。
     */
    private fun agnesModels(jwt: String): List<String> = try {
        val tokens = call("/api/token", jwt).optArray("items") ?: JsonArray()
        val first = (tokens.firstOrNull { (it as? JsonObject)?.get("status")?.asInt == 1 } ?: tokens.firstOrNull()) as? JsonObject
            ?: return emptyList()
        val id = jnum(first, "id")?.toInt() ?: return emptyList()
        // 揭示完整密钥是无文档路由，只认 POST（GET 405）；空 body 即可
        val key = jstr(callPost("/api/token/$id/key", jwt), "key") ?: return emptyList()
        val skKey = if (key.startsWith("sk-")) key else "sk-$key"
        val models = gson.fromJson(
            QuotaHttp.get(
                "https://api.agnes-ai.cn/v1/models", bearer = skKey,
                proxyFirst = true, ua = UA
            ),
            JsonObject::class.java
        )
        (models.optArray("data") ?: JsonArray())
            .mapNotNull { (it as? JsonObject)?.get("id")?.takeIf { v -> v.isJsonPrimitive }?.asString }
            .filter { it in FREE_MODELS }  // 按官方定价分档：0 元才算免费档可用
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    /** 平台后端统一封套 {code, message, data}：code=200 → data；登录失效 → 401 语义；其余原样抛。 */
    private fun envelope(resp: String): JsonObject {
        val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "Agnes 响应为空")
        val code = jnum(obj, "code")?.toInt() ?: 200
        if (code != 200) {
            throw QuotaException(
                if (code == 401) 401 else code,
                "Agnes：${jstr(obj, "message") ?: "错误 $code"}"
            )
        }
        return obj.optObject("data") ?: JsonObject()
    }

    private fun call(path: String, bearer: String): JsonObject = try {
        envelope(
            QuotaHttp.get(BASE + path, bearer = bearer, proxyFirst = true, ua = UA)
        )
    } catch (e: QuotaException) {
        throw if (e.code == 401) QuotaException(401, "Agnes 登录已失效，请重新接入") else e
    } catch (e: Exception) {
        throw QuotaException(0, e.message ?: "Agnes 查询失败")
    }

    /** call 的 POST 版（揭示密钥等写路由用），空 body。 */
    private fun callPost(path: String, bearer: String): JsonObject = try {
        envelope(
            QuotaHttp.post(
                BASE + path, ByteArray(0).toRequestBody(null),
                bearer = bearer, proxyFirst = true, ua = UA
            )
        )
    } catch (e: QuotaException) {
        throw if (e.code == 401) QuotaException(401, "Agnes 登录已失效，请重新接入") else e
    }
}
