package ai.vibecafe.usage.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 指数退避重试拦截器：处理移动端弱网突发超时、DNS 阶段失败和 5xx 错误。
 * 最多重试 3 次，间隔 1s -> 2s -> 4s，不拦截 4xx（如 Key 无效）。
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
                
                response?.close() // 关闭上一次失败的 response
                response = chain.proceed(request)
                
                // 不重试客户端鉴权等错误 (4xx), 但重试网关和服务器瞬断 (5xx)
                if (response.code in 400..499) {
                    break
                }
                exception = null
            } catch (e: IOException) {
                exception = e
            } finally {
                tryCount++
            }
        }

        return response ?: throw exception ?: IOException("Unknown retry failure")
    }
}