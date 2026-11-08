package ai.vibecafe.usage.data

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
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

    /**
     * 今日数据（days=1）。小组件只需要今天的用量，
     * 不必为此拉 days=3650 的全量历史。
     */
    @GET("/api/usage?days=1&tz=Asia%2FShanghai")
    suspend fun getUsageToday(
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

    /**
     * 设备码登录：发起。无鉴权 POST {}，返回 deviceCode / userCode / verificationUriComplete。
     */
    @POST("/api/usage/device/code")
    suspend fun startDeviceLogin(
        @Body body: RequestBody
    ): Response<ResponseBody>

    /**
     * 设备码登录：轮询。等待用户在浏览器批准时返回 HTTP 200 + {"error":"authorization_pending"}，
     * 所以必须用 Response<ResponseBody> 拿原始状态码与报文自行解析。
     */
    @POST("/api/usage/device/poll")
    suspend fun pollDeviceLogin(
        @Body body: RequestBody
    ): Response<ResponseBody>
}
