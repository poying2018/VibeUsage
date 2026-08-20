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

/** 仓库的一个 tag（检查更新以 tag 为准，而不是 releases/latest——只打 tag 不建 Release 也能被发现）。 */
data class GitHubTag(
    @SerializedName("name") val name: String? = null
)