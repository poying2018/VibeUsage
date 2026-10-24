package ai.vibecafe.usage.ui

import ai.vibecafe.usage.BuildConfig
import ai.vibecafe.usage.R
import ai.vibecafe.usage.core.BackgroundStore
import ai.vibecafe.usage.core.ThemeMode
import ai.vibecafe.usage.stats.ModelCost
import ai.vibecafe.usage.stats.ModelDetail
import ai.vibecafe.usage.stats.StatsEngine
import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.stats.ToolDetail
import ai.vibecafe.usage.stats.ToolDistribution
import ai.vibecafe.usage.ui.anim.AnimatedCounter
import ai.vibecafe.usage.ui.anim.fadeSlideIn
import ai.vibecafe.usage.ui.ag.AgPanelScreen
import ai.vibecafe.usage.ui.ag.ExtraProvider
import ai.vibecafe.usage.ui.ag.ExtraQuotaViewModel
import ai.vibecafe.usage.ui.ag.ProviderPage
import ai.vibecafe.usage.ui.ag.QuotaOption
import ai.vibecafe.usage.ui.ag.QuotaOverviewSection
import ai.vibecafe.usage.ui.ag.QuotaSwitcher
import ai.vibecafe.usage.ui.ag.AgPanelViewModel
import ai.vibecafe.usage.ui.charts.DonutChart
import ai.vibecafe.usage.ui.charts.DonutSlice
import ai.vibecafe.usage.ui.charts.TrendChart
import ai.vibecafe.usage.ui.charts.TrendMetric
import ai.vibecafe.usage.ui.glass.GlassBackground
import ai.vibecafe.usage.ui.glass.LiquidBottomTab
import ai.vibecafe.usage.ui.glass.LiquidBottomTabs
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/** 应用/模型列表的排序口径。 */
private enum class ListSort(val label: String) {
    TOKENS("Tokens"),
    COST("消耗"),
    NAME("名称")
}

/** 应用（工具）列表排序：按 Tokens / 消耗 / 名称。 */
private fun toolComparator(sort: ListSort): Comparator<ToolDistribution> =
    when (sort) {
        ListSort.TOKENS -> compareByDescending { it.tokens }
        ListSort.COST -> compareByDescending { it.cost }
        ListSort.NAME -> compareBy { it.tool }
    }

/** 模型列表排序：按 Tokens / 消耗 / 名称。 */
private fun modelComparator(sort: ListSort): Comparator<ModelCost> =
    when (sort) {
        ListSort.TOKENS -> compareByDescending { it.tokens }
        ListSort.COST -> compareByDescending { it.cost }
        ListSort.NAME -> compareBy { it.model }
    }

/** 排序用的小胶囊按钮（液态玻璃底 + 选中态品牌色）。 */
@Composable
private fun SortChip(
    text: String,
    active: Boolean,
    backdrop: com.kyant.backdrop.Backdrop,
    onClick: () -> Unit
) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.Accent.copy(alpha = 0.16f) else palette.InkHi.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = GlassText.ChartAxis.copy(fontSize = 11.sp),
            color = if (active) palette.Accent else palette.InkMid,
            maxLines = 1
        )
    }
}

/** 额度页供应商下拉选项：反重力 + 凭据型平台（同为稳定引用） */
private val QuotaOptions: List<QuotaOption> =
    listOf(QuotaOption("反重力", Color(0xFF9B6CFF))) +
        ExtraProvider.entries.map { QuotaOption(it.title, it.color) }

