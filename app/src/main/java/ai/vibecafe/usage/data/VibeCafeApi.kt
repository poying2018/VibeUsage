package ai.vibecafe.usage.data

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface VibeCafeApi {
    /**
     * 日粒度数据必须覆盖全量历史：客户端按 今日/24小时/7天/30天/90天/全部 本地过滤，
     * 若此处限制 days，「全部」会被静默截断。服务端对超大 days 直接返回账号起始日起的全部数据。
     */
    @GET("/api/usage?days=3650&tz=Asia%2FShanghai")
    suspend fun getUsageDaily(
        @Header("Authorization") authorization: String
    ): UsageResponse

    @GET("/api/usage?days=1&tz=Asia%2FShanghai")
    suspend fun getUsageHourly(
        @Header("Authorization") authorization: String
    ): UsageResponse

    /** 自定义日期范围（yyyy-MM-dd，含首尾两天），与官方客户端 custom 查询一致。 */
    @GET("/api/usage")
    suspend fun getUsageCustom(
        @Header("Authorization") authorization: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("tz") tz: String = "Asia/Shanghai"
    ): UsageResponse
}
