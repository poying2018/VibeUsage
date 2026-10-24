package ai.vibecafe.usage.ui.ag

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vibecafe.usage.data.quota.AgGoogleOAuth
import ai.vibecafe.usage.data.quota.CommandCodeOAuth
import ai.vibecafe.usage.data.quota.ExtraQuotaApi
import ai.vibecafe.usage.data.quota.GitHubCopilot
import ai.vibecafe.usage.data.quota.OAuthFlow
import ai.vibecafe.usage.data.quota.OpenRouter
import ai.vibecafe.usage.data.quota.OpenRouterOAuth
import ai.vibecafe.usage.data.quota.QuotaHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** 当前账号/套餐实际可用的模型列表（动态接口拿不到则留空，UI 隐藏模型区）。 */
    val models: List<String> = emptyList(),
    /** 模型区标题覆盖（null 用默认「可用模型」），供按定价分档的供应商注明口径。 */
    val modelsLabel: String? = null,
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

    /**
     * 各平台的额度历史快照（近 7 天），供总览页画迷你趋势。
     * key = provider.id。
     */
    private val _history = MutableStateFlow<Map<String, List<QuotaHistoryStore.Snapshot>>>(emptyMap())
    val history: StateFlow<Map<String, List<QuotaHistoryStore.Snapshot>>> = _history.asStateFlow()

    private val prefs = application.getSharedPreferences("quota_extra", Application.MODE_PRIVATE)

    /** 进行中的一键授权任务：重复发起时取消旧流程（GitHub 轮询 / loopback 等待）。 */
    private val oauthJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * 进行中的额度查询任务（按供应商 id）。
     * 解绑或换凭据时必须取消旧的：否则旧账号的额度结果会晚到，
     * 覆盖新登录的状态（桌面/额度页显示串号的账号数据）。
     * 用 ConcurrentHashMap 才能用 remove(key, value) 精确移除自己那一条。
     */
    private val refreshJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    init {
        // 清理 v2.10.2 及之前残留的 Codex/Claude 凭据（两平台已移除）
        // 清理 v2.16.0 的 Agnes 单 Key 凭据（v2.17 起改为账号密码登录，Key 已废弃）
        // 清理 v2.17.1 及之前的 Gemini CLI 凭据（该供应商已移除）
        prefs.edit()
            .remove("codex_auth_raw").remove("codex_access").remove("codex_access_exp")
            .remove("claude_creds_raw").remove("claude_access").remove("claude_access_exp")
            .remove("agnes_key")
            .remove("geminicli_key")
            .apply()
        ExtraProvider.entries.forEach { p ->
            if (p.credPrefsKeys.all { prefs.getString(it, null) != null }) {
                _state.value = _state.value.toMutableMap().apply { put(p.id, ProviderState(loggedIn = true)) }
                refresh(p.id)
            }
        }
        // 载入历史快照：总览页的「近 7 天趋势」要在**本次查询失败**时也能看到
        // （凭据失效正是最需要看趋势的时候）。磁盘上的旧数据不能覆盖本次刚记录的新条目。
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val loaded = ExtraProvider.entries.associate { p ->
                p.id to QuotaHistoryStore.history(app, p.id)
            }
            _history.update { old -> loaded + old }
        }
    }

    // ─── 登录（粘贴凭据）───

    fun login(id: String, creds: List<String>) {
        val provider = ExtraProvider.byId(id) ?: return
        // 只剔除控制字符（换行/制表/零宽等粘贴噪声），不能连空格和中文一起吃掉：
        // Agnes 的密码含空格或非 ASCII 时会被悄悄改写，用户重输多少次都登不上。
        val cleaned = creds.map { raw ->
            raw.trim().filter { c -> c.code >= 0x20 && c.code != 0x7F }
        }
        if (cleaned.any { it.length < provider.minCredLen }) {
            update(id) { it.copy(error = "凭据格式不对（长度不足，请检查输入）") }
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
                    OAuthKind.COMMANDCODE -> listOf(
                        // 网页授权：浏览器登录 Studio，回调直接带回 API Key
                        withContext(Dispatchers.IO) {
                            CommandCodeOAuth.login(getApplication()).apiKey
                        }
                    )
                    OAuthKind.OPENROUTER -> {
                        val auth = withContext(Dispatchers.IO) { OpenRouterOAuth.awaitCode(getApplication()) }
                        listOf(
                            withContext(Dispatchers.IO) {
                                OpenRouter.exchangeOAuthCode(auth.code, auth.verifier)
                            }
                        )
                    }
                    OAuthKind.GITHUB_DEVICE -> {
                        val dc = withContext(Dispatchers.IO) { GitHubCopilot.startDeviceCode() }
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
                                    GitHubCopilot.pollToken(dc.deviceCode)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: GitHubCopilot.TerminalAuthException) {
                                // 用户拒绝 / 设备码过期：终止性错误，立即结束并如实告知，
                                // 不能当成网络抖动继续空转到过期
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
                oauthJobs.remove(id, coroutineContext[Job])
                update(id) { ProviderState(loggedIn = true, isLoading = true) }
                refresh(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                oauthJobs.remove(id, coroutineContext[Job])
                update(id) { it.copy(isLoading = false, oauthCode = null, error = "授权失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    // ─── 刷新 ───

    fun refresh(id: String) {
        val provider = ExtraProvider.byId(id) ?: return
        // 取消上一次未完成的查询：换凭据/解绑后旧结果晚到会串号
        refreshJobs.remove(id)?.cancel()
        update(id) { it.copy(isLoading = true, error = null) }
        val job = viewModelScope.launch {
            try {
                val creds = provider.credPrefsKeys.map { k ->
                    prefs.getString(k, null) ?: throw ExtraQuotaApi.QuotaException(0, "凭据缺失，请重新接入")
                }
                val usage = withContext(Dispatchers.IO) { provider.fetch(creds) }
                // 记录今日快照（近 7 天趋势）。写盘失败绝不能影响额度展示，故整段吞掉异常
                withContext(Dispatchers.IO) { recordHistory(id, usage) }
                update(id) {
                    it.copy(
                        isLoading = false,
                        account = usage.account,
                        groups = usage.groups,
                        models = usage.models,
                        modelsLabel = usage.modelsLabel,
                        error = if (usage.groups.isEmpty()) "计划未返回额度数据" else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(isLoading = false, error = "查询失败：${e.message ?: "未知错误"}") }
            } finally {
                // 只移除**自己**这一条：直接 remove(id) 可能把后一轮新 job 的跟踪删掉，
                // 那样 logout 时新 job 就不会被取消，旧账号结果仍可能晚到串号
                refreshJobs.remove(id, coroutineContext[Job])
            }
        }
        refreshJobs[id] = job
    }

    /**
     * 一键刷新所有**已接入**平台。
     *
     * 各平台的查询是并发发起的：[refresh] 只取消**同一个 id** 的旧任务，
     * 不同 id 之间互不影响（见 refreshJobs 的说明）。
     */
    fun refreshAll() {
        val map = _state.value
        ExtraProvider.entries.forEach { p ->
            if (map[p.id]?.loggedIn == true) refresh(p.id)
        }
    }

    /** 记录今日快照并刷新趋势数据（失败不影响额度展示）。 */
    private fun recordHistory(id: String, usage: ExtraQuotaApi.Usage) {
        runCatching {
            val app = getApplication<Application>()
            QuotaHistoryStore.record(app, id, usage)
            val snap = QuotaHistoryStore.history(app, id)
            _history.update { m -> m.toMutableMap().apply { put(id, snap) } }
        }
    }

    // ─── 解绑 ───

    /** 导入凭据备份后调用：按 prefs 现状重建各供应商状态并全部刷新。 */
    fun onCredsImported() {
        _state.value = _state.value.toMutableMap().apply {
            ExtraProvider.entries.forEach { p ->
                val loggedIn = p.credPrefsKeys.all { prefs.getString(it, null) != null }
                put(p.id, if (loggedIn) ProviderState(loggedIn = true) else ProviderState())
            }
        }
        ExtraProvider.entries.forEach { p ->
            if (_state.value[p.id]?.loggedIn == true) refresh(p.id)
        }
    }

    fun logout(id: String) {
        val provider = ExtraProvider.byId(id) ?: return
        oauthJobs.remove(id)?.cancel()
        // 取消在途查询：否则旧账号的额度结果晚到会把已解绑的状态重新填上（串号）
        refreshJobs.remove(id)?.cancel()
        prefs.edit().apply {
            provider.credPrefsKeys.forEach { remove(it) }
        }.apply()
        // 历史快照也一并清掉：换账号后同一个平台的趋势图不该把上一个账号的历史混进来
        runCatching {
            ai.vibecafe.usage.data.quota.QuotaHistoryStore.delete(getApplication(), id)
        }
        _state.value = _state.value.toMutableMap().apply { put(id, ProviderState()) }
        _history.value = _history.value.toMutableMap().apply { remove(id) }
    }

    fun clearError(id: String) = update(id) { it.copy(error = null) }

    private fun update(id: String, f: (ProviderState) -> ProviderState) {
        _state.value = _state.value.toMutableMap().apply {
            put(id, f(get(id) ?: ProviderState()))
        }
    }
}