private val APP_VERSION = "v" + BuildConfig.VERSION_NAME

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
    onShareCard: () -> Unit = {},
    onSelectDevices: (Set<String>) -> Unit = {},
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

    var bgPath by rememberSaveable { mutableStateOf(BackgroundStore.get(context)) }
    var showSettings by rememberSaveable { mutableStateOf(false) }  // 设置独立页（底部导航 Tab 3）
    var showAgPanel by rememberSaveable { mutableStateOf(false) }  // 反重力额度面板切换
    var quotaTab by rememberSaveable { mutableStateOf(0) }  // 额度页平台切换：0 反重力 / 1+ ExtraProvider（旧存档可能越界，使用处钳制）
    var switcherExpanded by remember { mutableStateOf(false) }  // 平台下拉展开态（提升到页面级：空白处点击收起用）
    var trendMetric by rememberSaveable { mutableStateOf(TrendMetric.COST) }  // 趋势图纵轴指标
    var showCustomPicker by rememberSaveable { mutableStateOf(false) }  // 自定义日期范围对话框
    var showDevicePicker by rememberSaveable { mutableStateOf(false) }  // 设备筛选对话框
    // 应用/模型列表的排序方式：默认按 Tokens（消耗口径）
    var listSort by rememberSaveable { mutableStateOf(ListSort.TOKENS) }
    // 是否展开全部条目（默认只列前几个）
    var showAllTools by rememberSaveable { mutableStateOf(false) }
    var showAllModels by rememberSaveable { mutableStateOf(false) }
    val agPanelViewModel: AgPanelViewModel = viewModel()  // 与 AgPanelScreen 共享同一实例
    val agPanelState by agPanelViewModel.state.collectAsStateWithLifecycle()
    // 凭据型供应商的额度 ViewModel：与 ProviderPage 内部的是同一实例（同一 Activity 作用域 + 同默认 key），
    // 额度页的下拉刷新/头部刷新按钮要能刷到**当前选中的供应商**，而不是永远刷反重力。
    val extraQuotaViewModel: ExtraQuotaViewModel = viewModel()
    val extraQuotaState by extraQuotaViewModel.state.collectAsStateWithLifecycle()
    val wide = isWideScreen()
    val pullState = rememberPullToRefreshState()

    // 排序后的应用/模型列表。
    // 必须在 composable 作用域算：LazyColumn 的内容 lambda 是 LazyListScope（不是 @Composable），
    // 里面不能调 remember。
    val sortedTools = remember(state.toolDistribution, listSort) {
        state.toolDistribution.sortedWith(toolComparator(listSort))
    }
    val sortedModels = remember(state.modelCosts, listSort) {
        state.modelCosts.sortedWith(modelComparator(listSort))
    }
    val canExpand = state.toolDistribution.size > 8 || state.modelCosts.size > 10

    // 当前额度页选中的平台：0 反重力，1+ 对应 ExtraProvider
    val currentQuotaTab = quotaTab.coerceIn(0, QuotaOptions.lastIndex)
    val currentProvider: ExtraProvider? =
        if (currentQuotaTab == 0) null else ExtraProvider.entries.getOrNull(currentQuotaTab - 1)

    /** 额度页的刷新：按当前选中的平台刷，而不是固定刷反重力。 */
    val refreshQuotaTarget: () -> Unit = {
        val p = currentProvider
        if (p != null) extraQuotaViewModel.refresh(p.id)
        else agPanelViewModel.refresh()
    }

    /** 额度页是否正在刷新（供下拉刷新的指示器用）。 */
    val quotaRefreshing: Boolean = if (showAgPanel) {
        if (currentProvider != null) extraQuotaState[currentProvider.id]?.isLoading == true
        else agPanelState.isLoading
    } else {
        state.isRefreshing
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) bgPath = BackgroundStore.set(context, uri)
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(
            backdrop,
            imagePath = bgPath
        )

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
            isRefreshing = quotaRefreshing,
            onRefresh = {
                if (showAgPanel) refreshQuotaTarget()
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
                        isRefreshing = quotaRefreshing,
                        state = pullState
                    )
                }
            }
        ) {
        // 惰性滚动容器。手机单列的列表行（18 条，每条都是独立玻璃面）被提升为 LazyColumn
        // 的 item：滚出视口的行不再参与绘制与图层录制 —— 这是本次结构改造的全部意义。
        // 顶部内容仍放在同一个 item 内的 Column 里，保留原来的 20dp 间距。
        LazyColumn(
            Modifier
                .fillMaxSize()
                // 下拉展开时：点页面任意空白处（卡片、背景等未消费点击的区域）自动收起；
                // 菜单行/按钮等自身可点的控件会先消费点击，不会误触
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (switcherExpanded) switcherExpanded = false
                    }
                },
            // 内边距必须走 contentPadding：Modifier.padding 会缩小 LazyColumn 自己的视口，
            // 内容滚不到那段内边距里，也就没有任何内容能落到悬浮玻璃下方被实时模糊
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 18.dp,
                // 底部留出「底部导航栏 64+14 +（概览页：时间选择条 58+10）+ 呼吸」的空间：
                // 内容可滚动到悬浮玻璃下方被实时模糊，滚到底时最后一行仍不会被遮挡
                bottom = insets.calculateBottomPadding() +
                        (if (!showAgPanel && !showSettings) 176.dp else 106.dp),
                start = 18.dp,
                end = 18.dp
            ),
            // 11dp = 原列表内层 Column 的行间距；非行 item 需要的 20dp 由它们自己补足
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
        item {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Header(
                onRefresh = {
                    if (showAgPanel) refreshQuotaTarget()
                    else onRefresh()
                },
                backdrop = backdrop,
                wide = wide,
                refreshing = quotaRefreshing,
                deviceCount = state.availableDevices.size,
                deviceFilterActive = state.selectedDevices.isNotEmpty(),
                onOpenDevicePicker = { showDevicePicker = true }
            )

            // ---- 设备筛选生效提示：告诉用户当前只统计了哪几台设备 ----
            if (state.selectedDevices.isNotEmpty()) {
                GranularityNote(
                    "仅统计 ${state.selectedDevices.size}/${state.availableDevices.size} 台设备：" +
                        state.selectedDevices.joinToString("、"),
                    backdrop
                )
            }

            // ---- 降级提示：24 小时粒度不可用时友好告知（不影响主内容展示）----
            state.granularityNote?.let { note ->
                GranularityNote(note, backdrop)
            }

            // ---- 刷新失败提示：已有数据时保留旧内容，但要把失败原因说出来 ----
            // （此前只处理了「失败且没有任何数据」的情况，失败但有旧数据时用户毫无感知）
            if (state.error != null && state.stats != null) {
                ErrorNote("刷新失败：${state.error}（当前显示的是上次的数据）", backdrop)
            }

            // ---- 设置页 / DS+ Milky 面板 / 主内容切换 ----
            if (showSettings) {
                SettingsScreen(
                    backdrop = backdrop,
                    showReset = bgPath != null,
                    onCustomBackground = { pickImage.launch("image/*") },
                    onResetBackground = {
                        BackgroundStore.clear(context)
                        bgPath = null
                    },
                    onShareCard = onShareCard,
                    onLogout = onLogout,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    update = state.update,
                    onCheckUpdate = onCheckUpdate,
                    onDownloadUpdate = onDownloadUpdate,
                    onInstallUpdate = onInstallUpdate,
                    appVersion = APP_VERSION
                )
            } else if (showAgPanel) {
                Column {
                    // 已接入平台总览：一眼看到各平台额度摘要 / 近 7 天趋势 / 失败状态，
                    // 并支持「刷新全部」一次把 13 家都拉一遍（原先要逐个点下拉才知道谁失效了）
                    QuotaOverviewSection(
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 平台切换器：液态玻璃下拉。菜单只能用页面 backdrop——
                    // scrimBackdrop 含 contentBackdrop 捕获层，而本切换器就在捕获子树内，
                    // 自己画自己会让 RenderNode prepare 无限递归（RenderThread 栈溢出闪退）
                    QuotaSwitcher(
                        options = QuotaOptions,
                        selectedIndex = currentQuotaTab,
                        onSelect = { idx ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            quotaTab = idx
                        },
                        buttonBackdrop = backdrop,
                        menuBackdrop = backdrop,
                        expanded = switcherExpanded,
                        onExpandedChange = { switcherExpanded = it }
                    ) {
                        when (val tab = currentQuotaTab) {
                            0 -> AgPanelScreen(backdrop = backdrop)
                            // 供应商列表可能随版本增删，旧存档里的下标可能越界 → 取不到就回落到第一个
                            else -> ExtraProvider.entries.getOrNull(tab - 1)
                                ?.let { ProviderPage(it, backdrop = backdrop, viewModel = extraQuotaViewModel) }
                                ?: StatusCard("该平台已下线，请选择其它平台", backdrop)
                        }
                    }
                }
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
                    // 发丝线把「总览」和「分项统计」分成两块：四格统计直接贴在预测行下面，
                    // 数字大、字重高，扫读时容易把上一行的百分比读成这一行的
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 18.dp, bottom = 18.dp),
                        thickness = 1.dp,
                        color = LocalGlassPalette.current.InkLo.copy(alpha = 0.28f)
                    )
                    StatsStrip(state)
                }
            }

            // ---- 时间范围选择（原来浮在屏幕底部）----
            // 浮在底部时它直接压住趋势图的 X 轴；而且底部已经有导航条，
            // 两条全宽玻璃药丸上下叠着，页面显得杂乱、图也看不全。
            // 范围是全局口径，放在总览卡下面、趋势图上面：
            // 既不挡图，顺序也符合「先选范围，再看数」。
            RangeSelectorRow(
                state = state,
                selectedIndex = selectedIndex,
                onSelect = { idx ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectRange(RangeValues[idx])
                },
                backdrop = backdrop
            )

            // ---- 用量趋势：标题与切换器并进图表卡内 ----
            // 原来标题在卡外、图在卡里，看着像两块不相干的东西；
            // 并成一张卡后，趋势区是「一个区块」而不是「一个标题 + 一张卡」。
            Column(
                Modifier
                    .fillMaxWidth()
                    .fadeSlideIn(50)
                    .glassCard(backdrop)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("用量趋势")
                    MetricToggle(
                        selected = trendMetric,
                        onSelect = { m ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            trendMetric = m
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                val series = when (state.selectedTimeRange) {
                    TimeRange.HOURS_24 -> state.hourlyUsage
                    TimeRange.TODAY -> state.todayHourlyUsage
                    else -> state.dailyUsage
                }
                // 粒度说明：24 小时档在小时粒度不可用时会降级成日粒度，
                // 光看图分不清是「24 个点」还是「2 个点」，这里直接说清楚
                if (series.size >= 2) {
                    val granularity = when (state.selectedTimeRange) {
                        TimeRange.HOURS_24 -> "按小时"
                        TimeRange.TODAY -> "今日按小时"
                        else -> "按天"
                    }
                    val pointLabel = when (state.selectedTimeRange) {
                        TimeRange.HOURS_24 -> "${series.size} 个小时点"
                        TimeRange.TODAY -> "${series.size} 个小时点"
                        else -> "${series.size} 天"
                    }
                    Text(
                        "$granularity · $pointLabel",
                        style = GlassText.ChartAxis,
                        color = palette.InkLo,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
                        Column(Modifier.fillMaxWidth()) {
                            // 排序芯片宽屏也要有：否则平板上的模型列表固定按消耗排序，
                            // 跟手机上默认按 Tokens 排对不上，同一份数据两种顺序
                            Box(Modifier.padding(top = 9.dp)) {
                                SortControlBar(
                                    listSort = listSort,
                                    onSortChange = { listSort = it },
                                    canExpand = false,
                                    expanded = showAllTools || showAllModels,
                                    onToggleExpand = {
                                        val expanding = !(showAllTools || showAllModels)
                                        showAllTools = expanding
                                        showAllModels = expanding
                                    },
                                    backdrop = backdrop
                                )
                            }
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
                                        ModelCostSection(
                                            state,
                                            backdrop,
                                            onShowModelDetail,
                                            rows = sortedModels.take(10)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 手机：单列。标题与环形图留在顶部（非惰性），两个长列表的行
                        // 由 LazyColumn 的惰性 item 渲染，滚出视口的行不再绘制
                        if (hasTools) {
                            ToolDistributionSection(state, backdrop, onShowToolDetail, includeRows = false)
                        }
                        if (!hasTools && !hasModels) {
                            StatusCard("当前时间范围内没有用量记录", backdrop)
                        }
                    }
                }
            }
        } // 设置页 / 额度页 / 主内容 的 else 分支结束
        } // 顶部内容 Column 结束
        } // item 结束

        // ---- 手机单列：两个长列表的行提升为惰性 item ----
        // 行是各自独立的玻璃面，放进 itemsIndexed 后滚出视口就不再绘制/录制图层。
        // 间距逐项复刻改造前：行间 11dp（LazyColumn arrangement），
        // 与上方内容、以及模型标题前后的 20dp 由 9dp 补齐（11 + 9 = 20）。
        //
        // 条件里**不再要求 error == null**：刷新失败时 stats 仍然保留旧数据，
        // 之前一并把 error 判空会让应用行/模型行整段消失（同页上半部分的环形图还在），
        // 看起来像数据被清空。失败信息改由上方的错误条单独提示。
        if (!showSettings && !showAgPanel && !wide &&
            !state.isLoading && state.stats != null
        ) {
            // ---- 排序 / 展开控制条：应用与模型列表共用 ----
            // 默认只列前几个，靠排序 + 「显示全部」才能看到长尾（原先完全看不到）
            item {
                Box(Modifier.padding(top = 9.dp)) {
                    SortControlBar(
                        listSort = listSort,
                        onSortChange = { listSort = it },
                        canExpand = canExpand,
                        expanded = showAllTools || showAllModels,
                        onToggleExpand = {
                            val expanding = !(showAllTools || showAllModels)
                            showAllTools = expanding
                            showAllModels = expanding
                        },
                        backdrop = backdrop
                    )
                }
            }

            val toolRows = if (showAllTools) sortedTools else sortedTools.take(8)
            if (toolRows.isNotEmpty()) {
                itemsIndexed(toolRows, key = { _, it -> "tool-${it.tool}" }) { index, item ->
                    Box(Modifier.padding(top = if (index == 0) 9.dp else 0.dp)) {
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
            val modelRows = if (showAllModels) sortedModels else sortedModels.take(10)
            if (modelRows.isNotEmpty()) {
                val maxCost = modelRows.maxOfOrNull { it.cost } ?: 0.0
                item {
                    Box(Modifier.padding(top = 9.dp)) {
                        SectionTitle("模型消耗", modifier = Modifier.fadeSlideIn(140))
                    }
                }
                itemsIndexed(modelRows, key = { _, it -> "model-${it.model}" }) { index, item ->
                    Box(Modifier.padding(top = if (index == 0) 9.dp else 0.dp)) {
                        GlassListRow(
                            backdrop = backdrop,
                            icon = toolIconFor(item.model),
                            name = item.model,
                            meta = "${formatTokens(item.tokens)} tokens",
                            value = formatCost(item.cost),
                            progress = if (maxCost > 0.0) {
                                (item.cost / maxCost).toFloat().coerceIn(0.02f, 1f)
                            } else {
                                null
                            },
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

            // ---- 最近会话：数据层已算好分摊后的会话，之前只显示「会话 N」这个数字 ----
            val sessionRows = state.sessions.take(if (showAllTools || showAllModels) 30 else 8)
            if (sessionRows.isNotEmpty()) {
                item {
                    Box(Modifier.padding(top = 9.dp)) {
                        SectionTitle("最近会话", modifier = Modifier.fadeSlideIn(180))
                    }
                }
                // key 必须带 hostname，还要带下标兜底：
                // 同一台机器上先后开的两次会话（同 source、同 project、同一条消息时间戳）
                // 会撞成同一个 key，LazyColumn 直接抛「Key was already used」把概览页搞崩。
                // 下标保证唯一，内容字段保证同一次刷新内顺序稳定。
                itemsIndexed(sessionRows, key = { index, s ->
                    "session-$index-${s.source}-${s.project}-${s.hostname}-${s.lastMessageAt}"
                }) { index, s ->
                    Box(Modifier.padding(top = if (index == 0) 9.dp else 0.dp)) {
                        // 标题用「项目 · 来源」：同名项目（同一目录开多个工具）光看项目名分不出来
                        val title = if (s.project.isBlank() || s.project == s.source) s.source
                        else "${s.project} · ${s.source}"
                        GlassListRow(
                            backdrop = backdrop,
                            icon = toolIconFor(s.source),
                            name = title,
                            meta = "${s.messageCount} 条消息 · " +
                                formatDuration(s.durationSeconds) + " · " +
                                formatTokens(s.tokens) + " tokens",
                            value = formatCost(s.cost),
                            // 金额放到标题行：会话的 meta（消息数 · 时长 · tokens）本来就长，
                            // 金额再占一列的话 tokens 会被截成「75⋯」
                            valueInline = true,
                            delayMs = 200 + index * 35
                        )
                    }
                }
            }
        }
        } // LazyColumn 结束
        } // PullToRefreshBox 结束
        } // 内容导出 Box 结束

        // 时间范围选择器已挪进内容流（总览卡下方）—— 底部只留导航条，
        // 不再有两条全宽玻璃药丸上下叠着、挡住趋势图 X 轴的情况。

        // ---- 底部液态玻璃导航栏（酷安同款 LiquidBottomTabs）：概览 / 额度 / 设置 ----
        // 三层结构：胶囊玻璃壳(64dp, blur8+lens24) -> 隐藏的 Accent 色内容层(供滑块折射) ->
        // 纯折射滑块(56dp, 按压折射拉满+色散)。选中索引由既有状态派生：设置展开=2 / 额度面板=1 / 概览=0，
        // 与表头按钮、设置菜单状态完全互通。
        // 跟随应用内主题（InkHi：亮色≈近黑 / 暗色≈近白）
        val tabContentColor = LocalGlassPalette.current.InkHi
        val onSelectTab: (Int) -> Unit = { idx ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            when (idx) {
                0 -> {
                    showAgPanel = false
                    showSettings = false
                }
                1 -> {
                    showAgPanel = true
                    showSettings = false
                }
                else -> {
                    showSettings = true
                    showAgPanel = false
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = insets.calculateBottomPadding() + 14.dp)
        ) {
            LiquidBottomTabs(
                selectedTabIndex = {
                    when {
                        showSettings -> 2
                        showAgPanel -> 1
                        else -> 0
                    }
                },
                onTabSelected = onSelectTab,
                backdrop = scrimBackdrop,  // 背景+内容组合层：滚动内容实时进入玻璃模糊
                tabsCount = 3
            ) {
                LiquidBottomTab({ onSelectTab(0) }) {
                    Icon(
                        Icons.Filled.DonutLarge,
                        null,
                        Modifier.size(22.dp),
                        tint = tabContentColor
                    )
                    Text("概览", style = TextStyle(tabContentColor, 11.sp))
                }
                LiquidBottomTab({ onSelectTab(1) }) {
                    Icon(
                        Icons.Filled.PieChart,
                        null,
                        Modifier.size(22.dp),
                        tint = tabContentColor
                    )
                    Text("额度", style = TextStyle(tabContentColor, 11.sp))
                }
                LiquidBottomTab({ onSelectTab(2) }) {
                    Icon(
                        Icons.Filled.Settings,
                        null,
                        Modifier.size(22.dp),
                        tint = tabContentColor
                    )
                    Text("设置", style = TextStyle(tabContentColor, 11.sp))
                }
            }
        }


        // 自定义日期范围选择（与设置菜单同构：屏内 scrim + 液态玻璃面板，保持真实模糊层级）
        if (showCustomPicker) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { showCustomPicker = false }
            )
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 面板高度随可用高度收缩：竖屏 640dp 够放下整月，横屏 360dp 只够两三周，
                // 写死 400dp 会把「取消/确定」顶到屏幕外（横屏实测点不到）。
                val pickerHeight = (maxHeight - 100.dp).coerceIn(200.dp, 400.dp)
                CustomRangePanel(
                    backdrop = scrimBackdrop,
                    pickerHeight = pickerHeight,
                    onConfirm = { from, to ->
                        showCustomPicker = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectCustomRange(from, to)
                    },
                    onDismiss = { showCustomPicker = false }
                )
            }
        }

        // 设备筛选对话框（与自定义范围同层：scrim + 液态玻璃面板）
        if (showDevicePicker) {
            DevicePickerDialog(
                devices = state.availableDevices,
                selected = state.selectedDevices,
                backdrop = scrimBackdrop,
                onDismiss = { showDevicePicker = false },
                onConfirm = { picked ->
                    showDevicePicker = false
                    onSelectDevices(picked)
                }
            )
        }

        // 长按应用详情（3D Touch 风格）
        state.toolDetail?.let { detail ->
            ToolDetailOverlay(
                detail = detail,
                icon = toolIconFor(detail.tool),
                backdrop = scrimBackdrop,
                onDismiss = onHideToolDetail
            )
        }

        // 长按模型详情：同风格弹窗，额外突出缓存命中率
        state.modelDetail?.let { detail ->
            ModelDetailOverlay(
                detail = detail,
                icon = toolIconFor(detail.model),
                backdrop = scrimBackdrop,
                onDismiss = onHideModelDetail
            )
        }
    }
}

@Composable
private fun Header(
    onRefresh: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    wide: Boolean = false,
    refreshing: Boolean = false,
    deviceCount: Int = 0,
    deviceFilterActive: Boolean = false,
    onOpenDevicePicker: () -> Unit = {}
) {
    val palette = LocalGlassPalette.current
    // 表头日期每次重组现算：`remember` 缓存的话，App 常驻不杀、跨过零点后
    // 组合不销毁，这里就一直显示昨天的日期。
    val today = currentDateLabel()
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
                    .size(if (wide) 48.dp else 40.dp)
                    .clip(RoundedCornerShape(if (wide) 16.dp else 13.dp))
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
                    modifier = Modifier.size(if (wide) 28.dp else 24.dp)
                )
            }
            Spacer(Modifier.width(if (wide) 14.dp else 12.dp))
            Column {
                Text(
                    "VibeUsage",
                    style = GlassText.Title,
                    // 标题原来 23sp + 46dp logo，占掉首屏近四分之一屏，
                    // 真正的数据被挤到下一屏。收紧到 20sp/40dp。
                    fontSize = if (wide) 25.sp else 20.sp,
                    color = palette.InkHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                // 窄屏(竖屏)下省略日期，保证「用量概览 · 版本号」完整显示；
                // 宽屏空间充足时保留完整信息
                Text(
                    if (wide) "用量概览 · $APP_VERSION · $today"
                    else "用量概览 · $APP_VERSION",
                    style = GlassText.Label,
                    fontSize = if (wide) 12.5.sp else 11.sp,
                    color = palette.InkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 设备筛选：只有一台设备时没有意义，隐藏以免占位
            if (deviceCount > 1) {
                IconGlassButton(
                    imageVector = Icons.Filled.Devices,
                    contentDescription = "设备筛选",
                    backdrop = backdrop,
                    onClick = onOpenDevicePicker,
                    active = deviceFilterActive
                )
            }
            IconGlassButton(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新",
                backdrop = backdrop,
                onClick = onRefresh,
                spinning = refreshing
            )
            // 设置 / 额度入口已统一移至底部液态玻璃导航栏，避免重复入口
        }
    }
}

