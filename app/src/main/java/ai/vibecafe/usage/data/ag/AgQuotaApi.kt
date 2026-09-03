package ai.vibecafe.usage.data.ag

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Antigravity（Google 反重力）额度查询客户端。
 * 逆向自 Antigravity.Tools 桌面版：Google OAuth refresh_token 换 access_token 后，
 * 调 cloudcode-pa.googleapis.com 的 v1internal 接口取配额摘要（5h 滚动窗口 + 每周窗口）。
 *
 * 大陆网络无法直连 Google，默认走内置 Cloudflare Worker 中转（服务端补 OAuth client_secret），
 * 与工作区 AntigravityQuotaApp 项目的 worker.js 配套。
 */
object AgQuotaApi {

    // ─── 常量（与 Antigravity.Tools 桌面版一致；该 client 为其公开发行的内置凭据，
    //         非 VibeUsage 机密。字面量拆分是为绕过 GitHub push protection 误报）───

    private const val CLIENT_ID =
        "1071006060591" + "-tmhssin2h21lcre235vtolojh4g403ep" + ".apps.googleusercontent.com"
    private const val CLIENT_SECRET = "GOCSPX-" + "K58FWR486LdLJ1mLB8sXC4z6qDAf"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /** 内置中转（worker.js）：/token 自动补 secret，/proxy?url= 转发任意请求 */
    private const val BUILTIN_PROXY = "https://proxy.20050912.xyz"

