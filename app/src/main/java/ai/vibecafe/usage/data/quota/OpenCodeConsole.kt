package ai.vibecafe.usage.data.quota

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

// ─── OpenCode Console 登录通道（设备码 OAuth）───
//
// 场景：用户不想粘贴 oc_sk_ Key（或 Key 权限不足）时，用浏览器输几个字符
// 即可接好。这条通道走 Console 的设备码授权，拿到 OAuth 令牌后直接读
// `/api/go/status` —— 与 Key 路径读到的是同一份美元数额。
//
// 为什么用设备码而不是回调用 OAuth（loopback）：设备码页只需要输一个短码，
// 不需要本机监听端口；手机、平板、PC 浏览器都能输，失败面更小。
//
// 关键点是 `supports_org_scope=true`：不带它，令牌没有 org 上下文，
// `/api/go/status` 会 400 `OrgRequired`（"x-org-id is required"）；
// 带上后令牌里就带 org，刚才（2026-11-06）实测直接 200，不用另传头。
// 所以发起时必须带这个开关；注册端点本身只认两种 grant，
// 设备码不走注册声明的授权类型，这是服务端行为（注册接口注明了支持项）。
//
// 凭据存进既有的 opencode_key 槽位，用 [PREFIX] 前缀与 oc_sk_ Key 区分：
// 一条槽位同时承载「登录态」和「粘贴 Key」两种接入方式，prefs 结构与解绑逻辑都不用改。
object OpenCodeConsole {

    private const val CONSOLE = "https://console.opencode.ai"
    private const val WEB = "https://opencode.ai"
    private const val UA = "VibeUsage/2.23"

    /** 需要的权限：只要读用量，不碰账务 / 成员管理。 */
    private const val SCOPE = "usage:read"

    /**
     * 动态注册失败时的兜底 client_id（官方公开客户端，不含秘密）。
     * 正常路径每次登录都会现注册一个专用客户端，兜底只是第二条路。
     */
    private const val FALLBACK_CLIENT_ID = "opencode"

    /** 注册时声明的回调地址。设备码流程不会真的跳回来，只是注册接口必填。 */
    private const val REDIRECT_URI = "http://127.0.0.1:53682/callback"

    /** 存进 prefs 的登录凭据前缀（`oc_console:{json}`）。 */
    const val PREFIX = "oc_console:"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 设备码换来的令牌包。
     *
     * [expiresAt] 是本地估算的绝对毫秒（服务端只给 expires_in 秒）。
     * [clientId] 是发起时的客户端（用于刷新时回传）；裸的 `opencode` 也一并存，
     * 刷新时服务器会校验 client 与令牌归属。
     */
    data class Tokens(
        val access: String,
        val refresh: String?,
        val expiresAt: Long,
        val clientId: String
    )

    /** 设备码发起结果。 */
    data class DeviceCode(
        val clientId: String,
        val deviceCode: String,
        val userCode: String,
        /** 浏览器要打开的完整授权页（已带 user_code）。 */
        val verifyUrl: String,
        val expiresInSec: Int,
        val intervalSec: Int
    )

    fun isConsoleCred(cred: String): Boolean = cred.trim().startsWith(PREFIX)

    /** 令牌包 → 存盘字符串。 */
    internal fun encode(t: Tokens): String = PREFIX + gson.toJson(JsonObject().apply {
        addProperty("a", t.access)
        t.refresh?.let { addProperty("r", it) }
        addProperty("e", t.expiresAt)
        addProperty("c", t.clientId)
    })

    /**
     * 存盘字符串 → 令牌包；不是登录态或格式不对返回 null。
     *
     * 解析失败不能抛：旧的 oc_sk_ Key 也走同一个槽位，把一张坏掉的 JSON
     * 当成 Key 用只会得到一个难懂的 401。
     */
    internal fun decode(cred: String): Tokens? {
        val raw = cred.trim()
        if (!raw.startsWith(PREFIX)) return null
        val obj = runCatching {
            gson.fromJson(raw.removePrefix(PREFIX), JsonObject::class.java)
        }.getOrNull() ?: return null
        val access = jstr(obj, "a")?.takeIf { it.isNotBlank() } ?: return null
        return Tokens(
            access = access,
            refresh = jstr(obj, "r")?.takeIf { it.isNotBlank() },
            expiresAt = jnum(obj, "e")?.toLong() ?: 0L,
            clientId = jstr(obj, "c")?.takeIf { it.isNotBlank() } ?: FALLBACK_CLIENT_ID
        )
    }

    // ─── 设备码授权 ───

