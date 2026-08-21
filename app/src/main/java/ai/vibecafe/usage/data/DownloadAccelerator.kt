package ai.vibecafe.usage.data

import android.util.Base64

/**
 * GitHub 下载加速器 —— 通过 Cloudflare 边缘代理下载 APK。
 *
 * 安全设计：
 * - 代理地址与访问令牌以 XOR+Base64 混淆后硬编码，运行时才解密，
 *   反编译源码只能看到密文，无法直接读取代理地址/令牌。
 * - 令牌通过 Authorization 头传递，不出现在 URL 中。
 * - 真正的防刷由 Worker 端保障：AUTH_TOKEN 校验 + IP 限流 + 全局限流。
 * - 加速过程对用户完全透明，界面不展示代理地址。
 */
object DownloadAccelerator {

    /** 混淆密钥（与生成密文的工具保持一致）。 */
    private const val OBFUSCATION_KEY = "VbUs2026DSH"

    /** 加密的 Cloudflare Worker 代理地址（自定义域名，国内可访问）。 */
    private const val ENC_BASE = "PhYhA0EKHRkjOjw+FzddAAACA3RqeWRMLQpI"

    /** 加密的 app 专用访问密钥（Worker 端 APP_KEY，经 X-App-Key 头校验）。 */
    private const val ENC_APP_KEY = "b1tgQgcCAQRwMSxmW2EXUQlWBH1jKjcGNEUFCQdQJzB5NFJnSgQHVAF9NyxuUmEXUwBQA3E1Lm9VbBcABlQEIQ=="

    /** 解密混淆字符串。 */
    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val key = OBFUSCATION_KEY.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    /** 代理地址（不对外暴露，仅内部使用）。 */
    private fun base(): String = decrypt(ENC_BASE)

    /** app 专用访问密钥（不对外暴露，仅内部使用）。 */
    internal fun appKey(): String = decrypt(ENC_APP_KEY)

    /**
     * 把原始 GitHub 下载地址转换为经 Cloudflare 边缘加速的代理地址。
     * 例：https://github.com/.../app.apk
     *   -> https://<worker>.<subdomain>.workers.dev/https://github.com/.../app.apk
     */
    fun accelerate(originalUrl: String): String {
        val b = base()
        return if (originalUrl.startsWith("https://") || originalUrl.startsWith("http://")) {
            "$b/$originalUrl"
        } else {
            originalUrl // 非 http 地址不加速，原样返回
        }
    }
}