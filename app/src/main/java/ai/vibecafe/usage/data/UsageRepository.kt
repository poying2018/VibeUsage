package ai.vibecafe.usage.data

class UsageRepository {
    private val api = RetrofitClient.api

    suspend fun fetchDailyData(apiKey: String): Result<UsageResponse> = runCatching {
        api.getUsageDaily("Bearer $apiKey")
    }

    suspend fun fetchHourlyData(apiKey: String): Result<UsageResponse> = runCatching {
        api.getUsageHourly("Bearer $apiKey")
    }
}
