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
 * 超过 [BUDGET_USD] 时发一条本地通知（纯本地，无服务端）。
 */
object BudgetNotify {
    /** 月度预算阈值（USD）。超支提醒的判定线，后续可做成设置项。 */
    const val BUDGET_USD = 500.0

    private const val CHANNEL_ID = "budget_alert"
    private const val NOTIFICATION_ID = 41
    private const val WORK_NAME = "budget-daily-check"

    /** App 启动时注册每日一次的预算核对（KEEP 幂等）。 */
    fun ensureScheduled(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BudgetCheckWorker>(24, TimeUnit.HOURS).build()
            )
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "月度预算提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "预测整月消耗超过预算时提醒" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun notifyIfNeeded(context: Context, projected: Double) {
        if (projected < BUDGET_USD) return
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
            .setContentText("按当前日均，整月预计 " + formatCost(projected) + "（预算 " + formatCost(BUDGET_USD) + "）")
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
        val resp = UsageRepository().fetchDailyData(key).getOrNull() ?: return Result.success()
        val projection = StatsEngine.computeMonthProjection(resp) ?: return Result.success()
        BudgetNotify.notifyIfNeeded(context, projection.projected)
        return Result.success()
    }
}
