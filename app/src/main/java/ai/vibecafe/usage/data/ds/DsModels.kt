package ai.vibecafe.usage.data.ds

import com.google.gson.annotations.SerializedName

/** 通用响应包装（原版 DsResponse：有 code 字段，0 表示成功）。 */
data class DsResponse<T>(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("data") val data: T? = null,
    @SerializedName("msg") val msg: String? = null
)

/** 通用数据包装。 */
data class DsData<T>(
    @SerializedName("list") val list: List<T>? = null,
    @SerializedName("total") val total: Int? = null
)

// ─── 登录 ───
// 原版 LoginRequest：仅 mobile + password 两个字段有值，其余为 null（Gson 不序列化 null）
data class LoginRequest(
    @SerializedName("mobile") val mobile: String,
    @SerializedName("password") val password: String,
    @SerializedName("areaCode") val areaCode: String? = null,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("os") val os: String? = null
)

/** 登录响应：token 位于 data.biz_data.user.token。 */
data class LoginBiz(
    @SerializedName("biz_data") val bizData: LoginBizData? = null
)

data class LoginBizData(
    @SerializedName("user") val user: LoginUser? = null
)

data class LoginUser(
    @SerializedName("token") val token: String? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("name") val name: String? = null
)

data class UserInfo(
    @SerializedName("id") val id: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("name") val name: String? = null
)

// ─── 仪表盘 / 总览 ───

data class DashboardResponse(
    @SerializedName("wallet") val wallet: Wallet? = null,
    @SerializedName("usage") val usage: UsageData? = null,
    @SerializedName("monthly_costs") val monthlyCosts: List<CostItem>? = null,
    @SerializedName("monthly_token_usage") val monthlyTokenUsage: List<DayUsage>? = null,
    @SerializedName("monthly_usage") val monthlyUsage: List<DayUsage>? = null,
    @SerializedName("realtime") val realtime: RealtimeResponse? = null
)

data class Wallet(
    @SerializedName("balance") val balance: String? = null,
    @SerializedName("balance_alerts") val balanceAlerts: List<BalanceAlert>? = null
)

data class BalanceAlert(
    @SerializedName("threshold") val threshold: Double? = null,
    @SerializedName("enabled") val enabled: Boolean? = null
)

data class UsageData(
    @SerializedName("total_amount") val totalAmount: Double? = null,
    @SerializedName("total_cost") val totalCost: Double? = null,
    @SerializedName("data_month") val dataMonth: String? = null,
    @SerializedName("data_year") val dataYear: Int? = null,
    @SerializedName("data_avail") val dataAvail: Boolean? = null
)

data class CostItem(
    @SerializedName("model") val model: String? = null,
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("tokens") val tokens: Long? = null,
    @SerializedName("date") val date: String? = null
)

data class DayUsage(
    @SerializedName("date") val date: String? = null,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("tokens") val tokens: Long? = null
)

data class ModelUsage(
    @SerializedName("model") val model: String? = null,
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("tokens") val tokens: Long? = null,
    @SerializedName("count") val count: Int? = null
)

data class UsageItem(
    @SerializedName("model") val model: String? = null,
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("tokens") val tokens: Long? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("count") val count: Int? = null
)

// ─── 用户摘要 ───

data class UserSummaryBiz(
    @SerializedName("api_keys") val apiKeys: List<ApiKeyInfo>? = null,
    @SerializedName("api_key_count") val apiKeyCount: Int? = null
)

data class ApiKeyInfo(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("prefix") val prefix: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_used_at") val lastUsedAt: String? = null
)

// ─── 实时数据 ───

data class RealtimeResponse(
    @SerializedName("title") val title: String? = null,
    @SerializedName("data") val data: List<RealtimeDataPoint>? = null
)

data class RealtimeDataPoint(
    @SerializedName("time") val time: String? = null,
    @SerializedName("value") val value: Double? = null
)

// ─── DeepSeek 官方 API Key 余额查询 (api.deepseek.com) ───

data class BalanceResponse(
    @SerializedName("is_available") val isAvailable: Boolean? = null,
    @SerializedName("balance_infos") val balanceInfos: List<BalanceInfo>? = null
)

data class BalanceInfo(
    @SerializedName("currency") val currency: String? = null,
    @SerializedName("total_balance") val totalBalance: String? = null,
    @SerializedName("granted_balance") val grantedBalance: String? = null,
    @SerializedName("topped_up_balance") val toppedUpBalance: String? = null
)

/** 模型列表响应。 */
data class ModelsResponse(
    @SerializedName("object") val obj: String? = null,
    @SerializedName("data") val data: List<ModelInfo>? = null
)

data class ModelInfo(
    @SerializedName("id") val id: String? = null,
    @SerializedName("object") val obj: String? = null,
    @SerializedName("owned_by") val ownedBy: String? = null
)

// ─── 面板 UI 状态 ───

data class DsPanelState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val apiKey: String? = null,
    val balance: String? = null,
    val currency: String? = null,
    val grantedBalance: String? = null,
    val toppedUpBalance: String? = null,
    val isAvailable: Boolean? = null,
    val models: List<ModelInfo>? = null,
    val error: String? = null
)