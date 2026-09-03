package ai.vibecafe.usage.data.quota

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * 「额度」平台的一键授权（OAuth loopback + PKCE）。
 *
 * 流程与各家官方 CLI 完全一致：在本机起一个临时 HTTP 服务（127.0.0.1），打开浏览器到
 * 官方授权页；用户确认后浏览器重定向到 localhost，由本服务捕获授权码并兑换令牌。
 * 令牌兑换走内置 Cloudflare Worker 中转（auth.openai.com / platform.claude.com 大陆不可达），
 * 失败自动回退直连。
 *
 * - Codex（openai/codex CLI 同款）：端口 1455、/auth/callback、auth.openai.com
 * - Claude Code（Claude Code CLI 同款）：端口 54545、/callback、claude.com → platform.claude.com
 * - Antigravity（Antigravity.Tools 同款）：端口 51121、/callback、accounts.google.com
 */
object OAuthFlow {

    internal val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    /** PKCE code_verifier（32 字节 base64url）。 */
    fun newVerifier(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** CSRF state（同 verifier 生成方式）。 */
    fun newState(): String = newVerifier()

    /** S256 code_challenge。 */
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    internal fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** 用系统浏览器打开授权页。 */
    fun openBrowser(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * loopback 回调服务器：绑定 127.0.0.1:[port]，等待浏览器重定向。
     * 逐连接处理；非回调路径（预连/探测）直接回 404 并继续等，收到 code 或 error 才返回。
     * 等待 15 分钟；同端口重复发起时自动接管（旧等待流程随即取消）。
     */
    class LoopbackServer(private val port: Int, private val path: String) {

        data class Callback(val code: String?, val error: String?, val state: String?)

        private var server: ServerSocket? = null

        suspend fun await(): Callback = withContext(Dispatchers.IO) {
            // 接管同端口旧实例：重复发起授权时旧等待流程让位，避免绑定冲突
            liveSockets.remove(port)?.let { old ->
                try { old.close() } catch (_: Exception) { }
            }
            val ss = ServerSocket()
            ss.reuseAddress = true
            try {
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            } catch (e: Exception) {
                throw QuotaException(
                    0,
                    if (e is java.net.BindException) "本地回调端口被占用，请稍等片刻后重新发起授权"
                    else "回调服务器启动失败：${e.message?.take(80) ?: "未知错误"}"
                )
            }
            server = ss
            liveSockets[port] = ss
            ss.soTimeout = 15 * 60_000
            try {
                while (true) {
                    val sock = try {
                        ss.accept()
                    } catch (e: java.net.SocketTimeoutException) {
                        throw QuotaException(0, "授权等待超时，请回到应用重新点击「一键授权」")
                    } catch (e: java.net.SocketException) {
                        throw QuotaException(0, "已发起新的授权，本次等待已取消")
                    }
                    val cb = handle(sock)
                    if (cb.code != null || cb.error != null) return@withContext cb
                }
            } finally {
                stop()
            }
            throw IllegalStateException("unreachable")
        }

        private fun handle(sock: Socket): Callback = try {
            sock.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: ""
            while (reader.readLine()?.isNotEmpty() == true) { /* 读掉头部 */ }
            val target = requestLine.split(" ").getOrNull(1) ?: ""
            val params: Map<String, String> = if (target.startsWith(path)) {
                target.substringAfter('?', "").split("&").mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
                }.toMap()
            } else emptyMap()
            val body = if (target.startsWith(path)) SUCCESS_HTML else NOT_FOUND_HTML
            sock.getOutputStream().write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                    .toByteArray()
            )
            sock.getOutputStream().flush()
            Callback(params["code"], params["error"], params["state"])
        } catch (_: Exception) {
            Callback(null, null, null)
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }

        fun stop() {
            val ss = server
            server = null
            if (ss != null) liveSockets.remove(port, ss)
            try { ss?.close() } catch (_: Exception) { }
        }

        private companion object {
            /** 各端口当前存活的服务器：重复发起授权时接管端口用 */
            val liveSockets = java.util.concurrent.ConcurrentHashMap<Int, ServerSocket>()

            val SUCCESS_HTML = """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="background:#141218;color:#E6E0E9;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center"><h1>&#10003;</h1><p>授权成功，请返回 VibeUsage</p></div></body></html>"""
            const val NOT_FOUND_HTML = "<html><body>Not Found</body></html>"
        }
    }
}

// ─── Codex（ChatGPT 计划）一键授权 ───

object CodexOAuth {

    data class Tokens(val accessToken: String, val accountId: String?, val refreshToken: String?)

    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val REDIRECT = "http://localhost:1455/auth/callback"

    fun authorizeUrl(challenge: String, state: String): String =
        "https://auth.openai.com/oauth/authorize" +
            "?response_type=code&client_id=$CLIENT_ID&redirect_uri=${OAuthFlow.enc(REDIRECT)}" +
            "&scope=${OAuthFlow.enc("openid profile email offline_access api.connectors.read api.connectors.invoke")}" +
            "&code_challenge=$challenge&code_challenge_method=S256&state=${OAuthFlow.enc(state)}" +
            "&id_token_add_organizations=true&codex_cli_simplified_flow=true&originator=codex_cli_rs"

    /** 浏览器授权 → loopback 捕获 → 兑换令牌。 */
    suspend fun login(context: Context): Tokens = coroutineScope {
        val verifier = OAuthFlow.newVerifier()
        val state = OAuthFlow.newState()
        val server = withContext(Dispatchers.IO) { OAuthFlow.LoopbackServer(1455, "/auth/callback") }
        try {
            val pending = async { server.await() }
            OAuthFlow.openBrowser(context, authorizeUrl(OAuthFlow.challenge(verifier), state))
            val cb = pending.await()
            checkCallback(cb, state)
            exchange(cb.code!!, verifier)
        } finally {
            server.stop()
        }
    }

