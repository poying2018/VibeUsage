package ai.vibecafe.usage.ui

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

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
    val granularityNote: String? = null
)

class MainViewModel : ViewModel() {
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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, granularityNote = null)

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