@Composable
private fun IconGlassButton(
    imageVector: ImageVector,
    contentDescription: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onClick: () -> Unit,
    spinning: Boolean = false,
    active: Boolean = false
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
            // 选中态（设备筛选生效时）用品牌色描边 + 品牌色图标区分
            .then(
                if (active) {
                    Modifier.border(1.5.dp, palette.Accent, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
            .clickable(interaction, null, enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (active) palette.Accent else palette.InkHi,
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
                    // 同年时省略结束日期的年份（2026/11/01 ~ 11/05），否则整行会被省略号切掉
                    customActive && state.customRange != null -> {
                        val from = state.customRange.from
                        val to = state.customRange.to
                        val toShort = if (from.take(4) == to.take(4)) to.drop(5) else to
                        "总消耗（${from.replace("-", "/")} ~ ${toShort.replace("-", "/")}）"
                    }
                    else -> "总消耗（当前范围）"
                },
                style = GlassText.Label,
                color = palette.InkMid,
                // 自定义范围时标题会变长（2026/11/01 ~ 2026/11/05），先把剩余宽度给标题，
                // 否则右侧药丸被挤成两行（「自定」+「义」）
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
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
                    ),
                    maxLines = 1,
                    softWrap = false
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
                    style = GlassText.Hero.copy(fontSize = if (wide) 54.sp else 42.sp),
                    color = palette.InkHi
                )
                Spacer(Modifier.width(5.dp))
                // 「USD」原来 15sp ExtraBold 挂在数字右边，视觉重量接近数字本身，
                // 看着像数字的一部分。降到 11sp 并用弱色，只做单位标注。
                Text(
                    "USD",
                    fontFamily = HanSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (wide) 12.sp else 11.sp,
                    color = palette.InkLo,
                    modifier = Modifier.padding(bottom = if (wide) 6.dp else 4.dp)
                )
            }
            if (trend != null) TrendChip(trend)
        }
        // 行 3：本月消耗 + 按日均推算的整月预测（没有推算也要保持高度一致避免抖动）
        Spacer(Modifier.height(if (wide) 8.dp else 5.dp))
        val mp = state.monthProjection
        if (mp != null) {
            Text(
                "本月已用 " + formatCost(mp.monthCost) + " · 按日均预测整月 " + formatCost(mp.projected),
                style = GlassText.Meta,
                color = palette.InkLo
            )
        } else {
            // 用透明文字占据相同高度
            Text(
                "本月已用",
                style = GlassText.Meta,
                color = androidx.compose.ui.graphics.Color.Transparent
            )
        }
    }
}

