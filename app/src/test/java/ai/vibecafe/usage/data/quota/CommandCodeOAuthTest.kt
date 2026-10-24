package ai.vibecafe.usage.data.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * CommandCode 网页授权的 URL 与回调解析。
 *
 * 官方 CLI 的登录流程（command-code CLI）：浏览器打开
 * `{studio}/studio/auth/cli?callback=...&state=...&mode=redirect`，
 * 登录后服务端 302 回本地回调地址，并直接在 query 里带上
 * `apiKey` / `state` / `userId` / `userName`。
 */
class CommandCodeOAuthTest {

    @Test
    fun `authorize url points at studio cli auth with encoded callback`() {
        val url = CommandCodeOAuth.buildAuthorizeUrl(51125, "state-token-123")
        assertTrue("应指向 Studio 的 CLI 授权页：$url", url.startsWith("https://commandcode.ai/studio/auth/cli?"))
        assertTrue("应带 mode=redirect", url.contains("&mode=redirect"))
        assertTrue("应带 state", url.contains("state="))

        // callback 是 URL 编码后的 127.0.0.1 地址
        val cb = URLDecoder.decode(
            url.substringAfter("callback=").substringBefore("&"),
            "UTF-8"
        )
        assertEquals("http://127.0.0.1:51125/callback", cb)

        val state = URLDecoder.decode(
            url.substringAfter("state=").substringBefore("&"),
            "UTF-8"
        )
        assertEquals("state-token-123", state)
    }

    @Test
    fun `authorize url survives special characters in state`() {
        val url = CommandCodeOAuth.buildAuthorizeUrl(51125, "a+b/c=d&e")
        // 特殊字符必须被编码，不能把 query 结构弄坏
        assertTrue("state 含 & 时不能出现裸 & 分隔：$url", url.contains("state=a%2Bb%2Fc%3Dd%26e"))
    }

    @Test
    fun `callback state must match the one we issued`() {
        val cb = OAuthFlow.LoopbackServer.Callback(
            apiKey = "user_abc",
            state = "issued-state",
            userId = "u_1",
            userName = "someone"
        )
        // 一致 → 通过
        CommandCodeOAuth.validateState(cb, "issued-state")

        // 不一致 → 拒绝
        try {
            CommandCodeOAuth.validateState(cb, "other-state")
            throw AssertionError("state 不一致时必须拒绝")
        } catch (e: ExtraQuotaApi.QuotaException) {
            assertTrue("错误文案应提示重新发起：${e.message}", e.message!!.contains("state"))
        }

        // 缺失 → 同样拒绝（否则能绕过 CSRF 校验）
        try {
            CommandCodeOAuth.validateState(OAuthFlow.LoopbackServer.Callback(apiKey = "user_abc"), "issued-state")
            throw AssertionError("state 缺失时必须拒绝")
        } catch (e: ExtraQuotaApi.QuotaException) {
            assertTrue(e.message!!.contains("state"))
        }
    }
}