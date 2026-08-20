package ai.vibecafe.usage.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 检查更新 + 下载 APK。
 *
 * 通过与仓库同名的 GitHub public API 查询「最新 release」，
 * 从中取出第一个 APK asset 的下载地址；下载时用 OkHttp 阻塞式读取并上报进度。
 * 说明：更新源依赖 GitHub（README 指向的 Releases 页），网络受限时可能失败，
 * 失败会被上层捕获并给出友好提示，不影响正常使用。
 */
object UpdateChecker {

    /** 远程版本号只用于比较，硬编码在此（与 BuildConfig.VERSION_NAME 相对照时用）。 */
    private const val REPO = "poying2018/VibeUsage"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** 下载目标固定文件名。 */
    const val APK_FILE_NAME = "vibeusage-update.apk"

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 查询最新 release 元数据；失败 / 无 release 时返回 null。 */
    suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VibeUsage")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) return@use null
                gson.fromJson(response.body!!.string(), GitHubRelease::class.java)
            }
        }.getOrNull()
    }

    /**
     * 流式下载 APK 到 [dest]。
     * [onProgress] 回调 0f..1f 进度（从 IO 线程调用，调用方需自行切回主线程更新 UI）。
     * 返回是否成功。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (dest.exists()) dest.delete()
            try {
                val request = Request.Builder().url(url).header("User-Agent", "VibeUsage").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    /**
     * 简单语义化版本号比较：解析出数字段序列逐个比较。
     * 返回 `latest` 是否严格高于 `current`。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = versionNumbers(latest)
        val b = versionNumbers(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private val versionSegment = Regex("\\d+")

    private fun versionNumbers(version: String): List<Int> =
        versionSegment.findAll(version.removePrefix("v").trim()).map { it.value.toInt() }.toList()
}