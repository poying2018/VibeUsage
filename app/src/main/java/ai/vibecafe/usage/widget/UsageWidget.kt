package ai.vibecafe.usage.widget

import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.data.UsageRepository
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.ui.formatCost
import ai.vibecafe.usage.ui.formatTokens
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 小组件展示的快照数据（后台同步后落盘，渲染时零网络）。 */
data class WidgetSnapshot(
    val costToday: Double = 0.0,
    val tokensToday: Long = 0,
    val updatedAt: Long = 0L
)

object UsageWidgetCache {
    private const val FILE_NAME = "usage_widget_snapshot.json"
    private val gson = Gson()

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun read(context: Context): WidgetSnapshot? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        gson.fromJson(f.readText(), WidgetSnapshot::class.java)
    }.getOrNull()

    fun write(context: Context, snapshot: WidgetSnapshot) {
        runCatching {
            file(context).writeText(gson.toJson(snapshot))
        }
    }
}

/** 后台同步：拉 days=1 数据，落盘今日快照并刷新小组件。 */
class UsageWidgetSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val key = ApiKeyStore.get(context)
        if (key.isEmpty()) return Result.success()
        val resp = UsageRepository().fetchDailyData(key).getOrNull()
            ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()
        val stats = StatsEngine.computeStats(resp, TimeRange.TODAY)
        UsageWidgetCache.write(
            context,
            WidgetSnapshot(costToday = stats.totalCost, tokensToday = stats.totalTokens, updatedAt = System.currentTimeMillis())
        )
        UsageWidget().updateAll(context)
        return Result.success()
    }
}

object UsageWidgetSync {
    private const val PERIODIC_NAME = "usage-widget-periodic"
    private const val ONESHOT_NAME = "usage-widget-oneshot"

    /** App 启动时调用：注册每 30 分钟的后台同步（KEEP 幂等）。 */
    fun ensureScheduled(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageWidgetSyncWorker>(30, TimeUnit.MINUTES).build()
            )
        }
    }

    /** 立即同步一次（小组件 onUpdate 时）。 */
    fun refreshNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UsageWidgetSyncWorker>().build()
            )
        }
    }
}

/** 小组件入口：2x2 今日消耗速览。 */
class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        UsageWidgetSync.refreshNow(context)
    }
}

class UsageWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = UsageWidgetCache.read(context)
        provideContent { Content(snapshot) }
    }

    @Composable
    private fun Content(snapshot: WidgetSnapshot?) {
        // 远程视图无法运行 RuntimeShader 真实折射，改用暗色玻璃设计令牌：
        // Rim 描边 + 深夜蓝底 + 同款强调色，视觉与 App 内玻璃卡一致
        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(DarkGlass.Rim))
                    .cornerRadius(24.dp)
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(1.dp)
                        .background(ColorProvider(DarkGlass.Page))
                        .cornerRadius(23.dp)
                        .padding(14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            "今日消耗",
                            style = TextStyle(color = ColorProvider(DarkGlass.InkMid), fontSize = 11.sp)
                        )
                        Text(
                            "$" + formatCost(snapshot?.costToday ?: 0.0),
                            style = TextStyle(
                                color = ColorProvider(DarkGlass.Accent),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            formatTokens(snapshot?.tokensToday ?: 0L) + " tokens",
                            style = TextStyle(color = ColorProvider(DarkGlass.InkMid), fontSize = 11.sp)
                        )
                        if (snapshot != null && snapshot.updatedAt > 0) {
                            val fmt = SimpleDateFormat("HH:mm", Locale.US)
                            Text(
                                fmt.format(Date(snapshot.updatedAt)) + " 同步",
                                style = TextStyle(color = ColorProvider(DarkGlass.InkLo), fontSize = 10.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 暗色液态玻璃令牌（与 GlassTokens.DarkGlassPalette 逐项一致，小组件无法引用 Compose 调色板故独立声明）。 */
private object DarkGlass {
    val Page = Color(0xFF0B1019)
    val Rim = Color(0x26FFFFFF)
    val Accent = Color(0xFF31E0CF)
    val InkMid = Color(0xB3C7D2E6)
    val InkLo = Color(0x59C7D2E6)
}
