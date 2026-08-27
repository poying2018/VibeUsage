package ai.vibecafe.usage

import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.core.ThemeMode
import ai.vibecafe.usage.core.ThemeStore
import ai.vibecafe.usage.ui.DashboardScreen
import ai.vibecafe.usage.ui.MainViewModel
import ai.vibecafe.usage.ui.glass.GlassBackground
import ai.vibecafe.usage.ui.glass.rememberPageBackdrop
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.GlassTheme
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 桌面小组件的后台用量同步（已安装小组件时每 30 分钟刷新）
        ai.vibecafe.usage.widget.UsageWidgetSync.ensureScheduled(this)
        // 每日预算核对：预测整月消耗超阈值时发本地通知
        ai.vibecafe.usage.budget.BudgetNotify.ensureScheduled(this)
        // Android 13+ 通知权限（预算提醒用），静默请求一次
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }

    // 外观模式：跟随系统 / 浅色 / 深色 / 纯黑（持久化）
    var themeMode by remember { mutableStateOf(ThemeStore.get(context)) }
    val darkTheme = when (themeMode) {
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    GlassTheme(darkTheme = darkTheme, amoled = themeMode == ThemeMode.AMOLED) {
        // 每次进入前台（首次进入、登录后、切后台再回来）都刷新数据，并静默检查更新
        LifecycleResumeEffect(apiKey) {
            if (apiKey.isNotEmpty()) {
                vm.loadData(apiKey)
                vm.checkUpdateIfIdle()
            }
            onPauseOrDispose { }
        }

        if (apiKey.isEmpty()) {
            LoginScreen(onLogin = { key ->
                ApiKeyStore.save(context, key)
                apiKey = key
            })
        } else {
            DashboardScreen(
                state = state,
                onSelectRange = vm::setTimeRange,
                onRefresh = { vm.loadData(apiKey) },
                onLogout = {
                    ApiKeyStore.clear(context)
                    apiKey = ""
                },
                onShowToolDetail = vm::showToolDetail,
                onHideToolDetail = vm::hideToolDetail,
                onShowModelDetail = vm::showModelDetail,
                onHideModelDetail = vm::hideModelDetail,
                onCheckUpdate = vm::checkUpdate,
                onDownloadUpdate = vm::downloadUpdate,
                onInstallUpdate = vm::installUpdate,
                onSelectCustomRange = vm::setCustomRange,
                onShareCard = vm::shareCard,
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    ThemeStore.set(context, mode)
                }
            )
        }
    }
}

@Composable
private fun LoginScreen(onLogin: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    val backdrop = rememberPageBackdrop()
    var key by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }

    // 验证 API Key：调 vibecafe.ai 用量接口，成功(HTTP 200)才放行
    val verifyAndLogin: (String) -> Unit = { rawKey ->
        val k = rawKey.trim()
        if (k.isNotBlank() && !verifying) {
            verifying = true
            error = null
            scope.launch {
                val ok = try {
                    ai.vibecafe.usage.data.RetrofitClient.api.getUsageDaily("Bearer $k")
                    true
                } catch (e: Exception) {
                    error = "API Key 验证失败，请检查后重试"
                    false
                }
                verifying = false
                if (ok) onLogin(k)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.86f)
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Logo
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF7EE8DF), Color(0xFF7C6BFF)),
                                start = androidx.compose.ui.geometry.Offset.Zero,
                                end = androidx.compose.ui.geometry.Offset(160f, 200f)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_logo_v),
                        contentDescription = "VibeUsage",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("VibeUsage", style = GlassText.Title, color = palette.InkHi)
                Text(
                    "输入 VibeCafe API Key 以加载用量数据",
                    style = GlassText.Label,
                    color = palette.InkMid
                )
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text("API Key", color = palette.InkMid) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Password
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = palette.InkHi,
                        unfocusedTextColor = palette.InkHi,
                        focusedContainerColor = palette.SurfaceSoft,
                        unfocusedContainerColor = palette.SurfaceSoft.copy(alpha = 0.72f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = palette.Accent
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                // 登录按钮：青紫渐变玻璃
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(palette.Accent, Color(0xFF4E7BFF)),
                                startX = 0f,
                                endX = 480f
                            )
                        )
                        .clickable(interaction, null, enabled = key.isNotBlank() && !verifying) {
                            verifyAndLogin(key)
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "登录",
                            style = GlassText.Chip,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
                // 验证失败提示
                if (error != null) {
                    Text(
                        error!!,
                        color = Color(0xFFE94560),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                // 隐私提示
                Text(
                    "API Key 仅保存在本机，不会上传",
                    style = GlassText.ChartAxis,
                    color = palette.InkLo,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
