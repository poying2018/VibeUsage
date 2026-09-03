package ai.vibecafe.usage.ui.ag

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.ag.AgQuotaApi
import ai.vibecafe.usage.data.ag.AgQuotaApi.QuotaBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 反重力（Google Antigravity）额度面板状态。 */
data class AgPanelState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val email: String? = null,
    val tier: String? = null,
    val buckets5h: List<QuotaBucket> = emptyList(),
    val bucketsWeekly: List<QuotaBucket> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null,
)

/**
 * 单账号反重力额度 ViewModel。
 * 登录方式：粘贴桌面版 Antigravity.Tools 导出的账号 JSON（取 refresh_token/email），
 * 或直接粘贴 refresh_token 串。
 */
class AgPanelViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AgPanelState())
    val state: StateFlow<AgPanelState> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("ag_panel", Application.MODE_PRIVATE)

    init {
        val token = prefs.getString("refresh_token", null)
        if (!token.isNullOrBlank()) {
            _state.value = _state.value.copy(
                isLoggedIn = true,
                email = prefs.getString("email", null),
                tier = prefs.getString("tier", null)
            )
            refresh()
        }
    }

    /** 导入账号：支持账号 JSON（取 refresh_token/email 字段）或裸 refresh_token 串。 */
    fun login(rawInput: String) {
        val (token, email) = parseAccount(rawInput)
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "未找到 refresh_token，请粘贴账号 JSON 或令牌串")
            return
        }
        prefs.edit().putString("refresh_token", token).apply()
        _state.value = _state.value.copy(
            isLoggedIn = true,
            isLoading = true,
            error = null,
            email = email ?: emailFromJson(rawInput),
            tier = null,
            buckets5h = emptyList(),
            bucketsWeekly = emptyList(),
            updatedAt = 0L
        )
        email?.let { prefs.edit().putString("email", it).apply() }
        refresh()
    }

    /** 刷新额度：refresh_token → access_token →（项目/等级）→ 配额摘要。 */
    fun refresh() {
        val token = prefs.getString("refresh_token", null) ?: return
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val access = AgQuotaApi.refreshAccessToken(token)
                    val assist = try {
                        AgQuotaApi.loadAssist(access.accessToken)
                    } catch (_: Exception) {
                        null  // 等级拉不到不影响额度展示
                    }
                    assist?.tier?.let { t ->
                        prefs.edit().putString("tier", t).apply()
                    }
                    val summary = AgQuotaApi.fetchQuotaSummary(access.accessToken, assist?.projectId)
                    assist to summary
                }
                val (assist, summary) = result
                _state.value = _state.value.copy(
                    isLoading = false,
                    tier = assist?.tier ?: _state.value.tier,
                    buckets5h = summary.buckets5h,
                    bucketsWeekly = summary.bucketsWeekly,
                    updatedAt = System.currentTimeMillis(),
                    error = if (summary.buckets5h.isEmpty() && summary.bucketsWeekly.isEmpty())
                        "账号未返回额度数据（可能尚未产生用量）" else null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "查询失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 一键授权失败时由 UI 层上报。 */
    fun setOAuthError(msg: String) {
        _state.value = _state.value.copy(error = msg)
    }

    fun logout() {
        prefs.edit().clear().apply()
        _state.value = AgPanelState()
    }

    companion object {
        /** 只保留可见 ASCII，过滤复制粘贴混入的不可见字符。 */
        fun sanitizeToken(raw: String): String = raw.filter { it in '!'..'~' }

        /** 从输入中提取 (refresh_token, email)：JSON 优先（顶层或嵌套 token 对象），否则整串当令牌。 */
        fun parseAccount(raw: String): Pair<String?, String?> {
            val input = raw.trim()
            if (!input.contains("{")) {
                val token = sanitizeToken(input)
                return token.takeIf { it.length >= 20 } to null
            }
            return try {
                val obj = JSONObject(input)
                val tokenObj = obj.optJSONObject("token") ?: obj.optJSONObject("tokens")
                val token = sanitizeToken(
                    obj.optString("refresh_token", "")
                        .ifEmpty { tokenObj?.optString("refresh_token", "") ?: "" }
                )
                val email = obj.optString("email", "").ifEmpty { null }
                token.takeIf { it.isNotEmpty() } to email
            } catch (_: Exception) {
                null to null
            }
        }

        private fun emailFromJson(raw: String): String? = try {
            JSONObject(raw.trim()).optString("email", "").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
