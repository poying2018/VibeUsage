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
 * 「额度」页凭据型供应商（MiniMax / GLM / Kimi / DeepSeek / 无问芯穹 / 百炼 / 方舟）的额度 ViewModel。
 * 凭据粘贴一次即持久化到 quota_extra prefs（火山方舟为 AK/SK 双字段）。
 */
class ExtraQuotaViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<Map<String, ProviderState>>(emptyMap())
    val state: StateFlow<Map<String, ProviderState>> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("quota_extra", Application.MODE_PRIVATE)

    init {
        // 清理 v2.10.2 及之前残留的 Codex/Claude 凭据（两平台已移除）
        prefs.edit()
            .remove("codex_auth_raw").remove("codex_access").remove("codex_access_exp")
            .remove("claude_creds_raw").remove("claude_access").remove("claude_access_exp")
            .apply()
        ExtraProvider.entries.forEach { p ->
            if (p.credPrefsKeys.all { prefs.getString(it, null) != null }) {
                _state.value = _state.value.toMutableMap().apply { put(p.id, ProviderState(loggedIn = true)) }
                refresh(p.id)
            }
        }
    }

    // ─── 登录（粘贴凭据）───

    fun login(id: String, creds: List<String>) {
        val provider = ExtraProvider.byId(id) ?: return
        val cleaned = creds.map { it.filter { c -> c in '!'..'~' } }
        if (cleaned.any { it.length < 16 }) {
            update(id) { it.copy(error = "凭据格式不对（长度不足，请粘贴完整密钥）") }
            return
        }
        prefs.edit().apply {
            provider.credPrefsKeys.forEachIndexed { i, k -> putString(k, cleaned[i]) }
        }.apply()
        update(id) { ProviderState(loggedIn = true, isLoading = true) }
        refresh(id)
    }

    // ─── 刷新 ───

    fun refresh(id: String) = viewModelScope.launch {
        val provider = ExtraProvider.byId(id) ?: return@launch
        update(id) { it.copy(isLoading = true, error = null) }
        try {
            val creds = provider.credPrefsKeys.map { k ->
                prefs.getString(k, null) ?: throw ExtraQuotaApi.QuotaException(0, "凭据缺失，请重新接入")
            }
            val usage = withContext(Dispatchers.IO) { provider.fetch(creds) }
            update(id) {
                it.copy(
                    isLoading = false,
                    account = usage.account,
                    groups = usage.groups,
                    error = if (usage.groups.isEmpty()) "计划未返回额度数据" else null
                )
            }
        } catch (e: Exception) {
            update(id) { it.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}") }
        }
    }

    // ─── 解绑 ───

    fun logout(id: String) {
        val provider = ExtraProvider.byId(id) ?: return
        prefs.edit().apply {
            provider.credPrefsKeys.forEach { remove(it) }
        }.apply()
        _state.value = _state.value.toMutableMap().apply { put(id, ProviderState()) }
    }

    fun clearError(id: String) = update(id) { it.copy(error = null) }

    private fun update(id: String, f: (ProviderState) -> ProviderState) {
        _state.value = _state.value.toMutableMap().apply {
            put(id, f(get(id) ?: ProviderState()))
        }
    }
}
