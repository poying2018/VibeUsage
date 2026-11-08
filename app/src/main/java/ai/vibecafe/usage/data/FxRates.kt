package ai.vibecafe.usage.data

import ai.vibecafe.usage.data.quota.QuotaHttp
import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 美元 → 人民币汇率（自动检测）。
 *
 * 服务端与各家额度口径都是美元，界面统一按人民币展示：显示层乘这个汇率。
 * 汇率来源：免费公开接口（er-api / exchangerate-api，响应形状都是
 * `{"rates":{"CNY":7.2}}`，走应用内建的中转优先通道，国内可达）。
 *
 * - 内存里一份当前值（[usdToCny]，供同步格式化函数直接读）+ [rateFlow] 供 Compose 订阅，
 *   汇率刷新后界面自动重组；
 * - 持久化一份（[PREFS_NAME]），离线/接口挂时用上次值，绝不凭空变 0；
 * - 从没拉到过时用内置近值 [DEFAULT_USD_CNY] 兜底。
 *
 * 合理性带 sanity check：服务端返回脏值（0/负数/离谱大数）一律丢弃，
 * 不让一次坏响应把全屏金额算飞。
 */
object FxRates {

    /** 从没拉到过汇率时的兜底（近几个月的中间价）。 */
    const val DEFAULT_USD_CNY = 6.70

    private const val TAG = "VibeUsage"
    private const val PREFS_NAME = "vibe_fx"
    private const val KEY_RATE = "usd_cny"
    private const val KEY_FETCHED_AT = "fetched_at"

    /** 拉取结果最多缓存 24 小时（ECB 日价 + 接口日更，24h 内没必要反复打）。 */
    private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000L

    private val SOURCES = listOf(
        "https://open.er-api.com/v6/latest/USD",
        "https://api.exchangerate-api.com/v4/latest/USD"
    )

    /** 当前汇率（内存权威值，同步格式化函数直接读）。 */
    @Volatile
    var usdToCny: Double = DEFAULT_USD_CNY
        internal set

    private val _rate = MutableStateFlow(DEFAULT_USD_CNY)

    /** 供 Compose 订阅：汇率一变，依赖它的文案自动重组。 */
    val rateFlow: StateFlow<Double> = _rate.asStateFlow()

    private val refreshMutex = Mutex()

    /** 美元 → 人民币。 */
    fun convert(usd: Double): Double = usd * usdToCny

    /** 首选：从持久化读上次值（进程启动 / 后台任务入口调用，纯内存读，很便宜）。 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getFloat(KEY_RATE, 0f).toDouble()
        if (isSane(stored)) {
            usdToCny = stored
            _rate.value = stored
        }
    }

    /** 超过缓存期才拉；没过期直接返回当前值。 */
    suspend fun refreshIfStale(context: Context): Double =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
            val fresh = fetchedAt > 0L && System.currentTimeMillis() - fetchedAt < REFRESH_INTERVAL_MS
            if (fresh) usdToCny else refreshLocked(context, prefs, force = false)
        }

    /** 强制刷新（设置页手动点、或上次失败想重试时用）。 */
    suspend fun refresh(context: Context): Double =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            refreshLocked(context, prefs, force = true)
        }

    private suspend fun refreshLocked(
        context: Context,
        prefs: android.content.SharedPreferences,
        force: Boolean
    ): Double =
        refreshMutex.withLock {
            // 拿锁后再看一次缓存新鲜度：并发的两个刷新者，先到的已写好，后到的直接用，
            // 不然冷启动时必定多打一次接口
            if (!force) {
                val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
                if (fetchedAt > 0L && System.currentTimeMillis() - fetchedAt < REFRESH_INTERVAL_MS) {
                    return@withLock usdToCny
                }
            }
            for (url in SOURCES) {
                val rate = runCatching {
                    parse(QuotaHttp.get(url, bearer = null, proxyFirst = true, ua = "VibeUsage/2.23"))
                }.getOrNull() ?: continue
                usdToCny = rate
                _rate.value = rate
                prefs.edit()
                    .putFloat(KEY_RATE, rate.toFloat())
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "USD/CNY rate fetched: $rate")
                return rate
            }
            Log.d(TAG, "USD/CNY rate fetch failed, keep ${usdToCny}")
            usdToCny
        }

    /**
     * `{"rates":{"CNY":7.2}}` → 汇率；形状/数值不对返回 null。
     * 三家接口形状一致（frankfurter 老域名重定向后也不例外），一个解析器全覆盖。
     */
    internal fun parse(json: String): Double? {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val rates = obj.get("rates") as? com.google.gson.JsonObject ?: return null
        val v = (rates.get("CNY") as? com.google.gson.JsonPrimitive)?.takeIf { it.isNumber }?.asDouble
            ?: return null
        return if (isSane(v)) v else null
    }

    /** 美元/人民币历史合理带（6~7.8 居多，放宽到 5~9）；带外一律视为脏数据。 */
    private fun isSane(v: Double): Boolean = v.isFinite() && v in 5.0..9.0
}
