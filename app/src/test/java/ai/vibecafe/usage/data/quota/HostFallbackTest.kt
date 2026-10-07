package ai.vibecafe.usage.data.quota

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * withHostFallback —— 多 host 顺序兜底的通用契约。
 *
 * 这段逻辑原先内联在各家的 fetchUsage 里，没法单测：它既要真发网络请求，
 * 又要判断「还剩多少时间预算」和「哪个错误更值得报给用户」。抽出来后注入 [now] 与
 * [fetch]，预算分支就能确定性覆盖，不必真的 sleep，也不必联网。
 */
class HostFallbackTest {

    private val hosts = listOf("https://cn-a", "https://cn-b", "https://intl-a", "https://intl-b")

    @Test
    fun `first host success short-circuits the remaining hosts`() {
        val tried = mutableListOf<String>()
        val r = withHostFallback<String>(hosts, budgetMs = 20_000, now = { 0L }) { h ->
            tried += h
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(listOf("https://cn-a"), tried)
    }

    @Test
    fun `failing hosts are walked in order and a later region can still succeed`() {
        val tried = mutableListOf<String>()
        val r = withHostFallback<String>(hosts, budgetMs = 60_000, now = { 0L }) { h ->
            tried += h
            if (h.startsWith("https://intl-a")) "intl ok" else throw QuotaException(2049, "invalid api key")
        }
        assertEquals("intl ok", r)
        // 国内两台 + 国际第一台，共 3 次，顺序不能乱
        assertEquals(listOf("https://cn-a", "https://cn-b", "https://intl-a"), tried)
    }

    @Test
    fun `budget exhaustion stops before launching another host`() {
        var clock = 0L
        val tried = mutableListOf<String>()
        val e = runCatching {
            withHostFallback<String>(hosts, budgetMs = 20_000, now = { clock }) { h ->
                tried += h
                clock += 15_000 // 单台耗时 15s：第 1 台跑完还在预算内，第 2 台跑完就超了
                throw QuotaException(500, "boom")
            }
        }.exceptionOrNull()

        assertEquals(listOf("https://cn-a", "https://cn-b"), tried)
        assertTrue("应抛 QuotaException，实际 $e", e is QuotaException)
        assertEquals(500, (e as QuotaException).code)
    }

    @Test
    fun `auth error outranks a later generic server error`() {
        val e = runCatching {
            withHostFallback<String>(hosts, budgetMs = 60_000, now = { 0L }) { h ->
                when (h) {
                    "https://cn-a" -> throw QuotaException(500, "server error")
                    "https://cn-b" -> throw QuotaException(2049, "invalid api key")
                    else -> throw QuotaException(500, "another 500")
                }
            }
        }.exceptionOrNull()
        // 整轮失败时，用户看到「你的 key 不对」比看到「服务器 500」有用得多
        assertTrue("应抛 QuotaException，实际 $e", e is QuotaException)
        assertEquals(2049, (e as QuotaException).code)
        assertEquals("invalid api key", e.message)
    }

    @Test
    fun `without an auth error the last failure is reported`() {
        val e = runCatching {
            withHostFallback<String>(hosts, budgetMs = 60_000, now = { 0L }) { h ->
                throw QuotaException(if (h == "https://cn-a") 500 else 503, "e-$h")
            }
        }.exceptionOrNull()
        assertEquals(503, (e as QuotaException).code)
    }

    @Test
    fun `network exceptions count as failures and do not break the chain`() {
        val tried = mutableListOf<String>()
        val r = withHostFallback<String>(hosts, budgetMs = 60_000, now = { 0L }) { h ->
            tried += h
            if (tried.size < 3) throw IOException("connect timed out")
            "recovered"
        }
        assertEquals("recovered", r)
        assertEquals(3, tried.size)
    }

    @Test
    fun `empty host list surfaces the caller's own message`() {
        val e = runCatching {
            withHostFallback<String>(
                hosts = emptyList(),
                budgetMs = 1_000,
                emptyMessage = "MiniMax 查询失败",
                now = { 0L },
            ) { "never" }
        }.exceptionOrNull()
        assertTrue("应抛 QuotaException，实际 $e", e is QuotaException)
        assertEquals("MiniMax 查询失败", e!!.message)
    }

    @Test
    fun `caller can redefine which codes count as auth errors`() {
        val e = runCatching {
            withHostFallback<String>(
                hosts = listOf("a", "b"),
                budgetMs = 60_000,
                isAuthError = { it == 999 },
                now = { 0L },
            ) { h -> throw QuotaException(if (h == "a") 500 else 999, "custom") }
        }.exceptionOrNull()
        // 500 不再算鉴权错误，于是抛最后那个 999
        assertEquals(999, (e as QuotaException).code)
    }
}