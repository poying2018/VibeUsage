package ai.vibecafe.usage.budget

import ai.vibecafe.usage.MainActivity
import ai.vibecafe.usage.R
import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.data.UsageRepository
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.ui.formatCost
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
import java.util.concurrent.TimeUnit

/**
 * 月度预算提醒：每日核对一次「按日均预测的整月消耗」，
 * 超过当前阈值（默认 500 美元，可在设置页调整）时发一条本地通知（纯本地，无服务端）。
 */
object BudgetNotify {
    /** 默认月度预算阈值（USD）。设置页「恢复默认」回到这个值。 */
    const val DEFAULT_BUDGET_USD = 500.0

    /** 阈值上限（USD）：超过这个值算非法输入。 */
    const val MAX_BUDGET_USD = 1_000_000.0

    private const val CHANNEL_ID = "budget_alert"
    private const val NOTIFICATION_ID = 41
    private const val WORK_NAME = "budget-daily-check"

    /** 阈值与主题/液态玻璃参数共用同一份 prefs。 */
    private const val PREFS_NAME = "vibe_usage"
    private const val KEY_THRESHOLD = "budget_threshold_usd"

    /**
     * 当前预算阈值（USD）。
     *
     * 存在 prefs（vibe_usage / budget_threshold_usd）；非法值（<=0、NaN、超上限）
     * 一律回落到默认 500 —— 阈值被写坏时不能让超支提醒整个哑掉。
     */
    fun threshold(context: Context): Double {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THRESHOLD, null)
        val v = raw?.trim()?.toDoubleOrNull() ?: return DEFAULT_BUDGET_USD
        return sanitize(v)
    }

    /**
     * 写入预算阈值（USD）。
     *
     * 非法值（<=0、NaN、超上限）按默认 500 处理 —— 与读取端同一套口径，
     * 免得 prefs 里落下读不回来的脏值。
     */
    fun setThreshold(context: Context, value: Double) {
        val v = sanitize(value)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THRESHOLD, v.toString())
            .apply()
    }

    /** 用户是否手动设置过阈值（false = 仍在用默认 500）。 */
    fun hasCustomThreshold(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_THRESHOLD)

    /** 恢复默认：清掉自定义值，回到内置的 500。 */
    fun resetThreshold(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_THRESHOLD)
            .apply()
    }

    /** 阈值合法性：非正数 / NaN / 超上限都回落到默认 500。 */
    private fun sanitize(value: Double): Double =
        if (value.isFinite() && value > 0.0 && value <= MAX_BUDGET_USD) value else DEFAULT_BUDGET_USD

    /** App 启动时注册每日一次的预算核对（KEEP 幂等）。 */
    fun ensureScheduled(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BudgetCheckWorker>(24, TimeUnit.HOURS)
                    // 没有网络就别跑：离线时拉数据必然失败，
                    // 一次失败就把当天的检查整个吞掉（要等 24h 才再来）
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    fun ensureChannel(context: Context) {
        // minSdk 26 = O，通知渠道从 API 26 起就是必备项，不需要再判版本
        val channel = NotificationChannel(
            CHANNEL_ID,
            "月度预算提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "预测整月消耗超过预算时提醒" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun notifyIfNeeded(context: Context, projected: Double) {
        // 阈值每次现读：设置页改完立即生效，下一次 Worker 执行就用新值
        val threshold = threshold(context)
        if (projected < threshold) return
        // Android 13+ 通知运行时权限
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_v)
            .setContentTitle("本月消耗预测将超预算")
            .setContentText("按当前日均，整月预计 " + formatCost(projected) + "（预算 " + formatCost(threshold) + "）")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

/** 每日预算核对：拉全量数据算月度预测，超阈值才通知。 */
class BudgetCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val key = ApiKeyStore.get(context)
        if (key.isEmpty()) return Result.success()
        // 网络失败要重试，不能直接 success：直接 success 等于把当天的预算检查整个吞掉，
        // 下一次要等满 24 小时，弱网日会系统性漏发超预算提醒。
        val resp = UsageRepository().fetchDailyData(key).getOrNull()
            ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
        val projection = StatsEngine.computeMonthProjection(resp)
            ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
        // 阈值由 notifyIfNeeded 内部从 prefs 现读，与设置页写入的是同一份
        BudgetNotify.notifyIfNeeded(context, projection.projected)
        return Result.success()
    }
}
