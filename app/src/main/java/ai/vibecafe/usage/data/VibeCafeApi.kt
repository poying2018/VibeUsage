package ai.vibecafe.usage.data

import retrofit2.http.GET
import retrofit2.http.Header

interface VibeCafeApi {
    @GET("/api/usage?days=30&tz=Asia%2FShanghai")
    suspend fun getUsageDaily(
        @Header("Authorization") authorization: String
    ): UsageResponse

    @GET("/api/usage?days=1&tz=Asia%2FShanghai")
    suspend fun getUsageHourly(
        @Header("Authorization") authorization: String
    ): UsageResponse
}
