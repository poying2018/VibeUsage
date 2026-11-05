package ai.vibecafe.usage.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * RetryInterceptor：重试策略不能只靠「看起来对」。
 *
 * 这里用假的 [Interceptor.Chain] 按顺序喂「异常 / 响应」，直接验证
 * 到底重试了几次、什么时候必须停手、`Retry-After` 有没有被采纳。
 * 退避基准设成 0，用例本身不睡。
 */
class RetryInterceptorTest {

    private val request = Request.Builder().url("https://example.test/x").build()

    /** 按脚本返回结果的 chain：元素是 Response 就返回，是 Throwable 就抛。 */
    private class ScriptedChain(
        private val request: Request,
        private val steps: List<Any>
    ) : Interceptor.Chain {
        var proceedCount = 0
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            val step = steps.getOrElse(proceedCount) { steps.last() }
            proceedCount++
            if (step is Throwable) throw step
            return step as Response
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException("call not used in test")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun response(code: Int, retryAfter: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("code $code")
            // 真实响应都带 body；不带 body 的 Response 上 close() 会抛 IllegalStateException
            .body(okhttp3.ResponseBody.create(null, ""))
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()

    private fun run(steps: List<Any>, maxRetries: Int = 3): Pair<Response?, Int> {
        val chain = ScriptedChain(request, steps)
        val result = runCatching {
            RetryInterceptor(maxRetries = maxRetries, baseDelayMs = 0L).intercept(chain)
        }
        return result.getOrNull() to chain.proceedCount
    }

    @Test
    fun `transient 5xx is retried then succeeds`() {
        val (resp, calls) = run(listOf(response(503), response(200)))
        assertEquals(2, calls)
        assertEquals(200, resp?.code)
        resp?.close()
    }

    @Test
    fun `4xx other than 429 is not retried`() {
        val (resp, calls) = run(listOf(response(401), response(200)))
        assertEquals("鉴权失败重试没有意义，只会让用户多等", 1, calls)
        assertEquals(401, resp?.code)
        resp?.close()
    }

    @Test
    fun `429 is retried`() {
        val (resp, calls) = run(listOf(response(429), response(200)))
        assertEquals(2, calls)
        assertEquals(200, resp?.code)
        resp?.close()
    }

    @Test
    fun `io exception is retried and the last exception surfaces when no response arrives`() {
        val (resp, calls) = run(listOf(SocketTimeoutException("timeout"), IOException("boom")))
        assertNull(resp)
        assertEquals("默认 3 次重试 → 最多 4 次尝试", 4, calls)
    }

    @Test
    fun `post is never replayed`() {
        val post = request.newBuilder().post(okhttp3.RequestBody.create(null, "x")).build()
        val chain = ScriptedChain(post, listOf(response(503), response(200)))
        val result = runCatching {
            RetryInterceptor(maxRetries = 3, baseDelayMs = 0L).intercept(chain)
        }
        assertEquals("非幂等请求重放会产生重复写入", 1, chain.proceedCount)
        assertEquals(503, result.getOrNull()?.code)
        result.getOrNull()?.close()
    }

    @Test
    fun `retry-after seconds are parsed`() {
        val interceptor = RetryInterceptor(baseDelayMs = 0L)
        assertEquals(120_000L, interceptor.retryAfterMillis(response(429, "120")))
        assertNull("0 秒等同于没给，应回落到指数退避", interceptor.retryAfterMillis(response(429, "0")))
        assertNull(interceptor.retryAfterMillis(response(429, "soon")))
        assertNull(interceptor.retryAfterMillis(response(429)))
    }

    @Test
    fun `retry-after http date in the past falls back to exponential backoff`() {
        val interceptor = RetryInterceptor(baseDelayMs = 0L)
        val past = "Wed, 21 Oct 2015 07:28:00 GMT"
        assertNull(interceptor.retryAfterMillis(response(429, past)))
    }

    @Test
    fun `retry-after http date in the future is honoured`() {
        val interceptor = RetryInterceptor(baseDelayMs = 0L)
        val future = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5))
        val ms = interceptor.retryAfterMillis(response(429, future))
        assertNotNull(ms)
        // 5 分钟后的时刻换算出的等待应落在 4~6 分钟（避免分秒边界抖动）
        assertTrue("解析出的等待 ${ms}ms 不在预期区间", ms!! in 240_000L..360_000L)
    }
}
