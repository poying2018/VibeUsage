package ai.vibecafe.usage.ui.ag

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.quota.ExtraQuotaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单个 provider 的展示状态。 */
data class ProviderState(
    val loggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val account: String? = null,
    val groups: Map<String, List<ExtraQuotaApi.Bar>> = emptyMap(),
    val error: String? = null
)

/**
 * MiniMax 额度 ViewModel。
 * 凭据来源（粘贴一次即持久化）：Token Plan API Key（sk-cp-...）。
 */
class ExtraQuotaViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ProviderState())
    val state: StateFlow<ProviderState> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("quota_extra", Application.MODE_PRIVATE)

    init {
        // 清理历史版本（v2.10.2 及之前）残留的 Codex/Claude 凭据
        prefs.edit()
            .remove("codex_auth_raw").remove("codex_access").remove("codex_access_exp")
            .remove("claude_creds_raw").remove("claude_access").remove("claude_access_exp")
            .apply()
        if (prefs.getString("minimax_key", null) != null) {
            _state.value = ProviderState(loggedIn = true)
            refresh()
        }
    }

    // ─── 登录（粘贴凭据）───

    fun login(key: String) {
        val k = key.filter { it in '!'..'~' }
        if (k.length < 20) {
            _state.value = _state.value.copy(error = "API Key 格式不对（应以 sk- 开头）")
            return
        }
        prefs.edit().putString("minimax_key", k).apply()
        _state.value = ProviderState(loggedIn = true, isLoading = true)
        refresh()
    }

    // ─── 刷新 ───

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(isLoading = true, error = null)
        try {
            val key = prefs.getString("minimax_key", null) ?: return@launch
            val usage = withContext(Dispatchers.IO) { ExtraQuotaApi.MiniMax.fetchUsage(key) }
            _state.value = _state.value.copy(
                isLoading = false,
                groups = usage.groups,
                account = null,
                error = if (usage.groups.isEmpty()) "计划未返回额度数据" else null
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}")
        }
    }

    // ─── 解绑 ───

    fun logout() {
        prefs.edit().remove("minimax_key").apply()
        _state.value = ProviderState()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
