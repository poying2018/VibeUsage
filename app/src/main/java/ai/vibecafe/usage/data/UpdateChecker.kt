package ai.vibecafe.usage.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 检查更新 + 下载 APK。
 *
 * 版本检查以**仓库 tag 列表**为准（语义化版本号取最大），而不是 `releases/latest`——
 * 后者只返回「最新发布过的 Release」，若新版本只打了 tag 还没建 Release，就永远查不到
 * （v2.8.1 就是教训：tag 已推但没建 Release，latest 仍指向 v2.8.0）。
 *
 * 发现新版本后再按 tag 查对应 Release 的 APK 附件：
 *  - 该 tag 有 Release 且带 APK → 可下载
 *  - 该 tag 没有 Release / 没有 APK → 提示「新版本已发布，安装包暂未提供」
 *
 * 说明：更新源依赖 GitHub（README 指向的 Releases 页），网络受限时可能失败，
 * 失败会被上层捕获并给出友好提示，不影响正常使用。
 */
object UpdateChecker {

    private const val REPO = "poying2018/VibeUsage"
    private const val TAGS_URL = "https://api.github.com/repos/$REPO/tags?per_page=100"
    private const val RELEASE_BY_TAG_URL = "https://api.github.com/repos/$REPO/releases/tags/"

    /** 下载目标固定文件名。 */
    const val APK_FILE_NAME = "vibeusage-update.apk"

    /** 检查结果状态。 */
    enum class Status {
        /** 仓库无任何可解析的版本 tag。 */
        NO_RELEASE,

        /** 当前已是最新。 */
        UP_TO_DATE,

        /** 发现新版本；[apkUrl] 为 null 表示该版本暂无安装包。 */
        UPDATE_AVAILABLE,

        /** 网络错误 / 接口不可用 / 被限流等，非「无更新」语义。 */
        FAILED
    }

    /** 检查结果。 */
    data class CheckResult(
        val status: Status,
        val latestVersion: String? = null,
        val apkUrl: String? = null,
        val detail: String? = null
    )

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 检查是否有新版本。
     * 与 [currentVersion]（BuildConfig.VERSION_NAME）比较，返回结构化结果。
     */
    suspend fun checkForUpdate(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        val tags = runCatching { fetchTags() }.getOrNull()
        if (tags == null) {
            return@withContext CheckResult(Status.FAILED, detail = "检查更新失败，请检查网络后重试")
        }
        // 只保留能解析出版本号的 tag，取语义最大者
        val versioned = tags.mapNotNull { it.name?.takeIf { n -> versionNumbers(n).isNotEmpty() } }
        if (versioned.isEmpty()) {
            return@withContext CheckResult(Status.NO_RELEASE)
        }
        val latest = versioned.maxWithOrNull(Comparator { a, b -> compareVersions(a, b) })!!

        if (!isNewer(latest, currentVersion)) {
            return@withContext CheckResult(Status.UP_TO_DATE, latestVersion = latest)
        }

        // 有新版本：查该 tag 是否有带 APK 的 Release（404 / 无 APK → 暂无安装包）
        val apkUrl = runCatching { fetchReleaseApkUrl(latest) }.getOrNull()
        CheckResult(Status.UPDATE_AVAILABLE, latestVersion = latest, apkUrl = apkUrl)
    }

    /** 拉取仓库 tag 列表；失败返回 null。经 Cloudflare 代理访问，国内网络可达。 */
    private fun fetchTags(): List<GitHubTag>? {
        val request = Request.Builder()
            .url(DownloadAccelerator.accelerate(TAGS_URL))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VibeUsage")
            .header("X-App-Key", DownloadAccelerator.appKey())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) return null
            val json = response.body!!.string()
            return gson.fromJson(json, Array<GitHubTag>::class.java)?.toList() ?: emptyList()
        }
    }

    /** 按 tag 查 Release 里第一个 APK 附件的下载地址；该 tag 无 Release / 无 APK 时返回 null。经 Cloudflare 代理访问。 */
    private fun fetchReleaseApkUrl(tag: String): String? {
        val encoded = URLEncoder.encode(tag, "UTF-8")
        val request = Request.Builder()
            .url(DownloadAccelerator.accelerate(RELEASE_BY_TAG_URL + encoded))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VibeUsage")
            .header("X-App-Key", DownloadAccelerator.appKey())
            .build()
        client.newCall(request).execute().use { response ->
            // 404：该 tag 没有 Release（只打了 tag）
            if (!response.isSuccessful || response.body == null) return null
            val release = gson.fromJson(response.body!!.string(), GitHubRelease::class.java) ?: return null
            return release.assets.firstOrNull {
                it.browserDownloadUrl != null && it.name?.lowercase()?.endsWith(".apk") == true
            }?.browserDownloadUrl
        }
    }

    /**
     * 流式下载 APK 到 [dest]。
     * 走 Cloudflare 边缘加速代理（[DownloadAccelerator]），代理地址与令牌对用户不可见。
     * [onProgress] 回调 0f..1f 进度（从 IO 线程调用，调用方需自行切回主线程更新 UI）。
     * 返回是否成功。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (dest.exists()) dest.delete()
            try {
                val request = Request.Builder()
                    .url(DownloadAccelerator.accelerate(url))
                    .header("User-Agent", "VibeUsage")
                    .header("X-App-Key", DownloadAccelerator.appKey())
                    .build()
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
    fun isNewer(latest: String, current: String): Boolean = compareVersions(latest, current) > 0

    /**
     * 下载完成后校验安装包：尝试获取 `<apkUrl>.sha256` 并与本地文件哈希比对。
     * 远端未提供 hash（404/网络失败）时视为通过，保持对旧 Release 的兼容；
     * 提供了但不匹配时返回 false，上层阻止安装。
     */
    suspend fun verifyDownloadedApk(apkUrl: String, file: File): Boolean = withContext(Dispatchers.IO) {
        val expected = runCatching { fetchSha256(apkUrl + ".sha256") }.getOrNull()
            ?: return@withContext true
        val actual = sha256Of(file) ?: return@withContext true
        expected.equals(actual, ignoreCase = true)
    }

    /** 拉取远端 hash 文本（首行第一段 hex）。 */
    private fun fetchSha256(url: String): String? {
        val request = Request.Builder()
            .url(DownloadAccelerator.accelerate(url))
            .header("User-Agent", "VibeUsage")
            .header("X-App-Key", DownloadAccelerator.appKey())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) return null
            val text = response.body!!.string().trim()
            // 兼容 "abc123  filename.apk" 与纯 hex 两种格式
            return text.split(Regex("\\s+")).firstOrNull()?.takeIf { it.matches(Regex("[0-9a-fA-F]{32,128}")) }
        }
    }

    private fun sha256Of(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 版本号比较器：a < b 返回负数，相等返回 0，a > b 返回正数。 */
    private fun compareVersions(a: String, b: String): Int {
        val va = versionNumbers(a)
        val vb = versionNumbers(b)
        val n = maxOf(va.size, vb.size)
        for (i in 0 until n) {
            val x = va.getOrElse(i) { 0 }
            val y = vb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private val versionSegment = Regex("\\d+")

    private fun versionNumbers(version: String): List<Int> =
        versionSegment.findAll(version.removePrefix("v").trim()).map { it.value.toInt() }.toList()
}
