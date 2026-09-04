package ai.vibecafe.usage.ui.ag

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.quota.AgGoogleOAuth
import ai.vibecafe.usage.data.quota.ExtraQuotaApi
import ai.vibecafe.usage.data.quota.OAuthFlow
import ai.vibecafe.usage.data.quota.OpenRouterOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** GitHub 设备码授权流程中展示给用户手动输入的 user_code。 */
    val oauthCode: String? = null,
    val groups: Map<String, List<ExtraQuotaApi.Bar>> = emptyMap(),
    val error: String? = null
)

/**
 * 「额度」页凭据型供应商的额度 ViewModel。
 * 凭据粘贴一次即持久化到 quota_extra prefs；支持一键授权的供应商（GitHub/OpenRouter/Gemini CLI）
 * 由 [loginOAuth] 完成 OAuth 后写入同样的存储键，刷新/解绑逻辑与粘贴型完全一致。
 */
class ExtraQuotaViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<Map<String, ProviderState>>(emptyMap())
    val state: StateFlow<Map<String, ProviderState>> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("quota_extra", Application.MODE_PRIVATE)

    /** 进行中的一键授权任务：重复发起时取消旧流程（GitHub 轮询 / loopback 等待）。 */
    private val oauthJobs = mutableMapOf<String, Job>()

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

    // ─── 登录（一键授权）───

    fun loginOAuth(id: String) {
        val provider = ExtraProvider.byId(id) ?: return
        val kind = provider.oauth ?: return
        oauthJobs[id]?.cancel()
        oauthJobs[id] = viewModelScope.launch {
            update(id) { it.copy(isLoading = true, error = null, oauthCode = null) }
            try {
                val creds: List<String> = when (kind) {
                    OAuthKind.GOOGLE -> listOf(
                        withContext(Dispatchers.IO) { AgGoogleOAuth.login(getApplication()).refreshToken }
                    )
                    OAuthKind.GEMINI_CLI_OAUTH -> listOf(
                        withContext(Dispatchers.IO) {
                            ExtraQuotaApi.GeminiCli.exchangeCode(
                                ai.vibecafe.usage.data.quota.GeminiCliOAuth.awaitCode(getApplication())
                            )
                        }
                    )
                    OAuthKind.OPENROUTER -> {
                        val auth = withContext(Dispatchers.IO) { OpenRouterOAuth.awaitCode(getApplication()) }
                        listOf(
                            withContext(Dispatchers.IO) {
                                ExtraQuotaApi.OpenRouter.exchangeOAuthCode(auth.code, auth.verifier)
                            }
                        )
                    }
                    OAuthKind.GITHUB_DEVICE -> {
                        val dc = withContext(Dispatchers.IO) { ExtraQuotaApi.GitHubCopilot.startDeviceCode() }
                        update(id) { it.copy(oauthCode = dc.userCode) }
                        withContext(Dispatchers.Main) {
                            // 跳浏览器前先把授权码写进剪贴板，用户落地 github.com/login/device 直接粘贴
                            val cm = getApplication<Application>().getSystemService(Application.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("GitHub 授权码", dc.userCode))
                            android.widget.Toast.makeText(
                                getApplication(), "授权码 ${dc.userCode} 已复制，去浏览器粘贴", Toast.LENGTH_LONG
                            ).show()
                            OAuthFlow.openBrowser(getApplication(), dc.verifyUrl)
                        }
                        val deadline = System.currentTimeMillis() + dc.expiresSec.coerceAtMost(840) * 1000
                        var token: String? = null
                        while (token == null && System.currentTimeMillis() < deadline) {
                            delay(dc.intervalSec.coerceAtLeast(5) * 1000)
                            try {
                                token = withContext(Dispatchers.IO) {
                                    ExtraQuotaApi.GitHubCopilot.pollToken(dc.deviceCode)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // 代理/网络抖动：本轮轮询跳过，继续等待用户在浏览器完成授权
                            }
                        }
                        listOf(
                            token ?: throw ExtraQuotaApi.QuotaException(0, "设备码已过期，请重新发起授权")
                        )
                    }
                }
                prefs.edit().apply {
                    provider.credPrefsKeys.forEachIndexed { i, k -> putString(k, creds[i]) }
                }.apply()
                oauthJobs.remove(id)
                update(id) { ProviderState(loggedIn = true, isLoading = true) }
                refresh(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                oauthJobs.remove(id)
                update(id) { it.copy(isLoading = false, oauthCode = null, error = "授权失败：${e.message ?: "未知错误"}") }
            }
        }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}") }
        }
    }

    // ─── 解绑 ───

    fun logout(id: String) {
        val provider = ExtraProvider.byId(id) ?: return
        oauthJobs.remove(id)?.cancel()
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
