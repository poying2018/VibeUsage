package ai.vibecafe.usage.ui

import ai.vibecafe.usage.R
import ai.vibecafe.usage.core.BackgroundStore
import ai.vibecafe.usage.core.ThemeMode
import ai.vibecafe.usage.stats.ModelDetail
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.stats.ToolDetail
import ai.vibecafe.usage.ui.anim.AnimatedCounter
import ai.vibecafe.usage.ui.anim.fadeSlideIn
import ai.vibecafe.usage.ui.ds.DsPanelScreen
import ai.vibecafe.usage.ui.ds.DsPanelViewModel
import ai.vibecafe.usage.ui.charts.DonutChart
import ai.vibecafe.usage.ui.charts.DonutSlice
import ai.vibecafe.usage.ui.charts.TrendChart
import ai.vibecafe.usage.ui.charts.TrendMetric
import ai.vibecafe.usage.ui.glass.GlassBackground
import ai.vibecafe.usage.ui.glass.LiquidGlassSegmentedControl
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.glass.glassRow
import ai.vibecafe.usage.ui.glass.glassTile
import ai.vibecafe.usage.ui.glass.rememberPageBackdrop
import ai.vibecafe.usage.ui.theme.GlassPalette
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.HanSansFamily
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private val RangeLabels = listOf("今日", "24小时", "7天", "30天", "90天", "全部")
private val RangeValues = listOf(
    TimeRange.TODAY,
    TimeRange.HOURS_24,
    TimeRange.DAYS_7,
    TimeRange.DAYS_30,
    TimeRange.DAYS_90,
    TimeRange.ALL
)

