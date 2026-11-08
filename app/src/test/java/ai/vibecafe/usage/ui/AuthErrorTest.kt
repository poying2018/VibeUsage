package ai.vibecafe.usage.ui

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * 主数据加载失败的两种分支要分清：
 * - 401/403（Key 失效）→ 错误卡给「清除 Key，重新登录」按钮；
 * - 网络/服务端错误 → 只提示刷新，绝不诱导用户清掉正确的 Key。
 * 判错了就是把弱网用户往重新登录上赶，[isAuthError] 锁死这个口径。
 */
class AuthErrorTest {

    private fun http(code: Int) =
        HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `401 and 403 are auth errors`() {
        assertTrue(isAuthError(http(401)))
        assertTrue(isAuthError(http(403)))
    }

    @Test
    fun `other http codes are not auth errors`() {
        assertFalse(isAuthError(http(400)))
        assertFalse(isAuthError(http(404)))
        assertFalse(isAuthError(http(429)))
        assertFalse(isAuthError(http(500)))
    }

    @Test
    fun `network errors and null are not auth errors`() {
        assertFalse(isAuthError(IOException("timeout")))
        assertFalse(isAuthError(null))
    }
}
