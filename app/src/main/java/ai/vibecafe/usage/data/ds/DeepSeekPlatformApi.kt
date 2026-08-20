package ai.vibecafe.usage.data.ds

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * DeepSeek 官方平台 API（DS+ Milky 使用的后端）。
 * Base URL: https://platform.deepseek.com
 */
interface DeepSeekPlatformApi {

    /** 登录：手机号+密码，POST auth-api/v0/users/login，响应 token 在 data.biz_data.user.token。 */
    @POST("/auth-api/v0/users/login")
    suspend fun login(@Body request: LoginRequest): DsResponse<LoginBiz>

    /** 用量金额。 */
    @GET("/api/v0/usage/amount")
    suspend fun getUsageAmount(
        @Header("Authorization") authorization: String
    ): DsResponse<DashboardResponse>

    /** 费用。 */
    @GET("/api/v0/usage/cost")
    suspend fun getUsageCost(
        @Header("Authorization") authorization: String
    ): DsResponse<DashboardResponse>

    /** 获取 API 密钥列表。 */
    @GET("/api/v0/users/get_api_keys")
    suspend fun getApiKeys(
        @Header("Authorization") authorization: String
    ): DsResponse<UserSummaryBiz>

    /** 获取用户摘要。 */
    @GET("/api/v0/users/get_user_summary")
    suspend fun getUserSummary(
        @Header("Authorization") authorization: String
    ): DsResponse<UserSummaryBiz>
}