private const val APP_VERSION = "v2.9.7"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    onSelectRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onShowToolDetail: (String) -> Unit = {},
    onHideToolDetail: () -> Unit = {},
    onShowModelDetail: (String) -> Unit = {},
    onHideModelDetail: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onSelectCustomRange: (from: String, to: String) -> Unit = { _, _ -> },
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    val backdrop = rememberPageBackdrop()
    // 内容层单独导出，让悬浮玻璃（底部时间选择器 / 设置菜单）能实时模糊到其背后的滚动内容，
    // 形成真正的层级关系，而不是独立悬浮的面板
    val contentBackdrop = rememberLayerBackdrop()
    val scrimBackdrop = rememberCombinedBackdrop(backdrop, contentBackdrop)
    val selectedIndex = RangeValues.indexOf(state.selectedTimeRange).coerceAtLeast(0)
    val insets = WindowInsets.systemBars.asPaddingValues()

    var bgPath by remember { mutableStateOf(BackgroundStore.get(context)) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var showDsPanel by remember { mutableStateOf(false) }  // DS+ Milky 面板切换
    var trendMetric by remember { mutableStateOf(TrendMetric.COST) }  // 趋势图纵轴指标
    var showCustomPicker by remember { mutableStateOf(false) }  // 自定义日期范围对话框
    val dsPanelViewModel: DsPanelViewModel = viewModel()  // 与 DsPanelScreen 共享同一实例
    val wide = isWideScreen()
    val pullState = rememberPullToRefreshState()

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) bgPath = BackgroundStore.set(context, uri)
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop, imagePath = bgPath)

        // 内容整体导出进 backdrop（用包裹 Box，避开 scroll modifier 对录制的影响），
        // 悬浮玻璃才能实时模糊到其背后的滚动内容
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(contentBackdrop)
        ) {
        // 下拉刷新：material3 PullToRefreshBox 监听内部 verticalScroll 的嵌套滚动，
        // 顶部下滑即可触发刷新，刷新圈顶部留出状态栏空间
        PullToRefreshBox(
            state = pullState,
            isRefreshing = state.isRefreshing,
            onRefresh = {
                if (showDsPanel) dsPanelViewModel.loadBalance()
                else onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
            indicator = {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = insets.calculateTopPadding() + 4.dp)
                ) {
                    PullToRefreshDefaults.Indicator(
                        isRefreshing = state.isRefreshing,
                        state = pullState
                    )
                }
            }
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = insets.calculateTopPadding() + 18.dp,
                    // 底部留出「选择条高 58 + 距底 14 + 呼吸 20」的空间：
                    // 内容可滚动到悬浮选择条下方被实时模糊，滚到底时最后一行仍不会被遮挡
                    bottom = insets.calculateBottomPadding() + 92.dp,
                    start = 18.dp,
                    end = 18.dp
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Header(
                onRefresh = {
                    if (showDsPanel) dsPanelViewModel.loadBalance()
                    else onRefresh()
                },
                onOpenSettings = { settingsExpanded = true },
                onToggleDsPanel = { showDsPanel = !showDsPanel },
                showDsPanel = showDsPanel,
                backdrop = backdrop,
                wide = wide,
                refreshing = state.isRefreshing
            )

            // ---- 降级提示：24 小时粒度不可用时友好告知（不影响主内容展示）----
            state.granularityNote?.let { note ->
                GranularityNote(note, backdrop)
            }

            // ---- DS+ Milky 面板 / 主内容切换 ----
            if (showDsPanel) {
                DsPanelScreen(backdrop = backdrop)
            } else {
                // ---- 总消耗卡片 ----
            if (wide) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .fadeSlideIn(0)
                        .glassCard(backdrop)
                        .padding(26.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1.15f)) {
                        Summary(state, wide = true, onPickCustomRange = { showCustomPicker = true })
                    }
                    Column(Modifier.weight(1f)) {
                        StatsGrid(state)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fadeSlideIn(0)
                        .glassCard(backdrop)
                        .padding(20.dp)
                ) {
                    Summary(state, wide = false, onPickCustomRange = { showCustomPicker = true })
                    Spacer(Modifier.height(20.dp))
                    StatsStrip(state)
                }
            }

            // ---- 用量趋势 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .fadeSlideIn(50),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("用量趋势")
                // 与底部时间选择器同款的液态玻璃滑块（量体裁衣 + Q 弹折射）。
                // 注意：必须采样页面 backdrop 而非 scrimBackdrop——本控件在内容层内，
                // 采样含内容层的 backdrop 会形成 RenderNode 自引用循环导致 RenderThread 栈溢出
                LiquidGlassSegmentedControl(
                    items = listOf("金额", "Tokens"),
                    selectedIndex = if (trendMetric == TrendMetric.COST) 0 else 1,
                    onSelect = { idx ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        trendMetric = if (idx == 0) TrendMetric.COST else TrendMetric.TOKENS
                    },
                    backdrop = backdrop,
                    modifier = Modifier.width(150.dp),
                    height = 36.dp
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .fadeSlideIn(50)
                    .glassCard(backdrop)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                val series = when (state.selectedTimeRange) {
                    TimeRange.HOURS_24 -> state.hourlyUsage
                    TimeRange.TODAY -> state.todayHourlyUsage
                    else -> state.dailyUsage
                }
                if (series.size < 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (wide) 170.dp else 140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                state.selectedTimeRange == TimeRange.TODAY ->
                                    "今日数据还不多，去看看 24 小时视图吧"
                                state.selectedTimeRange == TimeRange.HOURS_24 ->
                                    "暂无小时粒度数据"
                                else ->
                                    "暂无趋势数据，切换到更长的时间范围试试"
                            },
                            style = GlassText.Label,
                            color = palette.InkMid
                        )
                    }
                } else {
                    TrendChart(
                        data = series,
                        height = if (wide) 180.dp else 152.dp,
                        metric = trendMetric,
                        accent = palette.Accent,
                        secondary = Color(0xFF5C7FFF)
                    )
                }
            }

            when {
                state.isLoading -> SkeletonDashboard(wide, backdrop)
                state.error != null && state.stats == null -> StatusCard(state.error, backdrop)
                else -> {
                    val hasTools = state.toolDistribution.isNotEmpty()
                    val hasModels = state.modelCosts.isNotEmpty()
                    if (wide && (hasTools || hasModels)) {
                        // 平板/宽屏：应用分布与模型消耗并排双列
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (hasTools) {
                                    ToolDistributionSection(state, backdrop, onShowToolDetail)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                if (hasModels) {
                                    ModelCostSection(state, backdrop, onShowModelDetail)
                                }
                            }
                        }
                    } else {
                        // 手机：单列顺序排列
                        if (hasTools) ToolDistributionSection(state, backdrop, onShowToolDetail)
                        if (hasModels) ModelCostSection(state, backdrop, onShowModelDetail)
                        if (!hasTools && !hasModels) {
                            StatusCard("当前时间范围内没有用量记录", backdrop)
                        }
                    }
                }
            }
        } // 结束 if (showDsPanel) 的 else 分支
        }
        } // PullToRefreshBox 结束
        } // 内容导出 Box 结束

        // ---- 底部悬浮时间选择器（DS+ 面板时不显示；不贴边，留出呼吸空间；实时模糊背后的滚动内容）----
        if (!showDsPanel) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = insets.calculateBottomPadding() + 14.dp)
            ) {
                LiquidGlassSegmentedControl(
                    items = RangeLabels,
                    // 自定义范围档不属于任何段：滑块隐藏，选中态由独立按钮承载
                    selectedIndex = if (state.selectedTimeRange == TimeRange.CUSTOM) -1 else selectedIndex,
                    onSelect = { idx ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectRange(RangeValues[idx])
                    },
                    backdrop = scrimBackdrop
                )
            }
        }

        // 设置菜单：同组合渲染，玻璃可真实采样页面背景光晕
        if (settingsExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { settingsExpanded = false }
            )
            SettingsMenu(
                backdrop = scrimBackdrop,
                showReset = bgPath != null,
                onCustomBackground = { pickImage.launch("image/*") },
                onResetBackground = {
                    BackgroundStore.clear(context)
                    bgPath = null
                },
                onLogout = {
                    settingsExpanded = false
                    onLogout()
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                update = state.update,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                topPadding = insets.calculateTopPadding() + 18.dp + if (wide) 62.dp else 54.dp
            )
        }

        // 自定义日期范围选择（与设置菜单同构：屏内 scrim + 液态玻璃面板，保持真实模糊层级）
        if (showCustomPicker) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { showCustomPicker = false }
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CustomRangePanel(
                    backdrop = scrimBackdrop,
                    initialFromMillis = System.currentTimeMillis() - 6L * 86_400_000L,
                    initialToMillis = System.currentTimeMillis(),
                    onConfirm = { from, to ->
                        showCustomPicker = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectCustomRange(from, to)
                    },
                    onDismiss = { showCustomPicker = false }
                )
            }
        }

        // 长按应用详情（3D Touch 风格）
        state.toolDetail?.let { detail ->
            ToolDetailOverlay(
                detail = detail,
                icon = toolIconFor(detail.tool),
                backdrop = backdrop,
                onDismiss = onHideToolDetail
            )
        }

        // 长按模型详情：同风格弹窗，额外突出缓存命中率
        state.modelDetail?.let { detail ->
            ModelDetailOverlay(
                detail = detail,
                icon = toolIconFor(detail.model),
                backdrop = backdrop,
                onDismiss = onHideModelDetail
            )
        }
    }
}

