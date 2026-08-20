package ai.vibecafe.usage.data.ds

import retrofit2.http.GET
import retrofit2.http.Header

/**
 * DeepSeek 官方 API（api.deepseek.com）。
 * 用 API Key 即可查询余额，无需平台账号登录。
 */
interface DeepSeekOfficialApi {

    /** 查询账户余额。 */
    @GET("/user/balance")
    suspend fun getBalance(
        @Header("Authorization") authorization: String
    ): BalanceResponse

    /** 查询可用模型列表。 */
    @GET("/models")
    suspend fun getModels(
        @Header("Authorization") authorization: String
    ): ModelsResponse
}