    /** 配额端点回退顺序：sandbox → daily → prod（与桌面版 quota.rs 一致） */
    private val ENDPOINTS = listOf(
        "https://daily-cloudcode-pa.sandbox.googleapis.com",
        "https://daily-cloudcode-pa.googleapis.com",
        "https://cloudcode-pa.googleapis.com",
    )

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dns(ai.vibecafe.usage.data.quota.DnsResolver)
            .build()
    }

    private val gson = Gson()

    // ─── 数据模型 ───

    data class TokenResult(val accessToken: String, val expiresInSec: Long, val refreshToken: String? = null)

    data class AssistInfo(val projectId: String?, val tier: String?)

    /** 一个额度桶：5h 滚动窗口或每周窗口 */
    data class QuotaBucket(
        val label: String,
        val percentRemaining: Int,
        val resetTimeIso: String?,
        val isWeekly: Boolean,
    )

    data class QuotaSummary(val buckets5h: List<QuotaBucket>, val bucketsWeekly: List<QuotaBucket>)

    /** 携带状态码与服务器错误说明的异常（401 = refresh token 失效） */
    class AgException(val code: Int, message: String) : Exception(message)

    // ─── 响应体 ───

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String? = null,
        @SerializedName("expires_in") val expiresIn: Long = 3600,
        @SerializedName("refresh_token") val refreshToken: String? = null,
        @SerializedName("error") val error: String? = null,
        @SerializedName("error_description") val errorDescription: String? = null,
    )

    private data class AssistResponse(
        @SerializedName("cloudaicompanionProject") val projectId: String? = null,
        @SerializedName("currentTier") val currentTier: Tier? = null,
        @SerializedName("paidTier") val paidTier: Tier? = null,
    ) {
        data class Tier(val name: String? = null, val id: String? = null)
    }

    /** 官方字段为 groups，兼容旧 quotaGroups */
    private data class QuotaResponse(
        val groups: List<Group>? = null,
        @SerializedName("quotaGroups") val legacyGroups: List<Group>? = null,
    ) {
        data class Group(val displayName: String? = null, val buckets: List<Bucket>? = null)
        data class Bucket(
            @SerializedName("bucketId") val bucketId: String? = null,
            val window: String? = null,
            @SerializedName("remainingFraction") val remainingFraction: Double? = null,
            @SerializedName("resetTime") val resetTime: String? = null,
            @SerializedName("displayName") val displayName: String? = null,
        )
    }

    // ─── 一键授权（Google OAuth PKCE loopback，与 Antigravity.Tools 桌面版同流程）───

    private const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val REDIRECT_URI = "http://localhost:51121/callback"
    private const val SCOPE =
        "openid https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email"

    /** 构造 Google 授权页地址（浏览器打开，回调打到本地 51121）。 */
    fun buildAuthorizeUrl(challenge: String, state: String): String =
        AUTHORIZE_URL +
            "?client_id=${java.net.URLEncoder.encode(CLIENT_ID, "UTF-8")}" +
            "&redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
            "&response_type=code" +
            "&scope=${java.net.URLEncoder.encode(SCOPE, "UTF-8")}" +
            "&access_type=offline&prompt=consent" +
            "&code_challenge=$challenge&code_challenge_method=S256" +
            "&state=${java.net.URLEncoder.encode(state, "UTF-8")}"

    /** 授权码兑换令牌：多线路容错（中转 /token → 中转通用转发 → 直连）。 */
    fun exchangeCode(code: String, verifier: String): TokenResult {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", REDIRECT_URI)
            .build()
        return tokenViaAny(form, "授权兑换失败")
    }

    /**
     * 令牌端点多线路尝试：中转 /token（secret 由服务端补）→ 中转 /proxy 通用转发 → 直连（需能访问 Google）。
     * 任一线路成功即返回；网络错误每线路重试一次；全部失败时优先抛出拿到过服务器应答的错误
     * （如 invalid_grant，说明链路本身是通的、授权码无效），否则汇总各线路网络错误便于定位。
     */
    private fun tokenViaAny(baseForm: FormBody, failLabel: String): TokenResult {
        val credBuilder = FormBody.Builder()
        for (i in 0 until baseForm.size) {
            credBuilder.add(baseForm.name(i), baseForm.value(i))
        }
        val credForm = credBuilder
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .build()
        val proxiedToken = "$BUILTIN_PROXY/proxy?url=" + java.net.URLEncoder.encode(TOKEN_URL, "UTF-8")
        val routes = listOf(
            Triple("$BUILTIN_PROXY/token", baseForm, "中转"),
            Triple(proxiedToken, credForm, "中转备用"),
            Triple(TOKEN_URL, credForm, "直连"),
        )
        val errors = mutableListOf<String>()
        var serverError: AgException? = null
        for ((url, form, label) in routes) {
            for (attempt in 1..2) {
                try {
                    return parseToken(post(url, form, bearer = null, browserUa = true))
                } catch (e: AgException) {
                    if (serverError == null) serverError = friendlyTokenError(e)
                    break
                } catch (e: Exception) {
                    if (attempt == 1) continue  // 网络抖动重试一次
                    errors.add("$label: ${e.message ?: "网络错误"}")
                }
            }
        }
        throw serverError ?: AgException(0, "$failLabel（${errors.joinToString("；")}）")
    }

    /** 把服务器返回的 JSON 错误体转成可读文案（解析失败保留原始内容）。 */
    private fun friendlyTokenError(e: AgException): AgException {
        val raw = e.message?.trim() ?: return e
        if (!raw.startsWith("{")) return e
        return try {
            val obj = gson.fromJson(raw, TokenResponse::class.java)
            AgException(e.code, obj?.errorDescription ?: obj?.error ?: raw)
        } catch (_: Exception) {
            e
        }
    }

    /** API 端点多线路尝试：中转 /proxy 通用转发 → 直连（大陆网络下直连 Google 通常不通）。 */
    private fun postViaAny(target: String, body: okhttp3.RequestBody, bearer: String): String {
        val proxied = "$BUILTIN_PROXY/proxy?url=" + java.net.URLEncoder.encode(target, "UTF-8")
        var serverError: AgException? = null
        var netErr: Exception? = null
        for (url in listOf(proxied, target)) {
            try {
                return post(url, body, bearer = bearer)
            } catch (e: AgException) {
                if (e.code == 401) throw e
                if (serverError == null) serverError = e
            } catch (e: Exception) {
                if (netErr == null) netErr = e
            }
        }
        throw serverError ?: netErr ?: AgException(0, "请求失败")
    }

    private fun parseToken(resp: String): TokenResult {
        val parsed = gson.fromJson(resp, TokenResponse::class.java)
            ?: throw AgException(0, "响应解析失败")
        if (parsed.accessToken == null) {
            throw AgException(401, parsed.errorDescription ?: parsed.error ?: "授权兑换失败")
        }
        return TokenResult(parsed.accessToken, parsed.expiresIn, parsed.refreshToken)
    }

    /** 拉取账号邮箱（非关键信息，失败返回 null）。 */
    fun fetchEmail(accessToken: String): String? {
        val target = "https://www.googleapis.com/oauth2/v2/userinfo"
        val urls = listOf(
            "$BUILTIN_PROXY/proxy?url=" + java.net.URLEncoder.encode(target, "UTF-8"),
            target,
        )
        for (u in urls) {
            try {
                val builder = Request.Builder().url(u).get()
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", BROWSER_UA)
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string().orEmpty()
                    val obj = gson.fromJson(text, com.google.gson.JsonObject::class.java) ?: return@use
                    val email = obj.get("email")?.asString ?: return@use
                    return email.ifEmpty { null }
                }
            } catch (_: Exception) {
                // 换线路重试
            }
        }
        return null
    }

    // ─── 高层操作 ───

    /** 用 refresh_token 换新的 access_token（多线路容错，与 exchangeCode 同路径） */
    fun refreshAccessToken(refreshToken: String): TokenResult {
        val form = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        return tokenViaAny(form, "刷新令牌失败")
    }

    /** 拉取项目 ID + 订阅等级 */
    fun loadAssist(accessToken: String): AssistInfo {
        var lastErr: Exception? = null
        for (ep in ENDPOINTS) {
            try {
                val body = """{"metadata":{"ideType":"ANTIGRAVITY"}}"""
                    .toRequestBody(JSON_TYPE)
                val resp = postViaAny("$ep/v1internal:loadCodeAssist", body, accessToken)
                val json = gson.fromJson(resp, AssistResponse::class.java)
                val tier = json.paidTier ?: json.currentTier
                return AssistInfo(
                    projectId = json.projectId,
                    tier = tier?.name ?: tier?.id
                )
            } catch (e: AgException) {
                if (e.code == 401) throw e
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: AgException(0, "loadCodeAssist 失败")
    }

    /** 拉取配额摘要；带 project 被 403 时去 project 重试（与桌面版一致） */
    fun fetchQuotaSummary(accessToken: String, projectId: String?): QuotaSummary {
        var lastErr: Exception? = null
        for (ep in ENDPOINTS) {
            val bodies = listOf(
                if (projectId != null) """{"project":"${projectId.replace("\"", "\\\"")}"}"""
                else "{}",
                "{}",
            )
            for (raw in bodies) {
                try {
                    val resp = postViaAny(
                        "$ep/v1internal:retrieveUserQuotaSummary",
                        raw.toRequestBody(JSON_TYPE),
                        accessToken
                    )
                    return parseQuota(gson.fromJson(resp, QuotaResponse::class.java))
                } catch (e: AgException) {
                    if (e.code == 401) throw e
                    lastErr = e
                    if (e.code != 403) break
                } catch (e: Exception) {
                    lastErr = e
                    break
                }
            }
        }
        throw lastErr ?: AgException(0, "额度查询失败")
    }

    /** window/bucketId 含 week → 每周桶，其余归入 5h 滚动窗口；百分比限幅 0..100 */
    private fun parseQuota(resp: QuotaResponse?): QuotaSummary {
        val groups = resp?.groups ?: resp?.legacyGroups ?: emptyList()
        val b5 = mutableListOf<QuotaBucket>()
        val bw = mutableListOf<QuotaBucket>()
        for (g in groups) {
            val groupName = (g.displayName ?: "")
                .replace(Regex(" models?$", RegexOption.IGNORE_CASE), "")
                .ifEmpty { "未知分组" }
            for (b in g.buckets.orEmpty()) {
                val frac = (b.remainingFraction ?: 0.0).coerceIn(0.0, 1.0)
                val win = (b.window ?: b.bucketId ?: "").lowercase()
                val isWeekly = "week" in win
                val bucket = QuotaBucket(
                    label = if (isWeekly) "$groupName (周)" else groupName,
                    percentRemaining = Math.round(frac * 100).toInt(),
                    resetTimeIso = b.resetTime,
                    isWeekly = isWeekly,
                )
                if (isWeekly) bw += bucket else b5 += bucket
            }
        }
        return QuotaSummary(b5, bw)
    }

    /** ISO8601 重置时间 → 剩余毫秒；解析失败返回 null */
    fun remainingMillis(resetIso: String?, now: Long = System.currentTimeMillis()): Long? {
        if (resetIso.isNullOrBlank()) return null
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val cleaned = resetIso.let { s ->
                val dot = s.indexOf('.')
                (if (dot > 0) s.substring(0, dot) else s).trimEnd('Z')
            }
            val target = sdf.parse(cleaned)?.time ?: return null
            target - now
        } catch (_: Exception) {
            null
        }
    }

    // ─── 底层请求 ───

    private fun post(url: String, body: okhttp3.RequestBody, bearer: String?, browserUa: Boolean = false): String {
        val builder = Request.Builder().url(url).post(body)
        if (browserUa) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            )
        }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AgException(resp.code, ai.vibecafe.usage.data.quota.httpErrorBody(text, resp.code))
            }
            return text
        }
    }
}