@Composable
private fun Header(
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDsPanel: () -> Unit = {},
    showDsPanel: Boolean = false,
    backdrop: com.kyant.backdrop.Backdrop,
    wide: Boolean = false,
    refreshing: Boolean = false
) {
    val palette = LocalGlassPalette.current
    val today = remember {
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左：logo + 标题。weight(1f) 让整组弹性收缩，窄屏(竖屏)时副标题被截断，
        // 右侧按钮组始终完整可见，不会被挤出屏幕
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(if (wide) 52.dp else 46.dp)
                    .clip(RoundedCornerShape(if (wide) 17.dp else 15.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF7EE8DF), Color(0xFF7C6BFF)),
                            start = Offset.Zero,
                            end = Offset(120f, 160f)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_logo_v),
                    contentDescription = "VibeUsage",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(if (wide) 31.dp else 28.dp)
                )
            }
            Spacer(Modifier.width(if (wide) 15.dp else 13.dp))
            Column {
                Text(
                    "VibeUsage",
                    style = GlassText.Title,
                    fontSize = if (wide) 27.sp else 23.sp,
                    color = palette.InkHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // 窄屏(竖屏)下省略日期，保证「用量概览 · 版本号」完整显示；
                // 宽屏空间充足时保留完整信息
                Text(
                    if (wide) "用量概览 · $APP_VERSION · $today"
                    else "用量概览 · $APP_VERSION",
                    style = GlassText.Label,
                    fontSize = if (wide) 13.sp else 11.5.sp,
                    color = palette.InkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconGlassButton(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新",
                backdrop = backdrop,
                onClick = onRefresh,
                spinning = refreshing
            )
            if (showDsPanel) {
                // DS 面板中：点击返回 VibeUsage
                IconGlassButton(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = "VibeUsage",
                    backdrop = backdrop,
                    onClick = onToggleDsPanel
                )
            } else {
                // 主面板：设置 + DS+ 切换
                IconGlassButton(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    backdrop = backdrop,
                    onClick = onOpenSettings
                )
                IconGlassButton(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = "DS余额",
                    backdrop = backdrop,
                    onClick = onToggleDsPanel
                )
            }
        }
    }
}

/** 同组合渲染的设置菜单（非 Popup 窗口），玻璃层能真实采样页面背景光晕。 */
@Composable
private fun SettingsMenu(
    backdrop: com.kyant.backdrop.Backdrop,
    showReset: Boolean,
    onCustomBackground: () -> Unit,
    onResetBackground: () -> Unit,
    onLogout: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp
) {
    val palette = LocalGlassPalette.current
    // 拦截菜单列内的点击：菜单内非交互区域（文本/留白/状态行）的点击
    // 若不消费，会穿透到下方全屏 scrim 导致菜单意外关闭（如点完「检查更新」后
    // 按钮变文本，再点同一位置就关掉了菜单）。
    val menuInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding, end = 18.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            Modifier
                .width(226.dp)
                .clickable(menuInteraction, null, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsMenuItem(
                backdrop = backdrop,
                icon = Icons.Filled.PhotoLibrary,
                label = "自定义背景",
                onClick = onCustomBackground
            )
            if (showReset) {
                SettingsMenuItem(
                    backdrop = backdrop,
                    icon = Icons.Filled.RestartAlt,
                    label = "恢复默认背景",
                    onClick = onResetBackground
                )
            }
            // 外观模式：跟随系统 / 浅色 / 深色
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassRow(backdrop, cornerRadius = 16.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.DarkMode,
                        contentDescription = null,
                        tint = palette.InkMid,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("外观", style = GlassText.Body)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        ThemeChip(
                            mode = mode,
                            selected = mode == themeMode,
                            onClick = { onThemeModeChange(mode) }
                        )
                    }
                }
            }
            // 检查更新 / 下载 / 安装
            UpdateBlock(
                update = update,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                backdrop = backdrop
            )
            SettingsMenuItem(
                backdrop = backdrop,
                icon = Icons.AutoMirrored.Filled.Logout,
                label = "退出登录",
                onClick = onLogout
            )
        }
    }
}

