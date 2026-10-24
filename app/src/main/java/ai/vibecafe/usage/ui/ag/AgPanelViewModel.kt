package ai.vibecafe.usage.ui.ag

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.ag.AgQuotaApi
import ai.vibecafe.usage.data.ag.AgQuotaApi.QuotaBucket
import ai.vibecafe.usage.data.quota.AgGoogleOAuth
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /**
     * 进行中的刷新任务。
     *
     * 早先用 `isLoading` 当"正在刷新"的判据，导致两个问题：
     * 1. login() 先置 isLoading=true 再调 refresh()，而 refresh() 开头 `if (isLoading) return`
     *    —— 登录路径自我阻塞，登录后永远转圈，只有杀进程重启才能恢复；
     * 2. logout() 无法取消在途请求，旧账号的结果会晚到覆盖新账号状态（串号）。
     * 改用显式 Job 句柄：重复刷新时取消上一次，logout 时一并取消。
     */
    private var refreshJob: Job? = null

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
            error = null,
            email = email ?: emailFromJson(rawInput),
            tier = null,
            buckets5h = emptyList(),
            bucketsWeekly = emptyList(),
            updatedAt = 0L
        )
        email?.let { prefs.edit().putString("email", it).apply() }
        // isLoading 由 refresh() 自己置位：这里若先置 true，refresh() 会被自己的守卫拦下
        refresh()
    }

    /** 刷新额度：refresh_token → access_token →（项目/等级）→ 配额摘要。 */
    fun refresh() {
        val token = prefs.getString("refresh_token", null) ?: return
        // 取消上一次未完成的刷新，避免旧结果覆盖新状态
        refreshJob?.cancel()
        _state.value = _state.value.copy(isLoading = true, error = null)
        refreshJob = viewModelScope.launch {
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
            } catch (e: CancellationException) {
                // 任务被取消（重复刷新 / logout）不是错误，状态由新任务或 logout 负责
                throw e
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

    /**
     * Google 一键授权（PKCE + loopback）。
     *
     * 放在 ViewModel 而不是 UI 层的组合作用域里：授权期间用户切到别的供应商 Tab，
     * 组合离开 → `rememberCoroutineScope` 被取消，浏览器里用户明明完成了登录，
     * 回到 App 却毫无反应（而且 finally 把 oauthBusy 复位，界面像什么都没发生）。
     * ViewModel 的 scope 跨 Tab 存活，授权流程不会被打断。
     */
    fun loginOAuthGoogle(context: Context) {
        if (oauthJob?.isActive == true) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        oauthJob = viewModelScope.launch {
            try {
                val l = withContext(Dispatchers.IO) { AgGoogleOAuth.login(context) }
                // 授权拿到的是 refresh_token，落库后按常规路径拉额度
                prefs.edit().putString("refresh_token", l.refreshToken).apply()
                l.email?.let { prefs.edit().putString("email", it).apply() }
                _state.value = _state.value.copy(
                    isLoggedIn = true,
                    error = null,
                    email = l.email ?: _state.value.email,
                    tier = null,
                    buckets5h = emptyList(),
                    bucketsWeekly = emptyList(),
                    updatedAt = 0L
                )
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "授权失败：${e.message?.take(140) ?: "未知错误"}"
                )
            }
        }
    }

    /** 进行中的 Google 授权任务（重复点击时取消旧流程）。 */
    private var oauthJob: Job? = null

    fun logout() {
        // 取消在途刷新：否则旧账号的额度结果会晚到，把已清空的状态重新填上（串号）
        refreshJob?.cancel()
        refreshJob = null
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