    /**
     * 发起设备码授权。
     *
     * 先尝试动态注册一个专用客户端，注册失败就用兜底 ID —— 两条路的设备码接口
     * 实测都返回 200，哪条先通就用哪条。`supports_org_scope=true` 必须带，
     * 否则令牌没有 org 上下文，后面读额度会 400。
     */
    fun startDeviceCode(): DeviceCode {
        // 注册的客户端被服务端拒绝时退到官方公开 ID（多半是客户端尚未被允许 device）
        val registered = registerClient()
        val tried = registered?.let { startDevice(it) }
        if (tried != null) return tried
        return startDevice(FALLBACK_CLIENT_ID)
            ?: throw QuotaException(0, "发起 OpenCode 授权失败，请检查网络后重试")
    }

    /** 任一步失败（网络/响应异常/缺字段）都返回 null，让调用方退到兜底 client 再试一次 —
     *  只包住发请求的话，注册客户端返回坏响应时会直接抛错，连第二条路都不走。 */
    private fun startDevice(clientId: String): DeviceCode? = runCatching {
        val body = JsonObject().apply {
            addProperty("client_id", clientId)
            addProperty("scope", SCOPE)
            addProperty("supports_org_scope", true)
        }
        val resp = postJson("$CONSOLE/auth/device/code", body)
        val obj = jsonOf(resp, "授权接口返回异常")
        val deviceCode = jstr(obj, "device_code")
            ?: throw QuotaException(0, "授权接口未返回 device_code")
        val userCode = jstr(obj, "user_code")
            ?: throw QuotaException(0, "授权接口未返回 user_code")
        val verify = jstr(obj, "verification_uri_complete")?.let { absolute(it) }
            ?: absolute(jstr(obj, "verification_uri") ?: "/console/device").let {
                "$it?user_code=${OAuthFlow.enc(userCode)}&client_id=${OAuthFlow.enc(clientId)}"
            }
        DeviceCode(
            clientId = clientId,
            deviceCode = deviceCode,
            userCode = userCode,
            verifyUrl = verify,
            expiresInSec = (jnum(obj, "expires_in") ?: 600.0).toInt(),
            intervalSec = (jnum(obj, "interval") ?: 5.0).toInt().coerceAtLeast(3)
        )
    }.getOrNull()