/** 设置菜单里的「检查更新 / 下载 / 安装」面板，随流程阶段切换内容。 */
@Composable
private fun UpdateBlock(
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop
) {
    val palette = LocalGlassPalette.current

    Column(
        Modifier
            .fillMaxWidth()
            .glassRow(backdrop, cornerRadius = 16.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = palette.InkMid,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("更新", style = GlassText.Body)
        }

        when (update.status) {
            UpdateStatus.IDLE -> UpdateActionRow("检查更新", onClick = onCheckUpdate)

            UpdateStatus.CHECKING -> Text(
                "正在检查更新…",
                style = GlassText.Meta,
                color = palette.InkMid
            )

            UpdateStatus.UP_TO_DATE -> {
                Text(
                    update.message ?: "已是最新版本",
                    style = GlassText.Meta,
                    color = palette.AccentInk
                )
                UpdateActionRow("重新检查", onClick = onCheckUpdate)
            }

            UpdateStatus.AVAILABLE -> {
                Text(
                    "发现新版本 ${update.version?.removePrefix("v")}",
                    style = GlassText.Meta,
                    color = palette.InkHi
                )
                UpdateActionRow("下载更新", onClick = onDownloadUpdate)
            }

            UpdateStatus.DOWNLOADING -> {
                Text(
                    "正在下载… ${(update.progress * 100).toInt()}%",
                    style = GlassText.Meta,
                    color = palette.InkMid
                )
                LinearProgressIndicator(
                    progress = { update.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = palette.Accent,
                    trackColor = palette.InkLo.copy(alpha = 0.5f)
                )
            }

            UpdateStatus.DOWNLOADED -> {
                Text(
                    "${update.version?.removePrefix("v")} 已下载",
                    style = GlassText.Meta,
                    color = palette.AccentInk
                )
                UpdateActionRow("安装更新", onClick = onInstallUpdate, emphasis = true)
            }

            // 发现新版本但该版本尚未发布带 APK 的 Release（只打了 tag）
            UpdateStatus.NO_APK -> {
                Text(
                    update.message ?: "发现新版本，安装包暂未发布",
                    style = GlassText.Meta,
                    color = palette.InkMid,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                UpdateActionRow("重新检查", onClick = onCheckUpdate)
            }

            UpdateStatus.FAILED -> {
                update.message?.let {
                    Text(it, style = GlassText.Meta, color = palette.Down)
                }
                UpdateActionRow("重试检查", onClick = onCheckUpdate)
            }
        }
    }
}

/** 更新区块里的小按钮行。 */
@Composable
private fun UpdateActionRow(label: String, onClick: () -> Unit, emphasis: Boolean = false) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (emphasis) palette.AccentWash.copy(alpha = 0.9f) else palette.AccentWash)
            .clickable(interaction, null, onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = GlassText.Chip,
            color = palette.AccentInk
        )
    }
}

@Composable
private fun RowScope.ThemeChip(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .weight(1f)
            .clip(shape)
            .background(if (selected) palette.AccentWash else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(1.dp, palette.AccentRim, shape)
                } else {
                    Modifier
                }
            )
            .clickable(interaction, null, onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            mode.label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) palette.AccentInk else palette.InkMid,
            maxLines = 1
        )
    }
}

@Composable
private fun SettingsMenuItem(
    backdrop: com.kyant.backdrop.Backdrop,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .glassRow(backdrop, cornerRadius = 16.dp)
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.InkMid,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = GlassText.Body)
    }
}