@Composable
private fun TrendChip(trend: Float) {
    val palette = LocalGlassPalette.current
    val up = trend >= 0f
    // 语义要对：消耗上升 = 花得更多，走警示色；消耗下降 = 花得更少，走品牌青。
    // 原来反着来——下降穿警告色，看着像「出事了」，其实那是省钱。
    val bg = if (up) palette.DownWash else palette.AccentWash
    val rim = if (up) palette.DownRim else palette.AccentRim
    val ink = if (up) palette.Down else palette.AccentInk
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, rim, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (up) "↗" else "↘",
                fontFamily = HanSansFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ink,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(4.dp))
            Text(
                String.format(Locale.US, "%+.0f%%", trend * 100),
                // 趋势是次要信息：原来 13sp Black + 大内边距，视觉重量逼近主数字，
                // 抢掉了「$66.18」的注意力。降到 12sp 并收窄内边距。
                style = GlassText.Chip.copy(fontSize = 12.sp),
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
        MiniStat("Tokens", formatTokens(stats?.totalTokens ?: 0L), palette, primary = true)
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
            MiniStat("Tokens", formatTokens(stats?.totalTokens ?: 0L), palette, primary = true)
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

/**
 * 四格统计里的一格。
 *
 * Tokens 是首屏最该被读到的量，给它更大字号；其余三项是计数，
 * 缩小一档并把标签压到最弱色，扫读时眼睛自然先落在 Tokens 上。
 */
@Composable
private fun MiniStat(label: String, value: String, palette: GlassPalette, primary: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            value,
            style = GlassText.NumericSmall.copy(fontSize = if (primary) 23.sp else 19.sp),
            color = palette.InkHi
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = GlassText.Meta.copy(fontSize = 11.sp),
            color = palette.InkLo
        )
    }
}

