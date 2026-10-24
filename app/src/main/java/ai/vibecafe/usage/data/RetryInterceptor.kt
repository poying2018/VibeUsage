package ai.vibecafe.usage.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 指数退避重试拦截器：处理移动端弱网突发超时、DNS 阶段失败和 5xx 错误。
 * 最多重试 3 次，间隔 1s -> 2s -> 4s，不拦截 4xx（如 Key 无效）。
 *
 * 注意：只有「成功拿到响应」时才会把它交给上层，返回的 Response 一定还是可读的；
 * 上一轮的响应在拿到新响应之后才关闭，若最后一轮抛 IOException，则返回上一轮
 * 那个尚未关闭的响应（而不是已关闭的旧响应），避免上层读到 body 时抛
 * IllegalStateException("closed") 而丢失真正的错误原因。
 */
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var exception: IOException? = null
        var tryCount = 0

        while (tryCount <= maxRetries && (response == null || !response.isSuccessful)) {
            try {
                if (tryCount > 0) {
                    Thread.sleep(1000L * (1 shl (tryCount - 1))) // 1s, 2s, 4s
                }

                val newResponse = chain.proceed(request)
                // 旧响应在拿到新响应之后才关闭：万一这一轮 proceed 抛异常，
                // 上一轮的响应仍然是可读的，可以安全地返回给上层
                val previous = response
                response = newResponse
                previous?.close()

                // 不重试客户端鉴权等错误 (4xx), 但重试网关和服务器瞬断 (5xx)
                if (newResponse.code in 400..499) {
                    break
                }
                exception = null
            } catch (e: IOException) {
                exception = e
            } finally {
                tryCount++
            }
        }

        // 有响应就返回响应（可读），一次都没拿到响应才抛异常
        return response ?: throw exception ?: IOException("Unknown retry failure")
    }
}