@Composable
private fun IconGlassButton(
    imageVector: ImageVector,
    contentDescription: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onClick: () -> Unit,
    spinning: Boolean = false
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    // 加载/刷新时旋转图标，提供明确的进行中反馈
    val rotation by animateFloatAsState(
        targetValue = if (spinning) 360f else 0f,
        animationSpec = if (spinning) {
            androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)
            )
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "refreshSpin"
    )
    Box(
        Modifier
            .size(40.dp)
            .glassTile(backdrop, cornerRadius = 20.dp)
            .clickable(interaction, null, enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = palette.InkHi,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

@Composable
private fun Summary(state: UiState, wide: Boolean = false, onPickCustomRange: () -> Unit = {}) {
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    val cost = state.stats?.totalCost ?: 0.0
    val trend = state.trendPercent
    val customActive = state.selectedTimeRange == TimeRange.CUSTOM

    Column(Modifier.fillMaxWidth()) {
        // 行 1：范围标题 + 独立的自定义范围入口（不再占用底部选择器档位）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    customActive && state.customRange != null ->
                        "总消耗（${state.customRange.from.replace("-", "/")} ~ ${state.customRange.to.replace("-", "/")}）"
                    else -> "总消耗（当前范围）"
                },
                style = GlassText.Label,
                color = palette.InkMid
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (customActive) palette.AccentWash else palette.InkHi.copy(alpha = 0.08f))
                    .border(
                        1.dp,
                        if (customActive) palette.AccentRim else palette.InkHi.copy(alpha = 0.14f),
                        RoundedCornerShape(999.dp)
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPickCustomRange()
                    }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.DateRange,
                    contentDescription = "自定义范围",
                    tint = if (customActive) palette.Accent else palette.InkMid,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "自定义",
                    style = GlassText.ChartAxis.copy(
                        fontSize = 10.sp,
                        color = if (customActive) palette.Accent else palette.InkMid
                    )
                )
            }
        }
        // 行 2：总消耗大数字 + 趋势百分比
        Spacer(Modifier.height(if (wide) 10.dp else 7.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCounter(
                    value = cost.toFloat(),
                    format = { formatCost(it.toDouble()) },
                    style = GlassText.Hero.copy(fontSize = if (wide) 56.sp else 44.sp),
                    color = palette.InkHi
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "USD",
                    fontFamily = HanSansFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (wide) 18.sp else 15.sp,
                    color = palette.InkMid,
                    modifier = Modifier.padding(bottom = if (wide) 6.dp else 3.dp)
                )
            }
            if (trend != null) TrendChip(trend)
        }
        // 行 3：本月消耗 + 按日均推算的整月预测
        state.monthProjection?.let { mp ->
            Spacer(Modifier.height(if (wide) 8.dp else 5.dp))
            Text(
                "本月已用 " + formatCost(mp.monthCost) + " · 按日均预测整月 " + formatCost(mp.projected),
                style = GlassText.Meta,
                color = palette.InkLo
            )
        }
    }
}

@Composable
private fun TrendChip(trend: Float) {
    val palette = LocalGlassPalette.current
    val up = trend >= 0f
    val bg = if (up) palette.AccentWash else palette.DownWash
    val rim = if (up) palette.AccentRim else palette.DownRim
    val ink = if (up) palette.AccentInk else palette.Down
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, rim, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (up) "↗" else "↘",
                fontFamily = HanSansFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ink,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(5.dp))
            Text(
                String.format(Locale.US, "%+.0f%%", trend * 100),
                style = GlassText.Chip,
                color = ink,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun StatsStrip(state: UiState) {
    val palette = LocalGlassPalette.current
    val stats = state.stats
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiniStat("Tokens", formatTokens(stats?.totalTokens ?: 0L), palette)
        MiniStat("模型", (stats?.modelCount ?: 0).toString(), palette)
        MiniStat("应用", (stats?.toolCount ?: 0).toString(), palette)
        MiniStat("会话", (stats?.sessionCount ?: 0).toString(), palette)
    }
}

/** 平板专用：四格统计 2×2 网格。 */
@Composable
private fun StatsGrid(state: UiState) {
    val palette = LocalGlassPalette.current
    val stats = state.stats
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniStat("Tokens", formatTokens(stats?.totalTokens ?: 0L), palette)
            MiniStat("模型", (stats?.modelCount ?: 0).toString(), palette)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniStat("应用", (stats?.toolCount ?: 0).toString(), palette)
            MiniStat("会话", (stats?.sessionCount ?: 0).toString(), palette)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, palette: GlassPalette) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = GlassText.NumericSmall, color = palette.InkHi)
        Spacer(Modifier.height(3.dp))
        Text(label, style = GlassText.Meta, color = palette.InkMid)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    Row(
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 3.5.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.Accent)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(Locale.getDefault()),
            style = GlassText.Section,
            color = palette.InkMid
        )
    }
}