    /** `verification_uri` 是站内相对路径（`/console/device`），补成完整 URL。 */
    private fun absolute(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else WEB + path

    /**
     * 轮询一次设备码。
     *
     * 返回 null 表示「还等着用户在浏览器里确认」（authorization_pending / slow_down）。
     * 其余错误都是终止性的：拒绝、过期、client 无效……继续轮询只会白等到超时。
     *
     * 走 QuotaHttp（中转优先、直连兜底）而不是自写请求：模拟器上自写直连会撞 IPv6
     * 排序问题（failed to connect .../2606:...），而中转通道已在 Key 路径上实测可用。
     * 代价是 400 时只能看到展示文案：`httpErrorBody` 优先取 `error_description`，
     * pending 的描述里含 "pending"，据此区分「没好」与终止错误。网络异常
     *（IOException）不包装成 QuotaException，交给 ViewModel 当抖动继续等。
     */
    fun pollToken(dc: DeviceCode): Tokens? {
        val body = JsonObject().apply {
            addProperty("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            addProperty("device_code", dc.deviceCode)
            addProperty("client_id", dc.clientId)
        }
        val resp = try {
            postJson("$CONSOLE/auth/device/token", body)
        } catch (e: QuotaException) {
            if (e.code != 400) throw e
            val msg = (e.message ?: "").lowercase()
            return when {
                "pending" in msg || "slow_down" in msg || "slow down" in msg -> null
                "denied" in msg || "refused" in msg || "declined" in msg ->
                    throw QuotaException(0, "已拒绝授权")
                "expired" in msg ->
                    throw QuotaException(0, "授权已过期，请重新发起")
                else -> throw QuotaException(0, "授权失败：${e.message ?: "未知错误"}")
            }
        }
        val obj = jsonOf(resp, "授权接口返回异常")
        val access = jstr(obj, "access_token")
            ?: throw QuotaException(0, "授权接口未返回 access_token")
        val expiresIn = (jnum(obj, "expires_in") ?: 3600.0).toLong()
        return Tokens(
            access = access,
            refresh = jstr(obj, "refresh_token"),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            clientId = dc.clientId
        )
    }

    /**
     * 用 refresh_token 换新 access_token。
     *
     * 服务端可能同时轮换 refresh_token，所以返回值要整体写回 prefs（见 ViewModel）。
     * 刷新失败返回 null：既可能是网络抖动，也可能是授权被撤销 —— 不在这里下结论，
     * 交给后面用旧 token 的实际调用给出 401 提示。
     */
    fun refresh(t: Tokens): Tokens? {
        val rt = t.refresh ?: return null
        val form = okhttp3.FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .add("client_id", t.clientId)
            .build()
        val obj = runCatching {
            jsonOf(QuotaHttp.post("$CONSOLE/auth/oauth/token", form, null, proxyFirst = true, ua = UA), "")
        }.getOrNull() ?: return null
        val access = jstr(obj, "access_token") ?: return null
        val expiresIn = (jnum(obj, "expires_in") ?: 3600.0).toLong()
        return Tokens(
            access = access,
            // 没下发新的 refresh_token 就沿用旧的（不是所有实现都会轮换）
            refresh = jstr(obj, "refresh_token") ?: rt,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            clientId = t.clientId
        )
    }

    /**
     * 动态注册一个公共客户端；失败返回 null（用兜底 ID）。
     *
     * `grant_types` 只能填注册器认识的那两个（实测带上 device_code 会 400），
     * 但注册出来的客户端照样能打 `/auth/device/code` —— 设备码不走注册声明的授权类型。
     */
    private fun registerClient(): String? = runCatching {
        val body = JsonObject().apply {
            addProperty("client_name", "VibeUsage")
            add("redirect_uris", JsonArray().apply { add(REDIRECT_URI) })
            add("grant_types", JsonArray().apply {
                add("authorization_code")
                add("refresh_token")
            })
            add("response_types", JsonArray().apply { add("code") })
            addProperty("token_endpoint_auth_method", "none")
        }
        jstr(jsonOf(postJson("$CONSOLE/auth/oauth/register", body), ""), "client_id")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ─── 额度查询 ───

    /**
     * 用登录态查额度，返回「用量 + 可能轮换后的新凭据」。
     *
     * [cred] 是 [PREFIX] 开头的存盘字符串。access_token 快过期时先刷新；
     * 刷新成功就把新令牌包一并返回，由调用方写回 prefs —— 不写回的话下次启动
     * 又拿着已被轮换掉的 refresh_token 去换。
     * 刷新成功那一刻就通过 [onRenew] 立即回写（不等后面的额度查询成功）：服务端
     * 已经轮换了 refresh_token，旧值作废；若查询紧接着失败还不写回，下次只能重新授权。
     */
    fun fetchUsage(cred: String, onRenew: (String) -> Unit = {}): Pair<Usage, String?> {
        var tokens = decode(cred) ?: throw QuotaException(0, "登录凭据格式不对，请重新授权")
        var renewed: String? = null
        if (tokens.expiresAt in 1 until System.currentTimeMillis() + 60_000) {
            refresh(tokens)?.let {
                tokens = it
                renewed = encode(it)
                onRenew(renewed!!)
            }
        }
        val status = try {
            fetchStatus(tokens.access)
        } catch (e: QuotaException) {
            // 401 可能是 access 已过期但 expiresAt 还没到（时钟漂移 / 令牌被提前撤销）：
            // 还没刷新过的话先刷新再试一次，仍不行就是真失效，提示重新授权
            if (e.code == 401 && renewed == null) {
                refresh(tokens)?.let {
                    tokens = it
                    renewed = encode(it)
                    onRenew(renewed!!)
                    return buildUsage(fetchStatus(tokens.access)) to renewed
                }
            }
            if (e.code == 401) throw QuotaException(401, "登录已失效，请重新一键授权")
            throw e
        }
        return buildUsage(status) to renewed
    }

    /** 授权刚完成时校验一次：登录态查不到 Go 额度就别存凭据。 */
    fun verify(tokens: Tokens): JsonObject = try {
        fetchStatus(tokens.access)
    } catch (e: QuotaException) {
        if (e.code == 401) {
            throw QuotaException(0, "授权成功但 Console 拒绝了这个令牌，请改用 API Key 接入")
        }
        throw e
    }

    private fun fetchStatus(access: String): JsonObject = jsonOf(
        QuotaHttp.get("$CONSOLE/api/go/status?fresh=true", bearer = access, proxyFirst = true, ua = UA),
        "Console 返回了空响应"
    )

    /**
     * `/api/go/status` → 额度（纯函数，便于单测）。
     *
     * 与 Key 路径共用 [OpenCode.parseMeters] 的口径：三档美元数额都来自同一份
     * `access.meters`，只是先做一次「有令牌嫌刷新」的例外处理。
     */
    internal fun buildUsage(status: JsonObject): Usage {
        val meters = OpenCode.parseMeters(status)
        val groups = OpenCode.buildGroupsFromMeters(meters)
        if (groups.isEmpty()) throw QuotaException(0, "Console 未返回额度窗口")
        return Usage(OpenCode.accountOf(status), groups)
    }

    // ─── HTTP 小工具 ───

    private fun postJson(url: String, body: JsonObject): String = QuotaHttp.post(
        url,
        body.toString().toRequestBody(JSON_TYPE),
        bearer = null,
        proxyFirst = true,
        ua = UA
    )

    private fun jsonOf(resp: String, emptyMessage: String): JsonObject {
        if (resp.isBlank()) throw QuotaException(0, emptyMessage.ifEmpty { "Console 返回了空响应" })
        return runCatching { gson.fromJson(resp, JsonObject::class.java) }.getOrNull()
            ?: throw QuotaException(0, "Console 返回异常")
    }
}