/**
 * 设备筛选对话框：多选设备，限定统计范围。
 *
 * 语义约定：全不选 = 不筛选（统计所有设备），而不是「统计 0 台」——
 * 这样用户取消筛选时不需要额外操作。
 */
@Composable
private fun DevicePickerDialog(
    devices: List<String>,
    selected: Set<String>,
    backdrop: com.kyant.backdrop.Backdrop,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val palette = LocalGlassPalette.current
    // 本地草稿：改动先落在这里，点「应用」才提交。
    // key 只能是 selected：devices 进了 key 的话，后台刷新把设备列表更新一下，
    // 草稿就被重建，用户已经勾上的复选框被静默清空。
    // 弹窗关闭时这个组合被销毁，下次打开自然重新取当前选中项。
    var draft by remember(selected) { mutableStateOf(selected) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1520).copy(alpha = 0.45f))
            .clickable(interactionSource = null, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .glassCard(backdrop, cornerRadius = 24.dp)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                // 吞掉面板内的点击，避免冒泡到遮罩把面板关掉
                .clickable(interactionSource = null, indication = null) { }
        ) {
            Text("设备筛选", style = GlassText.Title, color = palette.InkHi)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择要统计的设备；不选则统计全部",
                style = GlassText.Meta,
                color = palette.InkMid
            )
            Spacer(Modifier.height(12.dp))

            devices.forEach { device ->
                val checked = device in draft
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { draft = if (checked) draft - device else draft + device }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (checked) palette.Accent else Color.Transparent)
                            .border(
                                1.5.dp,
                                if (checked) palette.Accent else palette.InkLo,
                                RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        device,
                        style = GlassText.Body,
                        color = palette.InkHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { draft = emptySet() }) {
                    Text("清除", style = GlassText.Label, color = palette.InkMid)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("取消", style = GlassText.Label, color = palette.InkMid)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onConfirm(draft) }) {
                    Text("应用", style = GlassText.Label, color = palette.Accent)
                }
            }
        }
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
    pickerHeight: Dp,
    onConfirm: (from: String, to: String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalGlassPalette.current
    // Material3 的 DateRangePicker 内部按 **UTC 天** 组织日历：
    // 直接传 System.currentTimeMillis()，北京时间 0~8 点（UTC 还是前一天）时，
    // 「今天」的标记会落在昨天，默认范围整体差一天。
    // 所以：先按北京时间算出今天，再映射到该日的 UTC 零点 —— 日历上显示的
    // 第 N 格就是北京时间的第 N 天。
    val beijingZone = remember { java.time.ZoneId.of("Asia/Shanghai") }
    val todayLocal = remember { java.time.LocalDate.now(beijingZone) }
    val initialFromMillis = remember(todayLocal) {
        todayLocal.minusDays(6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val initialToMillis = remember(todayLocal) {
        todayLocal.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    // 未来日期没有用量，选了只会得到一个空范围 —— 直接禁掉
    val lastSelectableMillis = remember(todayLocal) {
        todayLocal.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFromMillis,
        initialSelectedEndDateMillis = initialToMillis,
        // 未来日期没有用量，选了只会得到一个空范围 —— 直接禁掉
        selectableDates = remember(lastSelectableMillis) {
            object : androidx.compose.material3.SelectableDates {
                // 参数是候选日的 UTC 零点；晚于今天的一律不可选
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= lastSelectableMillis
            }
        }
    )
    // millis -> yyyy-MM-dd（北京时间）
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = StatsEngine.beijingTz }
    }
    Column(
        Modifier
            // 横屏时 0.94 宽会把日历拉成超宽格，日与日之间空出一大截；先限宽再取比例
            .widthIn(max = 420.dp)
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
            // 竖屏 400dp = 标题 60 + 星期行 50 + 月份头 47 + 5 行日格 240，整月完整可见；
            // 横屏由调用方按可用高度收窄，避免按钮被顶出屏幕。六行月份仍可滚动。
            modifier = Modifier.height(pickerHeight),
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
            SkeletonBlock(palette, { alpha.value }, width = 0.42f, height = 13.dp)
            Spacer(Modifier.height(14.dp))
            SkeletonBlock(palette, { alpha.value }, width = 0.62f, height = if (wide) 52.dp else 42.dp)
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(palette, { alpha.value }, width = 1f, height = 15.dp)
        }
        // 趋势卡
        Column(
            Modifier
                .fillMaxWidth()
                .glassCard(backdrop)
                .padding(18.dp)
        ) {
            SkeletonBlock(palette, { alpha.value }, width = 0.3f, height = 12.dp)
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(palette, { alpha.value }, width = 1f, height = if (wide) 150.dp else 120.dp)
        }
        if (!wide) {
            // 分布卡
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassCard(backdrop)
                    .padding(18.dp)
            ) {
                SkeletonBlock(palette, { alpha.value }, width = 0.3f, height = 12.dp)
                Spacer(Modifier.height(16.dp))
                SkeletonBlock(palette, { alpha.value }, width = 1f, height = 90.dp)
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    palette: GlassPalette,
    alpha: () -> Float,
    width: Float,
    height: androidx.compose.ui.unit.Dp
) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.InkHi)
            // 呼吸透明度在 layer 阶段才读：以前 alpha.value 在组合作用域取值，
            // 整个骨架屏（3 张玻璃卡 + 7 个块）每帧重组一次
            .graphicsLayer { this.alpha = 0.09f * alpha() + 0.03f }
    )
}