/** 自定义日期范围：液态玻璃面板内的 Material3 DateRangePicker，时区与统计引擎一致（北京时间）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangePanel(
    backdrop: com.kyant.backdrop.Backdrop,
    initialFromMillis: Long,
    initialToMillis: Long,
    onConfirm: (from: String, to: String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalGlassPalette.current
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFromMillis,
        initialSelectedEndDateMillis = initialToMillis
    )
    // millis -> yyyy-MM-dd（北京时间）
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = StatsEngine.beijingTz }
    }
    Column(
        Modifier
            .fillMaxWidth(0.94f)
            .glassCard(backdrop, cornerRadius = 24.dp)
            .padding(horizontal = 6.dp, vertical = 10.dp)
    ) {
        DateRangePicker(
            state = pickerState,
            showModeToggle = false,
            title = {
                Text(
                    "选择自定义范围",
                    style = GlassText.Title,
                    color = palette.InkHi,
                    modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp)
                )
            },
            headline = null,
            modifier = Modifier.height(340.dp),
            colors = DatePickerDefaults.colors(
                containerColor = Color.Transparent,
                titleContentColor = palette.InkHi,
                headlineContentColor = palette.InkHi,
                weekdayContentColor = palette.InkMid,
                subheadContentColor = palette.InkMid,
                navigationContentColor = palette.InkMid,
                yearContentColor = palette.InkMid,
                currentYearContentColor = palette.InkHi,
                selectedYearContentColor = palette.InkStrong,
                selectedYearContainerColor = palette.Accent.copy(alpha = 0.85f),
                dayContentColor = palette.InkMid,
                disabledDayContentColor = palette.InkLo,
                selectedDayContentColor = palette.InkStrong,
                disabledSelectedDayContentColor = palette.InkLo,
                selectedDayContainerColor = palette.Accent.copy(alpha = 0.85f),
                disabledSelectedDayContainerColor = palette.Accent.copy(alpha = 0.3f),
                todayContentColor = palette.Accent,
                todayDateBorderColor = palette.Accent,
                dayInSelectionRangeContentColor = palette.InkHi,
                dayInSelectionRangeContainerColor = palette.Accent.copy(alpha = 0.10f),
                dividerColor = palette.InkLo
            )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text("取消", style = GlassText.Label, color = palette.InkMid)
            }
            val ready = pickerState.selectedStartDateMillis != null &&
                pickerState.selectedEndDateMillis != null
            TextButton(
                enabled = ready,
                onClick = {
                    val s = pickerState.selectedStartDateMillis!!
                    val e = pickerState.selectedEndDateMillis!!
                    onConfirm(fmt.format(Date(s)), fmt.format(Date(e)))
                }
            ) {
                Text("确定", style = GlassText.Label, color = if (ready) palette.Accent else palette.InkLo)
            }
        }
    }
}

/** 首次加载骨架屏：三段呼吸玻璃占位（复用 glassCard，与正文卡片同构）。 */
@Composable
private fun SkeletonDashboard(wide: Boolean, backdrop: com.kyant.backdrop.Backdrop) {
    val palette = LocalGlassPalette.current
    val alpha = rememberInfiniteTransition(label = "skeleton")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeletonAlpha"
        )
    Column(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(0),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 概览卡
        Column(
            Modifier
                .fillMaxWidth()
                .glassCard(backdrop)
                .padding(20.dp)
        ) {
            SkeletonBlock(palette, alpha.value, width = 0.42f, height = 13.dp)
            Spacer(Modifier.height(14.dp))
            SkeletonBlock(palette, alpha.value, width = 0.62f, height = if (wide) 52.dp else 42.dp)
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(palette, alpha.value, width = 1f, height = 15.dp)
        }
        // 趋势卡
        Column(
            Modifier
                .fillMaxWidth()
                .glassCard(backdrop)
                .padding(18.dp)
        ) {
            SkeletonBlock(palette, alpha.value, width = 0.3f, height = 12.dp)
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(palette, alpha.value, width = 1f, height = if (wide) 150.dp else 120.dp)
        }
        if (!wide) {
            // 分布卡
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassCard(backdrop)
                    .padding(18.dp)
            ) {
                SkeletonBlock(palette, alpha.value, width = 0.3f, height = 12.dp)
                Spacer(Modifier.height(16.dp))
                SkeletonBlock(palette, alpha.value, width = 1f, height = 90.dp)
            }
        }
    }
}

@Composable
private fun SkeletonBlock(palette: GlassPalette, alpha: Float, width: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.InkHi.copy(alpha = 0.09f * alpha + 0.03f))
    )
}

/** 应用分布：环形图 + 图例 + 列表。 */
@Composable
private fun ToolDistributionSection(
    state: UiState,
    backdrop: com.kyant.backdrop.Backdrop,
    onShowToolDetail: (String) -> Unit
) {
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    val wide = isWideScreen()
    val top = state.toolDistribution.take(8)

    SectionTitle("应用分布", modifier = Modifier.fadeSlideIn(120))
    Column(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(120)
            .glassCard(backdrop)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices = top.map { DonutSlice(it.tool, it.percentage, toolIconFor(it.tool).color) },
                diameter = if (wide) 150.dp else 126.dp,
                stroke = 17.dp,
                centerTop = "${top.size}",
                centerBottom = "应用"
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                top.take(5).forEach { item ->
                    LegendRow(
                        color = toolIconFor(item.tool).color,
                        name = item.tool,
                        pct = item.percentage
                    )
                }
                if (top.size > 5) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(palette.InkLo)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "其他 ${top.size - 5} 个应用",
                            style = GlassText.ChartAxis,
                            color = palette.InkMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(11.dp))
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        top.forEachIndexed { index, item ->
            GlassListRow(
                backdrop = backdrop,
                icon = toolIconFor(item.tool),
                name = item.tool,
                meta = "${formatTokens(item.tokens)} tokens · " +
                    String.format(Locale.US, "%.1f", item.percentage) + "%",
                value = formatCost(item.cost),
                active = state.toolDetail?.tool == item.tool,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShowToolDetail(item.tool)
                },
                delayMs = 160 + index * 45
            )
        }
    }
}

