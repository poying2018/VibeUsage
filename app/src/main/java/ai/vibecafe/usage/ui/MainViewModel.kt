package ai.vibecafe.usage.ui

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import ai.vibecafe.usage.data.UsageRepository
import ai.vibecafe.usage.data.UsageResponse
import ai.vibecafe.usage.stats.DashboardStats
import ai.vibecafe.usage.stats.DailyUsage
import ai.vibecafe.usage.stats.DisplaySession
import ai.vibecafe.usage.stats.ModelCost
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.stats.ToolDetail
import ai.vibecafe.usage.stats.ToolDistribution
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val toolDetail: ToolDetail? = null
)

class MainViewModel : ViewModel() {
    private val repository = UsageRepository()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun dataForRange(range: TimeRange): UsageResponse? =
        if (range == TimeRange.HOURS_24) _uiState.value.hourlyData else _uiState.value.dailyData

    fun loadData(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val dailyResult = repository.fetchDailyData(apiKey)
            val hourlyResult = repository.fetchHourlyData(apiKey)

            val daily = dailyResult.getOrNull()?.let { StatsEngine.dedupResponse(it) }
            val hourly = hourlyResult.getOrNull()?.let { StatsEngine.dedupResponse(it) }
            if (daily != null) {
                val devices = StatsEngine.availableDevices(daily)
                val tr = _uiState.value.selectedTimeRange
                val data = if (tr == TimeRange.HOURS_24) hourly ?: daily else daily
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null,
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
        _uiState.value = _uiState.value.copy(
            selectedTimeRange = range,
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

    private fun mapError(t: Throwable?): String = when (t) {
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.UnknownHostException -> "无法连接服务器"
        is java.net.ConnectException -> "连接失败"
        is javax.net.ssl.SSLException -> "安全连接失败"
        is java.io.IOException -> "网络错误"
        else -> "加载失败"
    }
}