/** 应用分布：环形图 + 图例 + 列表。 */
@Composable
private fun ToolDistributionSection(
    state: UiState,
    backdrop: com.kyant.backdrop.Backdrop,
    onShowToolDetail: (String) -> Unit,
    // 手机单列时由页面把列表行提升为惰性 item，这里只出标题 + 环形图卡
    includeRows: Boolean = true
) {
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    val wide = isWideScreen()
    val top = remember(state.toolDistribution) { state.toolDistribution.take(8) }
    // 切片只在数据变化时算一次：以前每次重组都新建 List（不稳定类型），DonutChart 永远无法跳过
    val slices = remember(top) { top.map { DonutSlice(it.tool, it.percentage, toolIconFor(it.tool).color) } }
    // 图例只描述「看得见」的占比：显示成 0% 的条目对读者零信息量，却在图例里各占一行，
    // 把卡片重心压歪。注意不能只按 >0 过滤——占比是 0.3% 的应用四舍五入后也是「0%」，
    // 但它是圆环上真实存在的一段颜色。圆环照旧按真实占比画，图例只命名看得见的那些，
    // 剩下的归进「其他 N 个应用」，数量对得上环上的段数。
    val ringItems = remember(top) { top.filter { it.percentage > 0f } }
    val legendItems = remember(ringItems) { ringItems.filter { it.percentage >= 0.5f }.take(5) }
    val otherCount = ringItems.size - legendItems.size

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
                slices = slices,
                diameter = if (wide) 150.dp else 126.dp,
                stroke = 17.dp,
                centerTop = "${top.size}",
                centerBottom = "应用"
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                legendItems.forEach { item ->
                    LegendRow(
                        icon = toolIconFor(item.tool),
                        name = item.tool,
                        pct = item.percentage
                    )
                }
                if (otherCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(palette.InkLo)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "其他 $otherCount 个应用",
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
    // 11dp 的空隙在惰性模式下要保留：它与 LazyColumn 的 11dp 行距、首行补的 9dp 一起，
    // 正好复刻改造前「环形图卡 → 第一行」的 51dp
    Spacer(Modifier.height(11.dp))
    if (includeRows) {
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
}

/** 模型消耗：列表 + 行内占比进度条。 */
@Composable
private fun ModelCostSection(
    state: UiState,
    backdrop: com.kyant.backdrop.Backdrop,
    onShowModelDetail: (String) -> Unit,
    rows: List<ModelCost> = state.modelCosts.take(10)
) {
    val haptic = LocalHapticFeedback.current
    val list = rows
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

/**
 * 排序 / 展开控制条：应用与模型列表共用。
 *
 * 手机单列和平板双列都要用它 —— 之前只有手机有排序芯片，
 * 平板上的模型列表固定按消耗排序，同一份数据两种顺序，用户没法统一预期。
 */
@Composable
private fun SortControlBar(
    listSort: ListSort,
    onSortChange: (ListSort) -> Unit,
    canExpand: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop
) {
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "排序",
            style = GlassText.Meta,
            color = palette.InkMid
        )
        Spacer(Modifier.width(8.dp))
        ListSort.entries.forEach { mode ->
            SortChip(
                text = mode.label,
                active = listSort == mode,
                backdrop = backdrop,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSortChange(mode)
                }
            )
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        if (canExpand) {
            Text(
                text = if (expanded) "收起" else "显示全部",
                style = GlassText.Meta,
                color = palette.Accent,
                modifier = Modifier.clickable { onToggleExpand() }
            )
        }
    }
}

@Composable
private fun LegendRow(icon: ToolIcon, name: String, pct: Float) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // 图例用应用自己的品牌 Logo，和同一张卡下方列表行、以及额度页保持一致。
        // 之前这里是纯色圆点：同一批应用在同一张卡里出现两次，一次有 Logo 一次没有，看着像两套数据。
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
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
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(Modifier.width(9.dp))
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
    /** 金额内联到标题行右侧（会话行用：让下方那行 meta 拿到整行宽度不被截断）。 */
    valueInline: Boolean = false,
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
                // 模型 id 常带「供应商/型号」长前缀，单行会把区分度最高的尾巴吃掉；
                // 允许折两行，只有真的放不下时卡片才会变高。
                if (valueInline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = GlassText.Body.copy(lineBreak = LineBreak.Heading),
                            color = palette.InkHi,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(value, style = GlassText.Numeric, color = palette.InkHi, maxLines = 1)
                    }
                } else {
                    Text(
                        name,
                        // Heading 的断行策略不拆词：否则长 id 会被切成「Flash-Pre / view」
                        style = GlassText.Body.copy(lineBreak = LineBreak.Heading),
                        color = palette.InkHi,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
        if (!valueInline) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = GlassText.Numeric, color = palette.InkHi)
        }
    }
}

