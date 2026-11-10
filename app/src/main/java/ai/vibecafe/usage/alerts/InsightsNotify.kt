package ai.vibecafe.usage.alerts

import ai.vibecafe.usage.MainActivity
import ai.vibecafe.usage.R
import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.data.FxRates
import ai.vibecafe.usage.data.UsageRepository
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.ui.ag.ExtraProvider
import ai.vibecafe.usage.ui.formatCost
import ai.vibecafe.usage.ui.formatTokens
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 洞察类本地通知（纯本地，无服务端）：
 *
 * 1. 额度预警：每 6 小时检查已接入供应商，任一额度窗口剩余低于阈值（默认 20%）时提醒，
 *    同一供应商每天最多一条；接口调用失败不打扰用户，按 WorkManager 重试。
 * 2. 用量摘要：每 24 小时推送「今日消耗 + Top 模型」，每天最多一条。
 *
 * 两个开关都持久化在 vibe_usage prefs，设置页可随时关闭。
 */
object InsightsNotify {

    internal const val PREFS_NAME = "vibe_usage"
    internal const val QUOTA_PREFS = "quota_extra"
    private const val KEY_QUOTA_ENABLED = "quota_alert_enabled"
    private const val KEY_QUOTA_THRESHOLD = "quota_alert_threshold"
    private const val KEY_SUMMARY_ENABLED = "summary_enabled"
    private const val KEY_QUOTA_LAST = "quota_alert_last_"
    private const val KEY_SUMMARY_LAST_DAY = "summary_last_day"

    private const val CHANNEL_QUOTA = "quota_alert"
    private const val CHANNEL_SUMMARY = "usage_summary"
    private const val QUOTA_WORK = "quota-alert-6h"
    private const val SUMMARY_WORK = "usage-summary-daily"
    // 固定基准 + 供应商序号：避免和预算提醒(41) / 摘要(43) 的 id 撞车。
    // 之前用 42 + hashCode 取低 16 位，hash 恰为 1 时就会覆盖摘要通知。
    private const val NOTIFY_QUOTA_BASE = 1000
    private const val NOTIFY_SUMMARY = 43

    /** 与 MainViewModel / BudgetNotify 同一键：后台提醒与页面同口径。 */
    internal const val KEY_DEVICES = "selected_devices"

