package ai.vibecafe.usage.ui.ds

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.ds.DsApiClient
import ai.vibecafe.usage.data.ds.DsPanelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DS+ Milky 风格面板 ViewModel。
 * 使用 DeepSeek 官方 API Key 查询余额（api.deepseek.com/user/balance），无需平台账号登录。
 */
class DsPanelViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DsPanelState())
    val state: StateFlow<DsPanelState> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("ds_panel", Application.MODE_PRIVATE)

    init {
        val savedKey = prefs.getString("api_key", null)
        if (!savedKey.isNullOrBlank()) {
            _state.value = _state.value.copy(isLoggedIn = true, apiKey = savedKey)
            loadBalance()
        }
    }

    /** 用 DeepSeek API Key 登录（实际是验证 Key 并查询余额）。 */
    fun login(apiKey: String) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            _state.value = _state.value.copy(error = "请输入 API Key")
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = DsApiClient.Official.api.getBalance("Bearer $key")
                val info = resp.balanceInfos?.firstOrNull()
                if (resp.isAvailable == false) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "账户不可用"
                    )
                    return@launch
                }
                if (info == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "未获取到余额信息，请检查 API Key"
                    )
                    return@launch
                }
                prefs.edit().putString("api_key", key).apply()
                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    apiKey = key,
                    balance = info.totalBalance,
                    currency = info.currency,
                    grantedBalance = info.grantedBalance,
                    toppedUpBalance = info.toppedUpBalance,
                    isAvailable = resp.isAvailable,
                    error = null
                )
                loadModels()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "查询失败：${e.localizedMessage ?: "未知错误"}（API Key 可能无效）"
                )
            }
        }
    }

    /** 加载可用模型列表。 */
    fun loadModels() {
        val key = _state.value.apiKey ?: return
        viewModelScope.launch {
            try {
                val resp = DsApiClient.Official.api.getModels("Bearer $key")
                if (!resp.data.isNullOrEmpty()) {
                    _state.value = _state.value.copy(models = resp.data)
                }
            } catch (_: Exception) {
                // 模型列表获取失败不影响余额展示
            }
        }
    }

    /** 重新加载余额。 */
    fun loadBalance() {
        val key = _state.value.apiKey ?: return
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = DsApiClient.Official.api.getBalance("Bearer $key")
                val info = resp.balanceInfos?.firstOrNull()
                if (info == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "未获取到余额信息"
                    )
                    return@launch
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    balance = info.totalBalance,
                    currency = info.currency,
                    grantedBalance = info.grantedBalance,
                    toppedUpBalance = info.toppedUpBalance,
                    isAvailable = resp.isAvailable,
                    error = null
                )
                loadModels()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "查询失败：${e.localizedMessage ?: "未知错误"}"
                )
            }
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        _state.value = DsPanelState()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}