    private suspend fun exchange(code: String, verifier: String): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val resp = QuotaHttp.post(
            "https://auth.openai.com/oauth/token", form,
            bearer = null, proxyFirst = true, ua = "codex_cli_rs"
        )
        val obj = JSONObject(resp)
        val access = obj.optString("access_token", "")
        if (access.isEmpty()) {
            throw QuotaException(
                401,
                obj.optString("error_description", "").ifEmpty { obj.optString("error", "授权兑换失败") }
            )
        }
        Tokens(
            accessToken = access,
            accountId = accountIdFromIdToken(obj.optString("id_token", "")),
            refreshToken = obj.optString("refresh_token", "").ifEmpty { null }
        )
    }

    /** ChatGPT-Account-Id 藏在 id_token JWT 的 chatgpt_account_id 声明里。 */
    private fun accountIdFromIdToken(idToken: String): String? = try {
        val payload = String(
            Base64.getUrlDecoder().decode(idToken.split(".")[1]),
            Charsets.UTF_8
        )
        val obj = JSONObject(payload)
        val direct = obj.optString("chatgpt_account_id", "")
        val nested = obj.optJSONObject("https://api.openai.com/auth")
            ?.optString("chatgpt_account_id", "") ?: ""
        direct.ifEmpty { nested }.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

// ─── Claude Code 一键授权 ───

object ClaudeOAuth {

    data class Tokens(val accessToken: String, val refreshToken: String?)

    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val REDIRECT = "http://localhost:54545/callback"
    private val SCOPES = listOf(
        "org:create_api_key", "user:profile", "user:inference",
        "user:sessions:claude_code", "user:mcp_servers", "user:file_upload"
    ).joinToString(" ")

    fun authorizeUrl(challenge: String, state: String): String =
        "https://claude.com/cai/oauth/authorize" +
            "?code=true&client_id=$CLIENT_ID&response_type=code&redirect_uri=${OAuthFlow.enc(REDIRECT)}" +
            "&scope=${OAuthFlow.enc(SCOPES)}&code_challenge=$challenge&code_challenge_method=S256" +
            "&state=${OAuthFlow.enc(state)}"

    suspend fun login(context: Context): Tokens = coroutineScope {
        val verifier = OAuthFlow.newVerifier()
        val state = OAuthFlow.newState()
        val server = withContext(Dispatchers.IO) { OAuthFlow.LoopbackServer(54545, "/callback") }
        try {
            val pending = async { server.await() }
            OAuthFlow.openBrowser(context, authorizeUrl(OAuthFlow.challenge(verifier), state))
            val cb = pending.await()
            checkCallback(cb, state)
            exchange(cb.code!!, state, verifier)
        } finally {
            server.stop()
        }
    }

    private suspend fun exchange(code: String, state: String, verifier: String): Tokens =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", REDIRECT)
                put("client_id", CLIENT_ID)
                put("code_verifier", verifier)
                put("state", state)
            }.toString().toRequestBody(OAuthFlow.JSON_TYPE)
            val resp = QuotaHttp.post(
                "https://platform.claude.com/v1/oauth/token", body,
                bearer = null, proxyFirst = true, ua = "claude-cli"
            )
            val obj = JSONObject(resp)
            val access = obj.optString("access_token", "")
            if (access.isEmpty()) {
                throw QuotaException(
                    401,
                    obj.optString("error_description", "").ifEmpty { obj.optString("error", "授权兑换失败") }
                )
            }
            if (!obj.optString("scope", "").contains("user:inference")) {
                throw QuotaException(403, "该账号没有 Claude Pro/Max 订阅")
            }
            Tokens(access, obj.optString("refresh_token", "").ifEmpty { null })
        }
}

// ─── Antigravity（Google）一键授权 ───

object AgGoogleOAuth {

    data class Login(val refreshToken: String, val accessToken: String, val email: String?)

    private const val PORT = 51121
    private const val PATH = "/callback"

    suspend fun login(context: Context): Login = coroutineScope {
        val verifier = OAuthFlow.newVerifier()
        val state = OAuthFlow.newState()
        val server = withContext(Dispatchers.IO) { OAuthFlow.LoopbackServer(PORT, PATH) }
        try {
            val pending = async { server.await() }
            OAuthFlow.openBrowser(
                context,
                ai.vibecafe.usage.data.ag.AgQuotaApi.buildAuthorizeUrl(
                    OAuthFlow.challenge(verifier), state
                )
            )
            val cb = pending.await()
            checkCallback(cb, state)
            val tokens = withContext(Dispatchers.IO) {
                ai.vibecafe.usage.data.ag.AgQuotaApi.exchangeCode(cb.code!!, verifier)
            }
            val refresh = tokens.refreshToken
                ?: throw QuotaException(0, "未返回 refresh_token，请在授权时确保勾选访问权限后重试")
            val email = withContext(Dispatchers.IO) {
                ai.vibecafe.usage.data.ag.AgQuotaApi.fetchEmail(tokens.accessToken)
            }
            Login(refresh, tokens.accessToken, email)
        } finally {
            server.stop()
        }
    }
}

/** 回调统一校验：有 error 报取消，缺 code/状态不符一律拒绝。 */
private fun checkCallback(cb: OAuthFlow.LoopbackServer.Callback, expectedState: String) {
    if (cb.error != null) throw QuotaException(0, "授权取消：${cb.error}")
    if (cb.code == null) throw QuotaException(0, "未收到授权码，请重试")
    if (cb.state != expectedState) throw QuotaException(0, "state 校验失败，已拒绝回调")
}