    fun quotaEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_QUOTA_ENABLED, true)

    fun setQuotaEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_QUOTA_ENABLED, enabled).apply()

    /** 剩余额度低于该百分比时提醒（5..90，默认 20）。 */
    fun quotaThreshold(context: Context): Int =
        prefs(context).getInt(KEY_QUOTA_THRESHOLD, 20).coerceIn(5, 90)

    fun summaryEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SUMMARY_ENABLED, true)

    fun setSummaryEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_SUMMARY_ENABLED, enabled).apply()

    /** App 启动时注册两个周期任务（KEEP 幂等）。 */
    fun ensureScheduled(context: Context) {
        runCatching {
            val network = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                QUOTA_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<QuotaAlertWorker>(6, TimeUnit.HOURS)
                    .setConstraints(network)
                    .build()
            )
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SUMMARY_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SummaryNotifyWorker>(24, TimeUnit.HOURS)
                    .setConstraints(network)
                    .build()
            )
        }
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUOTA, "额度预警", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "已接入平台额度不足时提醒" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, "用量摘要", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "每日推送今日消耗与 Top 模型" }
        )
    }

    /** 按供应商取稳定的通知 id：同一条提醒更新而不是叠一屏。 */
    internal fun quotaNotificationId(providerId: String): Int {
        val index = ExtraProvider.entries.indexOfFirst { it.id == providerId }
        val slot = if (index >= 0) index else providerId.hashCode().and(0x7FFF)
        return NOTIFY_QUOTA_BASE + slot
    }

    /** 同一供应商每天最多提醒一次（key 带供应商 id，值存日期）。 */
    internal fun notifyQuotaLow(context: Context, providerId: String, title: String, percent: Int) {
        val p = prefs(context)
        val today = dayStamp()
        if (p.getString(KEY_QUOTA_LAST + providerId, null) == today) return
        if (!post(context, CHANNEL_QUOTA, quotaNotificationId(providerId),
                "$title 额度不足", "剩余额度约 $percent%，请及时补充或切换套餐")) return
        p.edit().putString(KEY_QUOTA_LAST + providerId, today).apply()
    }

    internal fun notifyDailySummary(context: Context, cost: Double, tokens: Long, topModel: String?) {
        val p = prefs(context)
        val today = dayStamp()
        if (p.getString(KEY_SUMMARY_LAST_DAY, null) == today) return
        val text = buildString {
            append("今日消耗 ")
            append(formatCost(cost))
            append(" · ")
            append(formatTokens(tokens))
            append(" tokens")
            topModel?.let { append(" · Top 模型 ").append(it) }
        }
        if (!post(context, CHANNEL_SUMMARY, NOTIFY_SUMMARY, "今日用量摘要", text)) return
        p.edit().putString(KEY_SUMMARY_LAST_DAY, today).apply()
    }

    private fun post(context: Context, channel: String, id: Int, title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        ensureChannels(context)
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_logo_v)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.getOrDefault(false)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dayStamp(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

/** 每 6 小时检查已接入供应商额度，低于阈值时提醒。 */
class QuotaAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!InsightsNotify.quotaEnabled(ctx)) return Result.success()
        FxRates.init(ctx)
        val threshold = InsightsNotify.quotaThreshold(ctx)
        val quotaPrefs = ctx.getSharedPreferences(InsightsNotify.QUOTA_PREFS, Context.MODE_PRIVATE)
        // 各供应商的 fetch 都是 OkHttp 同步调用：必须切到 IO，别占着
        // CoroutineWorker 默认的 Dispatchers.Default 线程。
        val (checked, failed) = withContext(Dispatchers.IO) {
            var checkedCount = 0
            var failedCount = 0
            for (provider in ExtraProvider.entries) {
                // 空字符串视为未接入：手动清空输入框后不能拿空凭据去打接口
                val creds = provider.credPrefsKeys.mapNotNull {
                    quotaPrefs.getString(it, null)?.takeIf { v -> v.isNotBlank() }
                }
                // 未接入的平台直接跳过，不触发网络
                if (creds.size != provider.credPrefsKeys.size) continue
                checkedCount++
                val usage = runCatching {
                    provider.fetch(creds) { renewed ->
                        renewed.forEachIndexed { i, value ->
                            quotaPrefs.edit().putString(provider.credPrefsKeys[i], value).apply()
                        }
                    }
                }.getOrNull()
                if (usage == null) {
                    failedCount++
                    continue
                }
                val minRemaining = usage.groups.values.flatten()
                    .minOfOrNull { it.percentRemaining } ?: continue
                if (minRemaining <= threshold) {
                    InsightsNotify.notifyQuotaLow(ctx, provider.id, provider.title, minRemaining)
                }
            }
            checkedCount to failedCount
        }
        // 全部平台都失败（多半是断网）：留给 WorkManager 重试，别把这次检查吞掉
        return if (checked > 0 && failed == checked && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

/** 每 24 小时推送今日用量摘要。 */
class SummaryNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!InsightsNotify.summaryEnabled(ctx)) return Result.success()
        FxRates.init(ctx)
        FxRates.refreshIfStale(ctx)
        val key = ApiKeyStore.get(ctx)
        if (key.isEmpty()) return Result.success()
        val resp = UsageRepository().fetchTodayData(key).getOrNull()
            ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
        val devices = ctx.getSharedPreferences(InsightsNotify.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(InsightsNotify.KEY_DEVICES, null)?.toSet() ?: emptySet()
        val stats = StatsEngine.computeStats(resp, TimeRange.TODAY, devices)
        val topModel = StatsEngine.computeModelCosts(resp, TimeRange.TODAY, devices)
            .firstOrNull()?.model
        InsightsNotify.notifyDailySummary(ctx, stats.totalCost, stats.totalTokens, topModel)
        return Result.success()
    }
}