/** 模型消耗：列表 + 行内占比进度条。 */
@Composable
private fun ModelCostSection(
    state: UiState,
    backdrop: com.kyant.backdrop.Backdrop,
    onShowModelDetail: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val list = state.modelCosts.take(10)
    val maxCost = list.maxOfOrNull { it.cost } ?: 0.0

    SectionTitle("模型消耗", modifier = Modifier.fadeSlideIn(140))
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        list.forEachIndexed { index, item ->
            GlassListRow(
                backdrop = backdrop,
                icon = toolIconFor(item.model),
                name = item.model,
                meta = "${formatTokens(item.tokens)} tokens",
                value = formatCost(item.cost),
                progress = if (maxCost > 0.0) (item.cost / maxCost).toFloat().coerceIn(0.02f, 1f) else null,
                active = state.modelDetail?.model == item.model,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShowModelDetail(item.model)
                },
                delayMs = 180 + index * 45
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, name: String, pct: Float) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = GlassText.ChartAxis.copy(fontSize = 12.5.sp),
            color = palette.InkHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            String.format(Locale.US, "%.0f%%", pct),
            style = GlassText.ChartAxis,
            color = palette.InkMid
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassListRow(
    backdrop: com.kyant.backdrop.Backdrop,
    icon: ToolIcon,
    name: String,
    meta: String,
    value: String,
    compact: Boolean = false,
    active: Boolean = false,
    progress: Float? = null,
    delayMs: Int = 0,
    onLongPress: (() -> Unit)? = null
) {
    val palette = LocalGlassPalette.current
    // 长按激活时 Q 弹放大 + 投影增强（iOS 3D Touch 手感）
    val scale by animateFloatAsState(
        targetValue = if (active) 1.045f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "rowPressScale"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(delayMs)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (active) 26.dp.toPx() else 0f
            }
            .glassRow(backdrop)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = if (compact) 15.dp else 17.dp,
                vertical = if (compact) 11.dp else 14.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(icon.color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = if (icon.drawable != null) {
                        painterResource(icon.drawable)
                    } else {
                        rememberVectorPainter(icon.vector!!)
                    },
                    contentDescription = icon.label,
                    tint = if (icon.colorful) Color.Unspecified else icon.color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    name,
                    style = GlassText.Body,
                    color = palette.InkHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = GlassText.Meta,
                    color = palette.InkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.92f)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.InkLo.copy(alpha = 0.55f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(palette.Accent, Color(0xFF5C7FFF))
                                    )
                                )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = GlassText.Numeric, color = palette.InkHi)
    }
}

@Composable
private fun StatusCard(text: String, backdrop: com.kyant.backdrop.Backdrop) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(100)
            .glassRow(backdrop)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = GlassText.Label, color = palette.InkMid)
    }
}

/** 粒度降级提示：玻璃质感的小信息条，不打断主内容。 */
@Composable
private fun GranularityNote(text: String, backdrop: com.kyant.backdrop.Backdrop) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .glassRow(backdrop, cornerRadius = 14.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = null,
            tint = palette.AccentInk,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = GlassText.Meta,
            color = palette.InkMid,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 长按模型详情：与 ToolDetailOverlay 同款 3D Touch 玻璃弹窗，
 * 额外突出「缓存命中率」——大号百分比 + 渐变进度条 + 输入/缓存对比。
 */
@Composable
private fun ModelDetailOverlay(
    detail: ModelDetail,
    icon: ToolIcon,
    backdrop: com.kyant.backdrop.Backdrop,
    onDismiss: () -> Unit
) {
    val palette = LocalGlassPalette.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val dimAlpha by animateFloatAsState(
        targetValue = if (shown) 0.45f else 0f,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "modelDim"
    )
    val cardScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "modelCardScale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "modelCardAlpha"
    )
    val ripple = remember { MutableInteractionSource() }
    val hitRate = detail.cacheHitRate.coerceIn(0f, 100f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1520).copy(alpha = dimAlpha))
            .clickable(ripple, null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    alpha = cardAlpha
                }
                .glassCard(backdrop, cornerRadius = 30.dp)
                .clickable(ripple, null, onClick = {})
                .padding(24.dp)
        ) {
            // 头部：品牌 Logo 大块 + 模型名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(icon.color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = if (icon.drawable != null) {
                            painterResource(icon.drawable)
                        } else {
                            rememberVectorPainter(icon.vector!!)
                        },
                        contentDescription = icon.label,
                        tint = if (icon.colorful) Color.Unspecified else icon.color,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.model,
                        style = GlassText.Title,
                        color = palette.InkHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${detail.toolCount} 个应用 · ${detail.sessionCount} 个会话",
                        style = GlassText.Meta,
                        color = palette.InkMid
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // 消耗金额 + 占比 Chip
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("消耗", style = GlassText.Label, color = palette.InkMid)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCounter(
                            value = detail.cost.toFloat(),
                            format = { formatCost(it.toDouble()) },
                            style = GlassText.Hero.copy(fontSize = 38.sp),
                            color = palette.InkHi
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "USD",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = palette.InkMid,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(palette.AccentWash)
                        .border(1.dp, palette.AccentRim, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        String.format(Locale.US, "占比 %.1f%%", detail.percentage),
                        style = GlassText.Chip,
                        color = palette.AccentInk
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 占比进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.InkLo.copy(alpha = 0.45f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((detail.percentage / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF0FC6B6), Color(0xFF5C7FFF)))
                        )
                )
            }

            Spacer(Modifier.height(20.dp))

            // 缓存命中率：核心指标，大号高亮展示
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("缓存命中率", style = GlassText.Label, color = palette.InkMid)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            String.format(Locale.US, "%.1f%%", hitRate),
                            style = GlassText.Hero.copy(fontSize = 36.sp),
                            color = if (hitRate >= 50f) palette.AccentInk else palette.InkHi
                        )
                    }
                }
                Text(
                    "${formatTokens(detail.cachedTokens)} / ${formatTokens(detail.inputTokens + detail.cachedTokens)}",
                    style = GlassText.Meta,
                    color = palette.InkMid
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.InkLo.copy(alpha = 0.45f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(hitRate / 100f)
                        .height(8.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF0FC6B6), Color(0xFF7EE8DF)))
                        )
                )
            }

            Spacer(Modifier.height(20.dp))

            // 细分指标 2×2 网格
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("总 Tokens", formatTokens(detail.tokens), palette)
                DetailMetric("输入", formatTokens(detail.inputTokens), palette)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("输出", formatTokens(detail.outputTokens), palette)
                DetailMetric("缓存", formatTokens(detail.cachedTokens), palette)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("推理", formatTokens(detail.reasoningTokens), palette)
                DetailMetric("应用数", detail.toolCount.toString(), palette)
            }
        }
    }
}

