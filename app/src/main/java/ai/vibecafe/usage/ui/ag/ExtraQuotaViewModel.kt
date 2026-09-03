package ai.vibecafe.usage.ui.ag

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.quota.ExtraQuotaApi
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
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

data class ExtraQuotaState(
    val codex: ProviderState = ProviderState(),
    val claude: ProviderState = ProviderState(),
    val minimax: ProviderState = ProviderState()
)

/**
 * Codex / Claude Code / MiniMax 三平台额度 ViewModel。
 * 凭据来源（粘贴一次即持久化）：
 * - Codex: ~/.codex/auth.json（ChatGPT 登录态）
 * - Claude Code: ~/.claude/.credentials.json（OAuth 凭据）
 * - MiniMax: Token Plan API Key（sk-cp-...）
 */
class ExtraQuotaViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ExtraQuotaState())
    val state: StateFlow<ExtraQuotaState> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("quota_extra", Application.MODE_PRIVATE)

    init {
        if (prefs.getString("codex_auth_raw", null) != null) {
            _state.value = _state.value.copy(codex = ProviderState(loggedIn = true))
            refreshCodex()
        }
        if (prefs.getString("claude_creds_raw", null) != null) {
            _state.value = _state.value.copy(claude = ProviderState(loggedIn = true))
            refreshClaude()
        }
        prefs.getString("minimax_key", null)?.let {
            _state.value = _state.value.copy(minimax = ProviderState(loggedIn = true))
            refreshMiniMax()
        }
    }

    // ─── 登录（粘贴凭据）───

    fun loginCodex(raw: String) {
        val auth = ExtraQuotaApi.Codex.parseAuth(raw)
        if (auth == null) {
            _state.value = _state.value.copy(codex = _state.value.codex.copy(error = "未能解析出 access_token，请粘贴完整 auth.json"))
            return
        }
        prefs.edit().putString("codex_auth_raw", raw.trim()).putLong("codex_access_exp", 0).apply()
        _state.value = _state.value.copy(codex = ProviderState(loggedIn = true, isLoading = true))
        refreshCodex()
    }

    fun loginClaude(raw: String) {
        val auth = ExtraQuotaApi.Claude.parseCredentials(raw)
        if (auth == null) {
            _state.value = _state.value.copy(claude = _state.value.claude.copy(error = "未能解析出 accessToken，请粘贴完整 credentials.json"))
            return
        }
        prefs.edit().putString("claude_creds_raw", raw.trim()).apply()
        _state.value = _state.value.copy(claude = ProviderState(loggedIn = true, isLoading = true))
        refreshClaude()
    }

    fun loginMiniMax(key: String) {
        val k = key.filter { it in '!'..'~' }
        if (k.length < 20) {
            _state.value = _state.value.copy(minimax = _state.value.minimax.copy(error = "API Key 格式不对（应以 sk- 开头）"))
            return
        }
        prefs.edit().putString("minimax_key", k).apply()
        _state.value = _state.value.copy(minimax = ProviderState(loggedIn = true, isLoading = true))
        refreshMiniMax()
    }

    // ─── 刷新 ───

    fun refreshAll() {
        if (_state.value.codex.loggedIn) refreshCodex()
        if (_state.value.claude.loggedIn) refreshClaude()
        if (_state.value.minimax.loggedIn) refreshMiniMax()
    }

    fun refreshCodex() = viewModelScope.launch {
        update("codex") { it.copy(isLoading = true, error = null) }
        try {
            val raw = prefs.getString("codex_auth_raw", null) ?: return@launch
            val auth = ExtraQuotaApi.Codex.parseAuth(raw) ?: return@launch
            val usage = withContext(Dispatchers.IO) {
                var access = cachedAccess("codex", auth.accessToken)
                try {
                    ExtraQuotaApi.Codex.fetchUsage(access, auth.accountId)
                } catch (e: QuotaException) {
                    if (e.code == 401 && auth.refreshToken != null) {
                        access = ExtraQuotaApi.Codex.refreshAccessToken(auth.refreshToken)
                        cacheAccess("codex", access)
                        ExtraQuotaApi.Codex.fetchUsage(access, auth.accountId)
                    } else throw e
                }
            }
            update("codex") {
                it.copy(isLoading = false, account = usage.account ?: it.account, groups = usage.groups)
            }
        } catch (e: Exception) {
            update("codex") { it.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}") }
        }
    }

    fun refreshClaude() = viewModelScope.launch {
        update("claude") { it.copy(isLoading = true, error = null) }
        try {
            val raw = prefs.getString("claude_creds_raw", null) ?: return@launch
            val auth = ExtraQuotaApi.Claude.parseCredentials(raw) ?: return@launch
            val usage = withContext(Dispatchers.IO) {
                var access = cachedAccess("claude", auth.accessToken)
                try {
                    ExtraQuotaApi.Claude.fetchUsage(access)
                } catch (e: QuotaException) {
                    if (e.code == 401 && auth.refreshToken != null) {
                        access = ExtraQuotaApi.Claude.refreshAccessToken(auth.refreshToken)
                        cacheAccess("claude", access)
                        ExtraQuotaApi.Claude.fetchUsage(access)
                    } else throw e
                }
            }
            update("claude") {
                it.copy(isLoading = false, groups = usage.groups, error = if (usage.groups.isEmpty()) "账号暂未返回额度窗口" else null)
            }
        } catch (e: Exception) {
            update("claude") { it.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}") }
        }
    }

    fun refreshMiniMax() = viewModelScope.launch {
        update("minimax") { it.copy(isLoading = true, error = null) }
        try {
            val key = prefs.getString("minimax_key", null) ?: return@launch
            val usage = withContext(Dispatchers.IO) { ExtraQuotaApi.MiniMax.fetchUsage(key) }
            update("minimax") {
                it.copy(
                    isLoading = false,
                    groups = usage.groups,
                    account = null,
                    error = if (usage.groups.isEmpty()) "计划未返回额度数据" else null
                )
            }
        } catch (e: Exception) {
            update("minimax") { it.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}") }
        }
    }

    // ─── 解绑 ───

    fun logout(provider: String) {
        val key = when (provider) {
            "codex" -> "codex_auth_raw"
            "claude" -> "claude_creds_raw"
            else -> "minimax_key"
        }
        prefs.edit().remove(key)
            .remove("codex_access").remove("codex_access_exp")
            .remove("claude_access").remove("claude_access_exp")
            .apply()
        update(provider) { ProviderState() }
    }

    fun clearError(provider: String) = update(provider) { it.copy(error = null) }

    /** 一键授权失败时由 UI 层上报。 */
    fun setProviderError(provider: String, msg: String) =
        update(provider) { it.copy(error = msg, isLoading = false) }

    // ─── 内部 ───

    /** 缓存的 access_token（1 小时内复用），否则回退原始 token。 */
    private fun cachedAccess(provider: String, fallback: String): String {
        val exp = prefs.getLong("${provider}_access_exp", 0)
        val tok = prefs.getString("${provider}_access", null)
        return if (tok != null && System.currentTimeMillis() < exp) tok else fallback
    }

    private fun cacheAccess(provider: String, access: String) {
        prefs.edit()
            .putString("${provider}_access", access)
            .putLong("${provider}_access_exp", System.currentTimeMillis() + 60 * 60 * 1000)
            .apply()
    }

    private fun update(provider: String, f: (ProviderState) -> ProviderState) {
        val s = _state.value
        _state.value = when (provider) {
            "codex" -> s.copy(codex = f(s.codex))
            "claude" -> s.copy(claude = f(s.claude))
            else -> s.copy(minimax = f(s.minimax))
        }
    }
}
