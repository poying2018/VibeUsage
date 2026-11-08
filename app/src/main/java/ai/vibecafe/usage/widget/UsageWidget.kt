package ai.vibecafe.usage.widget

import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.data.FxRates
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun read(context: Context): WidgetSnapshot? = readFrom(file(context))

    fun write(context: Context, snapshot: WidgetSnapshot) = writeTo(file(context), snapshot)

    /**
     * 读快照（纯文件实现，便于单测）。
     *
     * 解析失败只可能是撞上了写方的非原子退路（极少见）：
     * 隔 30ms 再读一次，避免一次抖动就把桌面打成「—」/「等待同步」。
     */
    internal fun readFrom(f: File): WidgetSnapshot? {
        if (!f.exists()) return null
        repeat(2) { attempt ->
            val text = runCatching { f.readText() }.getOrNull() ?: return null
            val parsed = runCatching { gson.fromJson(text, WidgetSnapshot::class.java) }.getOrNull()
            if (parsed != null) return parsed
            if (attempt == 0) {
                try {
                    Thread.sleep(30)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return null
    }

    /**
     * 写快照（纯文件实现，便于单测）。
     *
     * 原子写：先写临时文件、fsync 落盘，再改名。
     * POSIX 的 rename 覆盖是原子的 —— 读方要么看到旧文件、要么看到完整的新文件，
     * 不会读到半截 JSON（解析失败会被吞成 null，桌面就退化成「—」/「等待同步」）。
     */
    internal fun writeTo(f: File, snapshot: WidgetSnapshot) {
        runCatching {
            val payload = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                // 极少数文件系统上「rename 覆盖已存在的目标」会失败。
                // 原来的退路是 f.writeText(...) 直接覆写目标 —— 那是**非原子**的，
                // 读方正好撞上就会读到半截 JSON。改成先删目标再改名。
                f.delete()
                if (!tmp.renameTo(f)) {
                    // 还是不行才直接写目标；内容是一整段字节，比逐字符写安全得多
                    java.io.FileOutputStream(f).use { it.write(payload) }
                    tmp.delete()
                }
            }
        }
    }
}

/** 后台同步：拉 days=1 今日数据，落盘快照并刷新小组件。 */
class UsageWidgetSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        FxRates.init(context)
        FxRates.refreshIfStale(context)
        val key = ApiKeyStore.get(context)
        if (key.isEmpty()) return Result.success()
        // 只要今天的数据：原来这里调 fetchDailyData（days=3650 全量历史），
        // 每 30 分钟下载一次账号全部历史只为显示一行「今日消耗」
        val resp = UsageRepository().fetchTodayData(key).getOrNull()
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
                PeriodicWorkRequestBuilder<UsageWidgetSyncWorker>(30, TimeUnit.MINUTES)
                    // 没网就别跑：离线执行必然失败，还白耗一次唤醒
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
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

    /**
     * 用 App **已经拿到**的响应直接刷新小组件快照，不再发第二次请求。
     *
     * App 主请求是 `days=3650`（含今天），而小组件原本为了显示「今日消耗」
     * 还要单独拉一次 `days=1` —— 那是主响应的子集，纯属重复请求：
     * 每打开一次 App 就多打一次网络，弱网下还平白多一份失败率。
     *
     * 直接从已有响应里算今天：省掉一次请求，且小组件数字和 App 里看到的
     * 必然一致（同一个响应算出来的，不会出现两边数字对不上）。
     *
     * 注意算「今日」要**扫全量历史**（响应里是 3650 天的数据），
     * 所以必须放后台线程 —— 放主线程就是一次可观的 UI 线程停顿。
     */
    suspend fun publishFromResponse(context: Context, resp: ai.vibecafe.usage.data.UsageResponse) {
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            val stats = ai.vibecafe.usage.stats.StatsEngine.computeStats(
                resp,
                ai.vibecafe.usage.stats.TimeRange.TODAY
            )
            val snapshot = WidgetSnapshot(
                costToday = stats.totalCost,
                tokensToday = stats.totalTokens,
                updatedAt = System.currentTimeMillis()
            )
            runCatching {
                UsageWidgetCache.write(context, snapshot)
                UsageWidget().updateAll(context)
            }
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
        FxRates.init(context)
        val snapshot = UsageWidgetCache.read(context)
        provideContent { Content(context, snapshot) }
    }

    @Composable
    private fun Content(context: Context, snapshot: WidgetSnapshot?) {
        // 远程视图无法运行 RuntimeShader 真实折射，改用暗色玻璃设计令牌：
        // Rim 描边 + 深夜蓝底 + 同款强调色，视觉与 App 内玻璃卡一致。
        // 点击整块组件直接打开 App（Glance actionStartActivity 携带 Intent）
        val launchIntent = android.content.Intent(context, ai.vibecafe.usage.MainActivity::class.java)
        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(DarkGlass.Rim))
                    .cornerRadius(24.dp)
                    .clickable(actionStartActivity(launchIntent))
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
                        // 快照是昨天的就明确标出来：连续同步失败时，
                        // 用户不能把隔天的数字当成今天的
                        val stale = snapshot != null && snapshot.updatedAt > 0 &&
                            !isSameDay(snapshot.updatedAt, System.currentTimeMillis())
                        Text(
                            if (stale) "上次消耗" else "今日消耗",
                            style = TextStyle(color = ColorProvider(DarkGlass.InkMid), fontSize = 11.sp)
                        )
                        // 没有快照时显示占位符而不是 $0.00：
                        // 「真的是 0」和「还没同步过」是两回事，不能混为一谈
                        Text(
                            if (snapshot == null) "—" else formatCost(snapshot.costToday),
                            style = TextStyle(
                                color = ColorProvider(DarkGlass.Accent),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            if (snapshot == null) "等待同步" else formatTokens(snapshot.tokensToday) + " tokens",
                            style = TextStyle(color = ColorProvider(DarkGlass.InkMid), fontSize = 11.sp)
                        )
                        if (snapshot != null && snapshot.updatedAt > 0) {
                            // 同步时间带日期：昨天的数据要能看出来，
                            // 否则用户会把隔天的数字当成今天的
                            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
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

/**
 * 两个时间戳是否同一天（按**北京时间**）。
 *
 * 必须和统计引擎同口径：快照里的 costToday 是「北京时间今天」的消耗
 * （StatsEngine.computeStats(resp, TimeRange.TODAY) 按 Asia/Shanghai 分日）。
 * 如果这里按设备本地时区判断，UTC+13 的用户在本地跨过零点、
 * 而北京还没跨日的那 8 小时里，刚同步的「北京昨天」的数据会被标成「今日消耗」——
 * 数字和口径对不上。
 */
internal fun isSameDay(t1: Long, t2: Long): Boolean {
    val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    }
    return f.format(Date(t1)) == f.format(Date(t2))
}