/**
 * 时间范围选择行（内容流内，不是底部悬浮条）。
 *
 * 放在总览卡与趋势图之间：范围决定的是整页口径，先选再看数最顺；
 * 悬浮在底部时它会压住趋势图的 X 轴，而且和底部导航条叠成两条药丸。
 */
@Composable
private fun RangeSelectorRow(
    state: UiState,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: com.kyant.backdrop.Backdrop
) {
    val palette = LocalGlassPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(30)
    ) {
        // 自定义范围档：滑块会隐藏（selectedIndex=-1），不提示的话用户不知道
        // 还能点下面的档位切回预设范围
        if (state.selectedTimeRange == TimeRange.CUSTOM) {
            val from = state.customRange?.from?.replace("-", "/") ?: ""
            val to = state.customRange?.to?.replace("-", "/") ?: ""
            Text(
                "自定义范围 $from ~ $to · 点下方档位可切回",
                style = GlassText.ChartAxis,
                color = palette.Accent,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
        }
        LiquidGlassSegmentedControl(
            items = RangeLabels,
            // 自定义范围档不属于任何段：滑块隐藏，选中态由独立按钮承载
            selectedIndex = if (state.selectedTimeRange == TimeRange.CUSTOM) -1 else selectedIndex,
            onSelect = onSelect,
            // 采样页面 backdrop（不采样 scrimBackdrop）：本控件在内容层内，
            // 采样含内容层的 backdrop 会形成 RenderNode 自引用循环导致 RenderThread 栈溢出
            backdrop = backdrop,
            // 放进内容流后不再是「贴在屏幕边缘的悬浮药丸」，
            // 没必要占 64dp 那么高，压到 52dp 更像一条控件而不是一块板砖
            height = 52.dp
        )
    }
}

