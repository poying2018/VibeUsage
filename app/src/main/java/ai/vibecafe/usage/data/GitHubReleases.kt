package ai.vibecafe.usage.data

import com.google.gson.annotations.SerializedName

/** GitHub Release 元数据（仅取检查更新所需字段）。 */
data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("assets") val assets: List<ReleaseAsset> = emptyList()
)

/** Release 里的一个附件（找第一个 APK）。 */
data class ReleaseAsset(
    @SerializedName("name") val name: String? = null,
    @SerializedName("browser_download_url") val browserDownloadUrl: String? = null,
    @SerializedName("size") val size: Long = 0L
)