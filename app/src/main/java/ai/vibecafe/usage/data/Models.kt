package ai.vibecafe.usage.data

import com.google.gson.annotations.SerializedName

data class UsageResponse(
    @SerializedName("buckets") val buckets: List<Bucket>,
    @SerializedName("sessions") val sessions: List<Session>,
    @SerializedName("hasAnyData") val hasAnyData: Boolean
)

data class Bucket(
    @SerializedName("source") val source: String,
    @SerializedName("model") val model: String,
    @SerializedName("project") val project: String,
    @SerializedName("hostname") val hostname: String,
    @SerializedName("bucketStart") val bucketStart: String,
    @SerializedName("inputTokens") val inputTokens: Long,
    @SerializedName("outputTokens") val outputTokens: Long,
    @SerializedName("cachedInputTokens") val cachedInputTokens: Long,
    @SerializedName("reasoningOutputTokens") val reasoningOutputTokens: Long,
    @SerializedName("totalTokens") val totalTokens: Long,
    @SerializedName("estimatedCost") val estimatedCost: Double
) {
    /** Full token count including cached input — the API field excludes cached. */
    fun fullTokens(): Long = inputTokens + outputTokens + reasoningOutputTokens + cachedInputTokens
}

data class Session(
    @SerializedName("source") val source: String,
    @SerializedName("project") val project: String,
    @SerializedName("hostname") val hostname: String,
    @SerializedName("firstMessageAt") val firstMessageAt: String,
    @SerializedName("lastMessageAt") val lastMessageAt: String,
    @SerializedName("durationSeconds") val durationSeconds: Long,
    @SerializedName("activeSeconds") val activeSeconds: Long,
    @SerializedName("messageCount") val messageCount: Int,
    @SerializedName("userMessageCount") val userMessageCount: Int,
    @SerializedName("userPromptHours") val userPromptHours: List<Int>
)