/**
 * 趋势图的指标切换（金额 / Tokens）。
 *
 * 不用 LiquidGlassSegmentedControl：那个是给「贴在页面上的主控件」设计的
 * （整条玻璃壳 + 折射滑块），塞进卡片里就成了一块死黑的重物。
 * 卡内的次要控件用轻量药丸：选中项填品牌色，未选中项只有极淡的底。
 */
@Composable
private fun MetricToggle(
    selected: TrendMetric,
    onSelect: (TrendMetric) -> Unit
) {
    val palette = LocalGlassPalette.current
    // 品牌色是亮青，填在色块上的文字必须用深色才看得清：
    // 原来用 AccentInk（暗色主题下本身就是浅色）= 浅字压浅底，几乎读不出来。
    val onAccent = if (palette.Accent.luminance() > 0.5f) {
        androidx.compose.ui.graphics.Color(0xFF07131A)
    } else {
        androidx.compose.ui.graphics.Color.White
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.InkHi.copy(alpha = 0.06f))
            .padding(3.dp)
    ) {
        TrendMetric.entries.forEach { m ->
            val on = m == selected
            Text(
                text = if (m == TrendMetric.COST) "金额" else "Tokens",
                style = GlassText.Meta.copy(fontSize = 12.sp),
                color = if (on) onAccent else palette.InkMid,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) palette.Accent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
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

/** 刷新失败提示条：与降级提示同构，但用警示色。 */
@Composable
private fun ErrorNote(text: String, backdrop: com.kyant.backdrop.Backdrop) {
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
            tint = palette.Down,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = GlassText.Meta,
            color = palette.Down,
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
            // 遮罩透明度只该作用于背景绘制本身：graphicsLayer{alpha} 会把整层内容
            // （含详情卡）一起变透明，卡片被压到 0.45，看起来像「背景穿出来了」。
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
                    // 详情是「看全名」的最后一道门：列表行可以省略，这里不行。
                    // 模型 id 常带「供应商/型号」长前缀，收小字号并允许折三行。
                    Text(
                        detail.model,
                        style = GlassText.Title.copy(lineBreak = LineBreak.Heading),
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                        color = palette.InkHi,
                        maxLines = 3,
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
            // 遮罩透明度只该作用于背景绘制本身：graphicsLayer{alpha} 会把整层内容
            // （含详情卡）一起变透明，卡片被压到 0.45，看起来像「背景穿出来了」。
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

/**
 * 表头日期（「10月24日 星期五」）。
 *
 * 每次调用现算：`remember` 缓存的话，App 常驻不杀、跨过零点后组合不销毁，
 * 表头就一直显示昨天的日期。用系统时区 —— 表头是给用户看的「今天」，
 * 跟用户本地日历一致（额度窗口那些内部口径才用北京时间）。
 */
private val headerDateFormatter = java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

internal fun currentDateLabel(): String =
    headerDateFormatter.format(java.time.LocalDate.now(java.time.ZoneId.systemDefault()))

/** 时长（秒）→ 「1时23分」/「23分」/「45秒」。 */
internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}时${m}分"
        m > 0 -> "${m}分"
        else -> "${seconds}秒"
    }
}
