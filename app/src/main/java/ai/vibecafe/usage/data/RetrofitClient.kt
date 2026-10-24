package ai.vibecafe.usage.data

import ai.vibecafe.usage.data.quota.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://vibecafe.ai"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 与额度层一致挂上 DnsResolver：模拟器与部分大陆网络下系统解析会间歇性整体失效，
            // 此时概览页会全挂而额度页照常工作
            .dns(DnsResolver)
            .addInterceptor(RetryInterceptor())
            // 网络日志只在 debug 包开启，release 不往 logcat 泄露请求信息
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (ai.vibecafe.usage.BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .build()
    }

    val api: VibeCafeApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VibeCafeApi::class.java)
    }
}
