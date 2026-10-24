package ai.vibecafe.usage.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import ai.vibecafe.usage.BuildConfig
import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.data.UpdateChecker
import ai.vibecafe.usage.data.UsageRepository
import kotlinx.coroutines.delay
import ai.vibecafe.usage.data.UsageResponse
import ai.vibecafe.usage.stats.CustomRange
import ai.vibecafe.usage.stats.DashboardStats
import ai.vibecafe.usage.stats.DailyUsage
import ai.vibecafe.usage.stats.DisplaySession
import ai.vibecafe.usage.stats.ModelCost
import ai.vibecafe.usage.stats.ModelDetail
import ai.vibecafe.usage.stats.MonthProjection
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.stats.ToolDetail
import ai.vibecafe.usage.stats.ToolDistribution
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.io.File

/** 检查更新流程的所处阶段。 */
enum class UpdateStatus { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, DOWNLOADING, DOWNLOADED, FAILED, NO_APK }

/** 检查更新 / 下载安装的界面状态。 */
data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val version: String? = null,
    val apkUrl: String? = null,
    val progress: Float = 0f,
    val message: String? = null
)

data class UiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val dailyData: UsageResponse? = null,
    val hourlyData: UsageResponse? = null,
    /** 自定义范围的独立数据源（仅在 selectedTimeRange == CUSTOM 时使用）。 */
    val customData: UsageResponse? = null,
    val customRange: CustomRange? = null,
    /** customData 对应的范围：换了范围但新数据还没到时，旧 customData 不能被误用。 */
    val customDataRange: CustomRange? = null,
    val stats: DashboardStats? = null,
    val trendPercent: Float? = null,
    val monthProjection: MonthProjection? = null,
    val toolDistribution: List<ToolDistribution> = emptyList(),
    val modelCosts: List<ModelCost> = emptyList(),
    val dailyUsage: List<DailyUsage> = emptyList(),
    val hourlyUsage: List<DailyUsage> = emptyList(),
    val todayHourlyUsage: List<DailyUsage> = emptyList(),
    val sessions: List<DisplaySession> = emptyList(),
    val availableDevices: List<String> = emptyList(),
    val selectedTimeRange: TimeRange = TimeRange.DAYS_7,
    val selectedDevices: Set<String> = emptySet(),
    val toolDetail: ToolDetail? = null,
    val modelDetail: ModelDetail? = null,
    /** HOURS_24 时若 hourlyData 不可用，显示降级提示。 */
    val granularityNote: String? = null,
    /** 更新检查 / 下载进度。 */
    val update: UpdateState = UpdateState()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UsageRepository()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun dataForRange(range: TimeRange): UsageResponse? {
        val s = _uiState.value
        if (range == TimeRange.HOURS_24) {
            // hourlyData null 时降级为 dailyData，但标记提示
            return s.hourlyData ?: s.dailyData
        }
        if (range == TimeRange.CUSTOM) {
            // customData 必须属于当前这个范围：换了范围但新数据还没拉到时，
            // 拿旧范围的 customData 去算新范围就是错的
            val s2 = _uiState.value
            return if (s2.customDataRange == s2.customRange) s2.customData ?: s2.dailyData
            else s2.dailyData
        }
        return s.dailyData
    }

    private fun customOf(range: TimeRange): CustomRange? =
        if (range == TimeRange.CUSTOM) _uiState.value.customRange else null

    /**
     * 进行中的 recompute 任务。
     *
     * 早先是 fire-and-forget：每次切档都新起一个协程，多个并发时「后完成者覆盖先完成者」，
     * 与调用顺序无关——快速连点时间档时，90 天档可能显示 7 天档的统计。
     * 改为显式持有 Job：新一次 recompute 取消上一次，且写回前校验"当前档位/自定义范围仍是发起时那一个"，
     * 避免过期结果覆盖新状态。
     */
    private var recomputeJob: Job? = null

    /** 按当前范围/设备一次性重算全部派生数据（计算在后台线程，赋值回主线程）。 */
    private fun recompute(range: TimeRange, data: UsageResponse) {
        val s = _uiState.value
        val devices = s.selectedDevices
        val custom = customOf(range)
        val hourlyBase = s.hourlyData ?: s.dailyData
        // 趋势对比与月度预测必须用全量数据：自定义档的 data 只有范围内 bucket，
        // 上一周期窗口和整月消耗都会被截断
        val fullDaily = s.dailyData ?: data
        val trendSource = if (range == TimeRange.HOURS_24) (hourlyBase ?: data) else fullDaily
        // 发起时的上下文快照：写回前用它确认这次结果仍然对应当前选择
        val requestedRange = range
        val requestedCustom = custom
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                Recomputed(
                    stats = StatsEngine.computeStats(data, range, devices, custom),
                    trendPercent = StatsEngine.computeTrendPercent(trendSource, range, custom, devices),
                    toolDistribution = StatsEngine.computeToolDistribution(data, range, devices, custom),
                    modelCosts = StatsEngine.computeModelCosts(data, range, devices, custom),
                    dailyUsage = StatsEngine.computeDailyUsage(data, range, devices, custom),
                    hourlyUsage = StatsEngine.computeHourlyUsage(hourlyBase ?: data, devices),
                    todayHourlyUsage = StatsEngine.computeTodayHourlyUsage(hourlyBase ?: data, devices),
                    sessions = StatsEngine.computeDisplaySessions(data, range, devices, custom),
                    monthProjection = StatsEngine.computeMonthProjection(fullDaily, devices)
                )
            }
            // 期间用户又切了档/改了自定义范围：这次结果已经过期，丢弃
            val cur = _uiState.value
            if (cur.selectedTimeRange != requestedRange) return@launch
            if (requestedRange == TimeRange.CUSTOM && cur.customRange != requestedCustom) return@launch
            _uiState.value = cur.copy(
                stats = r.stats,
                trendPercent = r.trendPercent,
                toolDistribution = r.toolDistribution,
                modelCosts = r.modelCosts,
                dailyUsage = r.dailyUsage,
                hourlyUsage = r.hourlyUsage,
                todayHourlyUsage = r.todayHourlyUsage,
                sessions = r.sessions,
                monthProjection = r.monthProjection
            )
        }
    }

    /** recompute 的批量结果，避免多次 copy 抖动。 */
    private data class Recomputed(
        val stats: DashboardStats,
        val trendPercent: Float?,
        val toolDistribution: List<ToolDistribution>,
        val modelCosts: List<ModelCost>,
        val dailyUsage: List<DailyUsage>,
        val hourlyUsage: List<DailyUsage>,
        val todayHourlyUsage: List<DailyUsage>,
        val sessions: List<DisplaySession>,
        val monthProjection: MonthProjection?
    )

    fun loadData(apiKey: String) {
        viewModelScope.launch {
            // 已有数据时视为「刷新」（保留内容区，仅显示头部进度圈）；首次加载才整页 loading
            val hadData = _uiState.value.stats != null
            _uiState.value = _uiState.value.copy(
                isLoading = !hadData,
                isRefreshing = hadData,
                error = null,
                granularityNote = null
            )

            val startTime = System.currentTimeMillis()

            // 并行请求 daily + hourly，速度提升约 1 倍
            val dailyDeferred = async { repository.fetchDailyData(apiKey) }
            val hourlyDeferred = async { repository.fetchHourlyData(apiKey) }
            
            // ...获取结果
            val dailyResult = dailyDeferred.await()
            val hourlyResult = hourlyDeferred.await()

            // 防止网络太快导致骨架屏闪烁
            if (!hadData) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 400L) delay(400L - elapsed)
            }

            val daily = dailyResult.getOrNull()?.let { StatsEngine.dedupResponse(it) }
            val hourly = hourlyResult.getOrNull()?.let { StatsEngine.dedupResponse(it) }

            if (daily != null) {
                val tr = _uiState.value.selectedTimeRange

                // 自定义范围：顺带刷新自定义数据源（失败保留旧值，但归属标记不变）
                var custom = _uiState.value.customData
                var customRangeOf = _uiState.value.customDataRange
                val cr = _uiState.value.customRange
                if (tr == TimeRange.CUSTOM && cr != null) {
                    repository.fetchCustomRange(apiKey, cr.from, cr.to).getOrNull()
                        ?.let {
                            custom = StatsEngine.dedupResponse(it)
                            customRangeOf = cr
                        }
                }

                // hourly 拉取失败时保留上一次的好数据：一次网络抖动不该把已有的
                // 小时粒度数据清成 null（会让 24 小时档降级、今日趋势退化成单点）。
                val effectiveHourly = hourly ?: _uiState.value.hourlyData
                val note = if (tr == TimeRange.HOURS_24 && effectiveHourly == null) {
                    "24 小时粒度数据暂不可用，已自动降级为日粒度显示"
                } else null

                val devices = StatsEngine.availableDevices(daily)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                    granularityNote = note,
                    dailyData = daily,
                    hourlyData = effectiveHourly,
                    customData = custom,
                    customDataRange = customRangeOf,
                    availableDevices = devices,
                    // 设备筛选要跟着本账号的设备列表走：
                    // 换账号后旧账号的 hostname 一个都不在新列表里，
                    // 留着筛选 = 所有数据被过滤成空，页面全白，
                    // 而设备按钮（只在多设备时显示）又打不开，用户没法自救。
                    selectedDevices = _uiState.value.selectedDevices intersect devices.toSet()
                )
                recompute(tr, dataForRange(tr) ?: daily)
                // 桌面小组件数据跟着这次刷新走（免等 30 分钟周期任务）
                ai.vibecafe.usage.widget.UsageWidgetSync.refreshNow(getApplication())
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = mapError(dailyResult.exceptionOrNull())
                )
            }
        }
    }

    fun setTimeRange(range: TimeRange) {
        val data = dataForRange(range) ?: return
        val note = if (range == TimeRange.HOURS_24 && _uiState.value.hourlyData == null) {
            "24 小时粒度数据暂不可用，已自动降级为日粒度显示"
        } else null
        _uiState.value = _uiState.value.copy(
            selectedTimeRange = range,
            granularityNote = note
        )
        recompute(range, data)
    }

    /** 选择自定义日期范围：先用全量数据本地过滤出结果，再拉取范围数据更精准。 */
    fun setCustomRange(from: String, to: String) {
        val apiKey = ApiKeyStore.get(getApplication())
        val range = CustomRange(from, to)
        _uiState.value = _uiState.value.copy(
            selectedTimeRange = TimeRange.CUSTOM,
            customRange = range,
            granularityNote = null
        )
        // 用**全量** dailyData 本地过滤，而不是上一次 customData：
        // customData 是上一个自定义范围的响应，直接拿来算会把旧范围的数据挂在新范围的标签下。
        _uiState.value.dailyData?.let { recompute(TimeRange.CUSTOM, it) }
        if (apiKey.isEmpty()) return
        viewModelScope.launch {
            repository.fetchCustomRange(apiKey, from, to).onSuccess { resp ->
                val deduped = StatsEngine.dedupResponse(resp)
                // 期间用户可能又换了范围：只在范围仍是这次请求的那个时才写回
                if (_uiState.value.customRange == range) {
                    _uiState.value = _uiState.value.copy(customData = deduped, customDataRange = range)
                    recompute(TimeRange.CUSTOM, deduped)
                }
            }
        }
    }

    fun setDevices(devices: Set<String>) {
        val tr = _uiState.value.selectedTimeRange
        val data = dataForRange(tr) ?: return
        _uiState.value = _uiState.value.copy(selectedDevices = devices)
        recompute(tr, data)
    }

    /**
     * 退出登录 / 换账号时清掉上一个账号的内存数据。
     *
     * ViewModel 跟着 Activity 活着，退出登录只是把 API Key 抹了，
     * 上一个账号的用量明细、趋势、设备筛选还留在内存里：
     * - 隐私：新账号登录前那一屏仍能翻到旧账号的明细；
     * - 正确性：设备筛选是按 hostname 存的，旧账号的 hostname 在新账号里不存在，
     *   留着筛选会把所有数据过滤成空，页面全白。
     */
    fun clearAccountData() {
        recomputeJob?.cancel()
        _uiState.value = UiState()
    }

    /**
     * 把当前用量卡存进系统相册。
     *
     * 放在 ViewModel 里而不是 Composable 的 `rememberCoroutineScope()`：
     * 组合作用域的 job 在离开页面时就被取消 —— 用户点了「保存」后立刻切到别的 Tab，
     * 协程被取消，回调不执行，**一条 Toast 都没有**，但图可能已经存进相册了，
     * 用户会以为没保存。viewModelScope 跟着 Activity 活着，结果一定回得来。
     */
    fun saveShareCardToGallery() {
        val app = getApplication<Application>()
        val payload = buildSharePayload(_uiState.value)
        if (payload == null) {
            Toast.makeText(app, "还没有用量数据，先刷新一下", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val generated = ai.vibecafe.usage.share.ShareCard.generate(app, payload)
                    ai.vibecafe.usage.share.ShareCard.saveToGallery(app, generated)
                }
            }
            result.onSuccess { saved ->
                Toast.makeText(
                    app,
                    if (saved.inGallery) {
                        "已保存到系统相册「图库 / Pictures / VibeUsage」"
                    } else {
                        "已保存到应用目录（系统相册不可见）：${saved.path}"
                    },
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Toast.makeText(app, "保存失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 长按应用行：计算并展示该应用在当前时间范围的细分详情。 */
    fun showToolDetail(tool: String) {
        val tr = _uiState.value.selectedTimeRange
        val data = dataForRange(tr) ?: return
        val devices = _uiState.value.selectedDevices
        val custom = customOf(tr)
        // 细分详情要遍历整个数据集，放主线程会让长按手势当场掉帧
        viewModelScope.launch {
            val detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                StatsEngine.computeToolDetail(data, tr, tool, devices, custom)
            }
            if (_uiState.value.selectedTimeRange == tr) {
                _uiState.value = _uiState.value.copy(toolDetail = detail)
            }
        }
    }

    fun hideToolDetail() {
        if (_uiState.value.toolDetail != null) {
            _uiState.value = _uiState.value.copy(toolDetail = null)
        }
    }

    /** 长按模型行：计算并展示该模型在当前时间范围的细分详情（含缓存命中率）。 */
    fun showModelDetail(model: String) {
        val tr = _uiState.value.selectedTimeRange
        val data = dataForRange(tr) ?: return
        val devices = _uiState.value.selectedDevices
        val custom = customOf(tr)
        viewModelScope.launch {
            val detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                StatsEngine.computeModelDetail(data, tr, model, devices, custom)
            }
            if (_uiState.value.selectedTimeRange == tr) {
                _uiState.value = _uiState.value.copy(modelDetail = detail)
            }
        }
    }

    fun hideModelDetail() {
        if (_uiState.value.modelDetail != null) {
            _uiState.value = _uiState.value.copy(modelDetail = null)
        }
    }

    /** 生成玻璃风格总结分享卡并拉起系统分享（IO 线程绘制，主线程仅发起）。 */
    fun shareCard() {
        val s = _uiState.value
        val stats = s.stats
        val app = getApplication<Application>()
        if (stats == null) {
            Toast.makeText(app, "还没有用量数据，先刷新一下", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            runCatching {
                // 载荷口径与「保存到相册」共用同一份 buildSharePayload
                val payload = buildSharePayload(s)
                    ?: error("还没有用量数据，先刷新一下")
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ai.vibecafe.usage.share.ShareCard.generate(app, payload)
                }
                    ai.vibecafe.usage.share.ShareCard.share(app, file)
                }.onFailure {
                    Toast.makeText(app, "分享卡片生成失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // ---------------- 检查更新 / 下载 / 安装 ----------------

    private fun setUpdate(transform: (UpdateState) -> UpdateState) {
        _uiState.value = _uiState.value.copy(update = transform(_uiState.value.update))
    }

    /** 手动「检查更新」。 */
    fun checkUpdate() = maybeCheckUpdate(force = true)

    /** 回到前台时静默检查：仅当尚未查过（IDLE）才触发，避免反复打扰。 */
    fun checkUpdateIfIdle() = maybeCheckUpdate(force = false)

    private fun maybeCheckUpdate(force: Boolean) {
        val st = _uiState.value.update.status
        if (st == UpdateStatus.CHECKING || st == UpdateStatus.DOWNLOADING) return
        if (!force && st != UpdateStatus.IDLE) return
        setUpdate { it.copy(status = UpdateStatus.CHECKING, message = null) }
        viewModelScope.launch {
            // 以 tag 列表为准做语义版本比较，避免 releases/latest 漏掉「只打 tag 未建 Release」的新版本
            val result = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
            when (result.status) {
                UpdateChecker.Status.FAILED -> setUpdate {
                    it.copy(status = UpdateStatus.FAILED, message = result.detail ?: "检查更新失败，请稍后重试")
                }

                UpdateChecker.Status.NO_RELEASE,
                UpdateChecker.Status.UP_TO_DATE -> setUpdate {
                    it.copy(
                        status = UpdateStatus.UP_TO_DATE,
                        message = "当前已是最新版本 (v" + BuildConfig.VERSION_NAME + ")"
                    )
                }

                UpdateChecker.Status.UPDATE_AVAILABLE -> {
                    val url = result.apkUrl
                    if (url == null) {
                        // 有新版本但该版本还没发布带 APK 的 Release
                        setUpdate {
                            it.copy(
                                status = UpdateStatus.NO_APK,
                                version = result.latestVersion,
                                message = "发现新版本 ${result.latestVersion?.removePrefix("v")}，安装包暂未发布"
                            )
                        }
                    } else {
                        setUpdate {
                            it.copy(
                                status = UpdateStatus.AVAILABLE,
                                version = result.latestVersion,
                                apkUrl = url,
                                message = null
                            )
                        }
                    }
                }
            }
        }
    }

    /** 下载更新 APK（仅当已发现新版本）；下载完成后做 SHA-256 校验（远端提供 hash 时）。 */
    fun downloadUpdate() {
        val st = _uiState.value.update
        if (st.status != UpdateStatus.AVAILABLE || st.apkUrl == null) return
        val file = apkFile() ?: run {
            setUpdate { it.copy(status = UpdateStatus.FAILED, message = "无法创建下载目录") }
            return
        }
        setUpdate { it.copy(status = UpdateStatus.DOWNLOADING, progress = 0f, message = null) }
        viewModelScope.launch {
            val ok = UpdateChecker.downloadApk(st.apkUrl, file) { progress ->
                setUpdate { it.copy(status = UpdateStatus.DOWNLOADING, progress = progress) }
            }
            val verified = if (ok) UpdateChecker.verifyDownloadedApk(st.apkUrl, file) else null
            when {
                ok && file.length() > 0 && verified == UpdateChecker.VerifyStatus.VERIFIED ->
                    setUpdate { it.copy(status = UpdateStatus.DOWNLOADED, progress = 1f) }
                ok && file.length() > 0 && verified == UpdateChecker.VerifyStatus.NO_BASELINE ->
                    // 远端没有提供 .sha256 校验基准：无法验证，不放行安装，但给出明确提示
                    setUpdate {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            message = "远端未提供安装包校验基准（.sha256），为安全起见已阻止安装"
                        )
                    }
                ok && file.length() > 0 && verified == UpdateChecker.VerifyStatus.BASELINE_UNREACHABLE ->
                    // 拉校验基准时网络失败：暂时无法验证，同样不放行安装，可重试
                    setUpdate {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            message = "无法获取安装包校验基准（网络异常），已阻止安装，请重试"
                        )
                    }
                ok && file.length() > 0 ->
                    setUpdate { it.copy(status = UpdateStatus.FAILED, message = "安装包校验失败，已阻止安装") }
                else ->
                    setUpdate { it.copy(status = UpdateStatus.FAILED, message = "下载失败，请重试") }
            }
        }
    }

    /** 用系统安装器安装已下载的 APK；缺少「安装未知应用」权限时引导去开启。 */
    fun installUpdate() {
        val app = getApplication<Application>()
        val update = _uiState.value.update
        val file = apkFile() ?: run {
            setUpdate { it.copy(status = UpdateStatus.FAILED, message = "安装包不存在") }
            return
        }
        if (!file.exists()) {
            setUpdate { it.copy(status = UpdateStatus.FAILED, message = "安装包不存在，请重新下载") }
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            setUpdate { it.copy(message = "请允许「安装未知应用」后重试，已为你打开设置") }
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${app.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure {
            setUpdate { it.copy(status = UpdateStatus.FAILED, message = "无法启动安装程序") }
        }
    }

    /** 下载 APK 的目标文件（私有 external files/downloads，缓存目录兜底）。 */
    private fun apkFile(): File? = runCatching {
        val app = getApplication<Application>()
        val dir = app.getExternalFilesDir("downloads") ?: app.cacheDir
        if (!dir.exists()) dir.mkdirs()
        File(dir, UpdateChecker.APK_FILE_NAME)
    }.getOrNull()

    private fun mapError(t: Throwable?): String = when (t) {
        is HttpException -> when (t.code()) {
            401 -> "API Key 无效或已过期，请重新登录"
            403 -> "没有访问权限"
            429 -> "请求过于频繁，请稍后再试"
            in 500..599 -> "服务器错误 (${t.code()})，请稍后再试"
            else -> "请求失败 (${t.code()})"
        }
        is java.net.SocketTimeoutException -> "连接超时，请检查网络"
        is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
        is java.net.ConnectException -> "连接失败，请检查网络"
        is javax.net.ssl.SSLException -> "安全连接失败"
        is java.io.IOException -> "网络错误，请检查连接"
        else -> "加载失败"
    }
}
