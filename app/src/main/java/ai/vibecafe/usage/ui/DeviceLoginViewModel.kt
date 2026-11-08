package ai.vibecafe.usage.ui

import ai.vibecafe.usage.data.VibeCafeDeviceLogin
import ai.vibecafe.usage.data.quota.OAuthFlow
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录页「一键登录」状态机：发起设备码 → 复制验证码 + 打开浏览器 → 轮询 → 回传凭据。
 * 放在 ViewModel 里，授权等待（可能长达数分钟）期间旋转屏幕不丢流程。
 */
class DeviceLoginViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Phase {
        data object Idle : Phase
        data class Waiting(val userCode: String, val verifyUrl: String) : Phase
        data class Approved(val apiKey: String) : Phase
        data class Terminal(val message: String) : Phase
    }

    private val _state = MutableStateFlow<Phase>(Phase.Idle)
    val state: StateFlow<Phase> = _state

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val start = try {
                VibeCafeDeviceLogin.start()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = Phase.Terminal("发起一键登录失败，请检查网络后重试")
                return@launch
            }
            val context = getApplication<Application>()
            runCatching {
                val cm = context.getSystemService(Application.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VibeCafe 验证码", start.userCode))
                Toast.makeText(context, "验证码 ${start.userCode} 已复制，去浏览器点「批准」", Toast.LENGTH_LONG).show()
                OAuthFlow.openBrowser(context, start.verifyUrl)
            }
            _state.value = Phase.Waiting(start.userCode, start.verifyUrl)

            val deadline = System.currentTimeMillis() + start.expiresInSec.coerceAtMost(900) * 1000L
            val intervalMs = start.intervalSec.coerceIn(3, 10) * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                val result = try {
                    VibeCafeDeviceLogin.poll(start.deviceCode)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // 网络抖动：本轮跳过，继续等浏览器侧确认
                }
                when (result) {
                    null, VibeCafeDeviceLogin.PollResult.Pending -> {}
                    is VibeCafeDeviceLogin.PollResult.Approved -> {
                        _state.value = Phase.Approved(result.apiKey)
                        return@launch
                    }
                    VibeCafeDeviceLogin.PollResult.Denied -> {
                        _state.value = Phase.Terminal("已在浏览器拒绝授权")
                        return@launch
                    }
                    VibeCafeDeviceLogin.PollResult.Expired -> {
                        _state.value = Phase.Terminal("验证码已失效，请重新发起一键登录")
                        return@launch
                    }
                    is VibeCafeDeviceLogin.PollResult.Failed -> {
                        if (!result.transient) {
                            _state.value = Phase.Terminal("授权失败：${result.message}")
                            return@launch
                        }
                    }
                }
            }
            _state.value = Phase.Terminal("等待授权超时，请重新发起一键登录")
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = Phase.Idle
    }

    fun reset() {
        job = null
        _state.value = Phase.Idle
    }
}
