package ai.vibecafe.usage.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.vibecafe.usage.BuildConfig
import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.data.UpdateChecker
import ai.vibecafe.usage.data.UsageRepository
import ai.vibecafe.usage.data.UsageResponse
import ai.vibecafe.usage.stats.DashboardStats
import ai.vibecafe.usage.stats.DailyUsage
import ai.vibecafe.usage.stats.DisplaySession
import ai.vibecafe.usage.stats.ModelCost
import ai.vibecafe.usage.stats.ModelDetail
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.stats.ToolDetail
import ai.vibecafe.usage.stats.ToolDistribution
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.io.File

/** 检查更新流程的所处阶段。 */
enum class UpdateStatus { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, DOWNLOADING, DOWNLOADED, FAILED }

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
    val stats: DashboardStats? = null,
    val toolDistribution: List<ToolDistribution> = emptyList(),
    val modelCosts: List<ModelCost> = emptyList(),
    val dailyUsage: List<DailyUsage> = emptyList(),
    val hourlyUsage: List<DailyUsage> = emptyList(),
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
        return s.dailyData
    }

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

            // 并行请求 daily + hourly，速度提升约 1 倍
            val dailyDeferred = async { repository.fetchDailyData(apiKey) }
            val hourlyDeferred = async { repository.fetchHourlyData(apiKey) }

            val dailyResult = dailyDeferred.await()
            val hourlyResult = hourlyDeferred.await()

            val daily = dailyResult.getOrNull()?.let { StatsEngine.dedupResponse(it) }
            val hourly = hourlyResult.getOrNull()?.let { StatsEngine.dedupResponse(it) }

            if (daily != null) {
                val devices = StatsEngine.availableDevices(daily)
                val tr = _uiState.value.selectedTimeRange
                val data = if (tr == TimeRange.HOURS_24) hourly ?: daily else daily

                // 检测降级情况
                val note = if (tr == TimeRange.HOURS_24 && hourly == null) {
                    "24 小时粒度数据暂不可用，已自动降级为日粒度显示"
                } else null

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                    granularityNote = note,
                    dailyData = daily,
                    hourlyData = hourly,
                    availableDevices = devices,
                    stats = StatsEngine.computeStats(data, tr),
                    toolDistribution = StatsEngine.computeToolDistribution(data, tr),
                    modelCosts = StatsEngine.computeModelCosts(data, tr),
                    dailyUsage = StatsEngine.computeDailyUsage(data, tr),
                    hourlyUsage = StatsEngine.computeHourlyUsage(hourly ?: daily),
                    sessions = StatsEngine.computeDisplaySessions(data, tr)
                )
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
            granularityNote = note,
            stats = StatsEngine.computeStats(data, range, _uiState.value.selectedDevices),
            toolDistribution = StatsEngine.computeToolDistribution(data, range),
            modelCosts = StatsEngine.computeModelCosts(data, range),
            dailyUsage = StatsEngine.computeDailyUsage(data, range),
            sessions = StatsEngine.computeDisplaySessions(data, range)
        )
    }

    fun setDevices(devices: Set<String>) {
        val data = dataForRange(_uiState.value.selectedTimeRange) ?: return
        val tr = _uiState.value.selectedTimeRange
        _uiState.value = _uiState.value.copy(
            selectedDevices = devices,
            stats = StatsEngine.computeStats(data, tr, devices)
        )
    }

    /** 长按应用行：计算并展示该应用在当前时间范围的细分详情。 */
    fun showToolDetail(tool: String) {
        val data = dataForRange(_uiState.value.selectedTimeRange) ?: return
        val tr = _uiState.value.selectedTimeRange
        _uiState.value = _uiState.value.copy(
            toolDetail = StatsEngine.computeToolDetail(data, tr, tool, _uiState.value.selectedDevices)
        )
    }

    fun hideToolDetail() {
        if (_uiState.value.toolDetail != null) {
            _uiState.value = _uiState.value.copy(toolDetail = null)
        }
    }

    /** 长按模型行：计算并展示该模型在当前时间范围的细分详情（含缓存命中率）。 */
    fun showModelDetail(model: String) {
        val data = dataForRange(_uiState.value.selectedTimeRange) ?: return
        val tr = _uiState.value.selectedTimeRange
        _uiState.value = _uiState.value.copy(
            modelDetail = StatsEngine.computeModelDetail(data, tr, model, _uiState.value.selectedDevices)
        )
    }

    fun hideModelDetail() {
        if (_uiState.value.modelDetail != null) {
            _uiState.value = _uiState.value.copy(modelDetail = null)
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
            val release = UpdateChecker.fetchLatestRelease()
            if (release == null) {
                setUpdate { it.copy(status = UpdateStatus.FAILED, message = "检查更新失败，请检查网络后重试") }
                return@launch
            }
            val tag = release.tagName ?: release.name ?: run {
                setUpdate { it.copy(status = UpdateStatus.FAILED, message = "没有找到版本信息") }
                return@launch
            }
            val apk = release.assets.firstOrNull {
                it.browserDownloadUrl != null && it.name?.lowercase()?.endsWith(".apk") == true
            }
            if (apk == null) {
                setUpdate { it.copy(status = UpdateStatus.UP_TO_DATE, message = "当前已是最新版本 (v" + BuildConfig.VERSION_NAME + ")") }
            } else if (UpdateChecker.isNewer(tag, BuildConfig.VERSION_NAME)) {
                setUpdate {
                    it.copy(
                        status = UpdateStatus.AVAILABLE,
                        version = tag,
                        apkUrl = apk.browserDownloadUrl,
                        message = null
                    )
                }
            } else {
                setUpdate { it.copy(status = UpdateStatus.UP_TO_DATE, message = "当前已是最新版本 (v" + BuildConfig.VERSION_NAME + ")") }
            }
        }
    }

    /** 下载更新 APK（仅当已发现新版本）。 */
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
            if (ok && file.length() > 0) {
                setUpdate { it.copy(status = UpdateStatus.DOWNLOADED, progress = 1f) }
            } else {
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