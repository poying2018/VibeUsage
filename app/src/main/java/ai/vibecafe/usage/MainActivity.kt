package ai.vibecafe.usage

import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.core.GlassStyleStore
import ai.vibecafe.usage.core.ThemeMode
import ai.vibecafe.usage.core.ThemeStore
import ai.vibecafe.usage.data.FxRates
import ai.vibecafe.usage.ui.DeviceLoginViewModel
import ai.vibecafe.usage.ui.DashboardScreen
import ai.vibecafe.usage.ui.MainViewModel
import ai.vibecafe.usage.ui.glass.GlassBackground
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.rememberPageBackdrop
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.GlassTheme
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        // 汇率先从缓存读，之后在组合里自动刷新（24h 有效期，失败保留旧值）
        FxRates.init(this)
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

    // 启动时自动检测美元汇率（过期才拉，失败不挡界面）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        FxRates.refreshIfStale(context)
    }

    // 液态玻璃全局样式（LockScreen 配方，设置页可调）
    // init() 返回 Unit，remember 不该返回 Unit —— 用 SideEffect 触发一次初始化
    SideEffect { GlassStyleStore.init(context) }

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
                    // 连同上一个账号留在内存里的用量明细与设备筛选一起清掉
                    vm.clearAccountData()
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
                onSelectDevices = vm::setDevices,
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
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val deviceVm: DeviceLoginViewModel = viewModel()
    val devicePhase by deviceVm.state.collectAsStateWithLifecycle()

    // 设备码批准 → 先复位状态机再落盘，避免登出回到登录页时残留 Approved 又自动登录
    LaunchedEffect(devicePhase) {
        (devicePhase as? DeviceLoginViewModel.Phase.Approved)?.let {
            deviceVm.reset()
            onLogin(it.apiKey)
        }
    }

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
                    // 网络异常与「Key 无效」要分开说：弱网用户看到「API Key 验证失败」
                    // 会以为自己抄错了，反复重输同一个正确的 Key
                    error = when (e) {
                        is retrofit2.HttpException -> when (e.code()) {
                            401, 403 -> "API Key 无效或已过期，请重新输入"
                            else -> "验证失败（HTTP ${e.code()}），请稍后重试"
                        }
                        is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
                        is java.net.UnknownHostException -> "无法连接服务器，请检查网络后重试"
                        is java.net.ConnectException -> "连接失败，请检查网络后重试"
                        is java.io.IOException -> "网络错误，请检查网络后重试"
                        else -> "验证失败：${e.message?.take(60) ?: "未知错误"}"
                    }
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
                    "一键授权登录，无需手动粘贴 API Key",
                    style = GlassText.Label,
                    color = palette.InkMid
                )
                // 一键登录：设备码授权，浏览器批准后自动回填凭据（官方收紧 Key 自助创建后的主通道）
                when (val phase = devicePhase) {
                    is DeviceLoginViewModel.Phase.Waiting -> {
                        // 验证码呼吸微动效：等待期间把视线引到需要跨设备抄写的字符上
                        val codePulse = rememberInfiniteTransition(label = "codePulse")
                        val codeAlpha by codePulse.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.6f,
                            animationSpec = infiniteRepeatable(
                                tween(1100, easing = FastOutSlowInEasing),
                                RepeatMode.Reverse
                            ),
                            label = "codeAlpha"
                        )
                        Text(
                            "浏览器打开 vibecafe.ai/usage/device（需已登录）",
                            style = GlassText.Label,
                            color = palette.InkMid
                        )
                        Text(
                            phase.userCode,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 3.sp,
                            color = palette.InkHi,
                            modifier = Modifier
                                .graphicsLayer { alpha = codeAlpha }
                                .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) {
                                val cm = context.getSystemService(android.app.Application.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("VibeCafe 验证码", phase.userCode))
                            }
                        )
                        Text("已自动复制，点验证码可再次复制", style = GlassText.ChartAxis, color = palette.InkLo)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = palette.InkMid, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("等待浏览器批准授权…", style = GlassText.Label, color = palette.InkMid)
                        }
                        Text(
                            "没有自动打开浏览器？点此重试",
                            fontSize = 11.sp,
                            color = palette.InkLo,
                            modifier = Modifier.clickable(
                                interactionSource = interaction,
                                indication = null
                            ) {
                                ai.vibecafe.usage.data.quota.OAuthFlow.openBrowser(context, phase.verifyUrl)
                            }
                        )
                        LiquidButton(
                            onClick = { deviceVm.cancel() },
                            backdrop = backdrop,
                            surfaceColor = Color.Gray,
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                        ) {
                            Text("取消", style = GlassText.Chip, color = Color.White, fontSize = 14.sp)
                        }
                    }
                    is DeviceLoginViewModel.Phase.Terminal -> {
                        Text(
                            phase.message,
                            color = Color(0xFFE94560),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        LiquidButton(
                            onClick = { deviceVm.start() },
                            backdrop = backdrop,
                            surfaceBrush = Brush.horizontalGradient(
                                listOf(palette.Accent, Color(0xFF4E7BFF)),
                                startX = 0f,
                                endX = 480f
                            ),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("重新一键登录", style = GlassText.Chip, color = Color.White, fontSize = 15.sp)
                        }
                    }
                    else -> {
                        LiquidButton(
                            onClick = { deviceVm.start() },
                            backdrop = backdrop,
                            surfaceBrush = Brush.horizontalGradient(
                                listOf(palette.Accent, Color(0xFF4E7BFF)),
                                startX = 0f,
                                endX = 480f
                            ),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("一键登录（浏览器授权）", style = GlassText.Chip, color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
                Text("—— 或粘贴 API Key ——", style = GlassText.ChartAxis, color = palette.InkLo)
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
                // 登录按钮：液态玻璃胶囊 + 青紫渐变表面
                LiquidButton(
                    onClick = { verifyAndLogin(key) },
                    backdrop = backdrop,
                    enabled = key.isNotBlank() && !verifying,
                    surfaceBrush = Brush.horizontalGradient(
                        listOf(palette.Accent, Color(0xFF4E7BFF)),
                        startX = 0f,
                        endX = 480f
                    ),
                    modifier = Modifier.fillMaxWidth()
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