/** 长按应用详情（iOS 3D Touch 风格）：暗化磨砂背景 + Q 弹玻璃卡 + 细分指标。 */
@Composable
private fun ToolDetailOverlay(
    detail: ToolDetail,
    icon: ToolIcon,
    backdrop: com.kyant.backdrop.Backdrop,
    onDismiss: () -> Unit
) {
    val palette = LocalGlassPalette.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val dimAlpha by animateFloatAsState(
        targetValue = if (shown) 0.45f else 0f,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "dim"
    )
    val cardScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardScale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "cardAlpha"
    )
    val ripple = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1520).copy(alpha = dimAlpha))
            .clickable(ripple, null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    alpha = cardAlpha
                }
                .glassCard(backdrop, cornerRadius = 30.dp)
                .clickable(ripple, null, onClick = {})
                .padding(24.dp)
        ) {
            // 头部：品牌 Logo 大块 + 应用名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(icon.color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = if (icon.drawable != null) {
                            painterResource(icon.drawable)
                        } else {
                            rememberVectorPainter(icon.vector!!)
                        },
                        contentDescription = icon.label,
                        tint = if (icon.colorful) Color.Unspecified else icon.color,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.tool,
                        style = GlassText.Title,
                        color = palette.InkHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${detail.modelCount} 个模型 · ${detail.sessionCount} 个会话",
                        style = GlassText.Meta,
                        color = palette.InkMid
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // 消耗金额 + 占比 Chip
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("消耗", style = GlassText.Label, color = palette.InkMid)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCounter(
                            value = detail.cost.toFloat(),
                            format = { formatCost(it.toDouble()) },
                            style = GlassText.Hero.copy(fontSize = 38.sp),
                            color = palette.InkHi
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "USD",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = palette.InkMid,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(palette.AccentWash)
                        .border(1.dp, palette.AccentRim, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        String.format(Locale.US, "占比 %.1f%%", detail.percentage),
                        style = GlassText.Chip,
                        color = palette.AccentInk
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 占比进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.InkLo.copy(alpha = 0.45f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((detail.percentage / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF0FC6B6), Color(0xFF5C7FFF)))
                        )
                )
            }

            Spacer(Modifier.height(20.dp))

            // 细分指标 2×2 网格
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("总 Tokens", formatTokens(detail.tokens), palette)
                DetailMetric("输入", formatTokens(detail.inputTokens), palette)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("输出", formatTokens(detail.outputTokens), palette)
                DetailMetric("缓存", formatTokens(detail.cachedTokens), palette)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("推理", formatTokens(detail.reasoningTokens), palette)
                DetailMetric("会话数", detail.sessionCount.toString(), palette)
            }
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, palette: GlassPalette) {
    Column {
        Text(value, style = GlassText.NumericSmall, color = palette.InkHi)
        Spacer(Modifier.height(2.dp))
        Text(label, style = GlassText.Meta, color = palette.InkMid)
    }
}

// ---------- 工具函数 ----------

internal fun formatCost(value: Double): String = when {
    value >= 1000 -> "$" + String.format(Locale.US, "%.0f", value)
    value >= 100 -> "$" + String.format(Locale.US, "%.1f", value)
    else -> "$" + String.format(Locale.US, "%.2f", value)
}

internal fun formatTokens(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}
