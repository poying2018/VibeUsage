package ai.vibecafe.usage.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 指数退避重试拦截器：处理移动端弱网突发超时、DNS 阶段失败和 5xx 错误。
 * 默认最多重试 3 次，间隔 1s -> 2s -> 4s；服务端给了 `Retry-After` 时按它走。
 *
 * **只重试幂等请求**（GET/HEAD/OPTIONS/TRACE）。当前这个 client 上全是 @GET，
 * 但拦截器挂在通用 OkHttp 链上：将来若有人加 POST（如刷新 token），
 * 盲目重放会让服务端收到重复写入。非幂等方法一律只跑一次。
 *
 * 不重试 4xx（鉴权/参数错误重试无意义），但 **429 例外** —— 它明确表示
 * 「稍后重试」。原实现注释里写了「还带 Retry-After」，却一直按自己的 1/2/4s
 * 睡，被限流时可能比服务端要求的更早重试，白费一次机会。
 *
 * 注意：只有「成功拿到响应」时才会把它交给上层，返回的 Response 一定还是可读的；
 * 上一轮的响应在拿到新响应之后才关闭，若最后一轮抛 IOException，则返回上一轮
 * 那个尚未关闭的响应（而不是已关闭的旧响应），避免上层读到 body 时抛
 * IllegalStateException("closed") 而丢失真正的错误原因。
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    /** 指数退避的基准间隔。生产用默认 1s；单测传 0 避免每个用例白等一秒。 */
    private val baseDelayMs: Long = 1000L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val retryable = request.method.uppercase() in IDEMPOTENT_METHODS
        val allowedRetries = if (retryable) maxRetries else 0

        var response: Response? = null
        var exception: IOException? = null
        var tryCount = 0

        while (tryCount <= allowedRetries && (response == null || !response.isSuccessful)) {
            try {
                if (tryCount > 0) {
                    val waitMs = retryAfterMillis(response) ?: (baseDelayMs * (1L shl (tryCount - 1)))
                    // 可中断的等待：调用方取消协程时不该被一次 4s 的 sleep 拖住
                    Thread.sleep(waitMs.coerceAtMost(MAX_WAIT_MS))
                }

                val newResponse = chain.proceed(request)
                // 旧响应在拿到新响应之后才关闭：万一这一轮 proceed 抛异常，
                // 上一轮的响应仍然是可读的，可以安全地返回给上层。
                // 用 body?.close() 而不是 response.close()：后者在没有 body 的响应上
                // 会抛 IllegalStateException（单测里的假响应就是这种）。
                val previous = response
                response = newResponse
                previous?.body?.close()

                // 不重试客户端鉴权等错误 (4xx), 但重试网关和服务器瞬断 (5xx)。
                // 429 是例外：它明确表示「稍后重试」。
                if (newResponse.code in 400..499 && newResponse.code != 429) {
                    break
                }
                exception = null
            } catch (e: IOException) {
                exception = e
            } catch (e: InterruptedException) {
                // 协程被取消：恢复中断标记并立刻退出，不要把取消吞成一次重试
                Thread.currentThread().interrupt()
                throw IOException("retry interrupted", e)
            } finally {
                tryCount++
            }
        }

        // 有响应就返回响应（可读），一次都没拿到响应才抛异常
        return response ?: throw exception ?: IOException("Unknown retry failure")
    }

    /**
     * 解析 `Retry-After`：既支持秒数（"120"），也支持 HTTP-date。
     * 解析不出或为负则返回 null，由调用方回落到指数退避。
     */
    internal fun retryAfterMillis(response: Response?): Long? {
        val raw = response?.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { secs ->
            return (secs * 1000L).takeIf { it > 0 }
        }
        val date = runCatching {
            java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (date - System.currentTimeMillis()).takeIf { it > 0 }
    }

    private companion object {
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")

        /** 上限 30s：服务端偶尔给离谱的 Retry-After（如 3600），不该把请求挂死一小时。 */
        const val MAX_WAIT_MS = 30_000L
    }
}
