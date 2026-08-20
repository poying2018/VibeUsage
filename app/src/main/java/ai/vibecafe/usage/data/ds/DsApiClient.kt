package ai.vibecafe.usage.data.ds

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 官方平台 API 客户端（DS+ Milky 使用的后端）。
 * Base URL: https://platform.deepseek.com
 *
 * 注意：platform.deepseek.com 有 WAF，必须伪装浏览器 User-Agent，
 * 否则会返回 HTTP 429。原版 DS+ Milky 使用的 UA：
 * Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36
 */
object DsApiClient {
    private const val BASE_URL = "https://platform.deepseek.com"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"

    private val browserHeadersInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .build()
        chain.proceed(request)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(browserHeadersInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    val api: DeepSeekPlatformApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeepSeekPlatformApi::class.java)
    }

    /** DeepSeek 官方 API (api.deepseek.com)：用 API Key 查余额等。 */
    object Official {
        private const val OFFICIAL_URL = "https://api.deepseek.com"

        private val officialClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", BROWSER_UA)
                        .header("Accept", "application/json")
                        .build()
                    chain.proceed(request)
                })
                .build()
        }

        val api: DeepSeekOfficialApi by lazy {
            Retrofit.Builder()
                .baseUrl(OFFICIAL_URL)
                .client(officialClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(DeepSeekOfficialApi::class.java)
        }
    }
}