package ai.vibecafe.usage.ui

import ai.vibecafe.usage.stats.TimeRange
import ai.vibecafe.usage.ui.glass.GlassBackground
import ai.vibecafe.usage.ui.glass.LiquidGlassSegmentedControl
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.glass.glassRow
import ai.vibecafe.usage.ui.glass.glassTile
import ai.vibecafe.usage.ui.glass.rememberPageBackdrop
import ai.vibecafe.usage.ui.theme.Glass
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.HanSansFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import ai.vibecafe.usage.R
import ai.vibecafe.usage.core.BackgroundStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

private val RangeLabels = listOf("今日", "24小时", "7天", "30天", "全部")
private val RangeValues = listOf(
    TimeRange.TODAY,
    TimeRange.HOURS_24,
    TimeRange.DAYS_7,
    TimeRange.DAYS_30,
    TimeRange.ALL
)

@Composable
fun DashboardScreen(
    state: UiState,
    onSelectRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val backdrop = rememberPageBackdrop()
    val selectedIndex = RangeValues.indexOf(state.selectedTimeRange).coerceAtLeast(0)
    val insets = WindowInsets.systemBars.asPaddingValues()

    var bgPath by remember { mutableStateOf(BackgroundStore.get(context)) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val wide = isWideScreen()

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) bgPath = BackgroundStore.set(context, uri)
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop, imagePath = bgPath)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = insets.calculateTopPadding() + 18.dp,
                    bottom = insets.calculateBottomPadding() + 28.dp,
                    start = 18.dp,
                    end = 18.dp
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Header(
                onRefresh = onRefresh,
                onOpenSettings = { settingsExpanded = true },
                backdrop = backdrop,
                wide = wide
            )

            if (wide) {
                // 平板：总消耗（数字+趋势+分段）与统计网格并排，卡片更紧凑、信息密度更高
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(backdrop)
                        .padding(26.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1.15f)) {
                        Summary(state, wide = true)
                        Spacer(Modifier.height(18.dp))
                        LiquidGlassSegmentedControl(
                            items = RangeLabels,
                            selectedIndex = selectedIndex,
                            onSelect = { onSelectRange(RangeValues[it]) },
                            backdrop = backdrop
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        StatsGrid(state)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(backdrop)
                        .padding(20.dp)
                ) {
                    Summary(state, wide = false)
                    Spacer(Modifier.height(22.dp))
                    LiquidGlassSegmentedControl(
                        items = RangeLabels,
                        selectedIndex = selectedIndex,
                        onSelect = { onSelectRange(RangeValues[it]) },
                        backdrop = backdrop
                    )
                    Spacer(Modifier.height(20.dp))
                    StatsStrip(state)
                }
            }

            when {
                state.isLoading -> StatusCard("正在加载用量数据…", backdrop)
                state.error != null -> StatusCard(state.error, backdrop)
                else -> {
                    val hasTools = state.toolDistribution.isNotEmpty()
                    val hasModels = state.modelCosts.isNotEmpty()
                    if (wide && (hasTools || hasModels)) {
                        // 平板/宽屏：应用分布与模型消耗并排双列，充分利用横向空间
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (hasTools) {
                                    SectionTitle("应用分布")
                                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                        state.toolDistribution.take(8).forEach { item ->
                                            GlassListRow(
                                                backdrop = backdrop,
                                                icon = toolIconFor(item.tool),
                                                name = item.tool,
                                                meta = "${formatTokens(item.tokens)} tokens · ${
                                                    String.format(Locale.US, "%.1f", item.percentage)
                                                }%",
                                                value = formatCost(item.cost),
                                                compact = true
                                            )
                                        }
                                    }
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                if (hasModels) {
                                    SectionTitle("模型消耗")
                                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                        state.modelCosts.take(10).forEach { item ->
                                            GlassListRow(
                                                backdrop = backdrop,
                                                icon = toolIconFor(item.model),
                                                name = item.model,
                                                meta = "${formatTokens(item.tokens)} tokens",
                                                value = formatCost(item.cost),
                                                compact = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 手机：单列顺序排列
                        if (hasTools) {
                            SectionTitle("应用分布")
                            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                state.toolDistribution.take(8).forEach { item ->
                                    GlassListRow(
                                        backdrop = backdrop,
                                        icon = toolIconFor(item.tool),
                                        name = item.tool,
                                        meta = "${formatTokens(item.tokens)} tokens · ${
                                            String.format(Locale.US, "%.1f", item.percentage)
                                        }%",
                                        value = formatCost(item.cost)
                                    )
                                }
                            }
                        }
                        if (hasModels) {
                            SectionTitle("模型消耗")
                            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                state.modelCosts.take(10).forEach { item ->
                                    GlassListRow(
                                        backdrop = backdrop,
                                        icon = toolIconFor(item.model),
                                        name = item.model,
                                        meta = "${formatTokens(item.tokens)} tokens",
                                        value = formatCost(item.cost)
                                    )
                                }
                            }
                        }
                        if (!hasTools && !hasModels) {
                            StatusCard("当前时间范围内没有用量记录", backdrop)
                        }
                    }
                }
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
                backdrop = backdrop,
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
                topPadding = insets.calculateTopPadding() + 18.dp + if (wide) 62.dp else 54.dp
            )
        }
    }
}

@Composable
private fun Header(
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    wide: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    fontSize = if (wide) 27.sp else 23.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "用量概览 · v2.5.1",
                    style = GlassText.Label,
                    fontSize = if (wide) 14.sp else 13.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconGlassButton(Icons.Filled.Refresh, "刷新", backdrop, onRefresh)
            IconGlassButton(Icons.Filled.Settings, "设置", backdrop, onOpenSettings)
        }
    }
}

/**
 * 同组合渲染的设置菜单（非 Popup 窗口），因此玻璃层能真实采样到页面背景光晕。
 * 每个菜单项都是一块带模糊的玻璃行（glassRow），退出/自定义背景/恢复默认一目了然。
 */
@Composable
private fun SettingsMenu(
    backdrop: com.kyant.backdrop.Backdrop,
    showReset: Boolean,
    onCustomBackground: () -> Unit,
    onResetBackground: () -> Unit,
    onLogout: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding, end = 18.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            Modifier.width(196.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsMenuItem(
                backdrop = backdrop,
                glyph = "🎨",
                label = "自定义背景",
                onClick = onCustomBackground
            )
            if (showReset) {
                SettingsMenuItem(
                    backdrop = backdrop,
                    glyph = "↺",
                    label = "恢复默认背景",
                    onClick = onResetBackground
                )
            }
            SettingsMenuItem(
                backdrop = backdrop,
                glyph = "⏻",
                label = "退出登录",
                onClick = onLogout
            )
        }
    }
}

@Composable
private fun SettingsMenuItem(
    backdrop: com.kyant.backdrop.Backdrop,
    glyph: String,
    label: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .glassRow(backdrop, cornerRadius = 16.dp)
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(glyph, fontSize = 17.sp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = GlassText.Body)
    }
}

@Composable
private fun IconGlassButton(
    imageVector: ImageVector,
    contentDescription: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .glassTile(backdrop, cornerRadius = 20.dp)
            .clickable(interaction, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Glass.InkHi,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun Summary(state: UiState, wide: Boolean = false) {
    val cost = state.stats?.totalCost ?: 0.0
    val trend = remember(state.dailyUsage, state.selectedTimeRange) { computeTrend(state) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("总消耗（当前范围）", style = GlassText.Label)
            Spacer(Modifier.height(if (wide) 10.dp else 7.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatCost(cost),
                    style = GlassText.Hero,
                    fontSize = if (wide) 56.sp else 44.sp
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "USD",
                    fontFamily = HanSansFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (wide) 18.sp else 15.sp,
                    color = Glass.InkMid,
                    modifier = Modifier.padding(bottom = if (wide) 6.dp else 3.dp)
                )
            }
        }
        if (trend != null) TrendChip(trend)
    }
}

@Composable
private fun TrendChip(trend: Float) {
    val up = trend >= 0f
    val bg = if (up) Glass.AccentWash else Glass.DownWash
    val rim = if (up) Glass.AccentRim else Glass.DownRim
    val ink = if (up) Glass.AccentInk else Glass.Down
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
                color = ink
            )
            Spacer(Modifier.width(5.dp))
            Text(
                String.format(Locale.US, "%+.0f%%", trend * 100),
                style = GlassText.Chip,
                color = ink
            )
        }
    }
}

@Composable
private fun StatsStrip(state: UiState) {
    val stats = state.stats
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiniStat("Tokens", formatTokens(stats?.totalTokens ?: 0L))
        MiniStat("模型", (stats?.modelCount ?: 0).toString())
        MiniStat("应用", (stats?.toolCount ?: 0).toString())
        MiniStat("会话", (stats?.sessionCount ?: 0).toString())
    }
}

/** 平板专用：四格统计 2×2 网格，紧凑呈现于总消耗卡片右侧。 */
@Composable
private fun StatsGrid(state: UiState) {
    val stats = state.stats
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniStat("Tokens", formatTokens(stats?.totalTokens ?: 0L))
            MiniStat("模型", (stats?.modelCount ?: 0).toString())
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniStat("应用", (stats?.toolCount ?: 0).toString())
            MiniStat("会话", (stats?.sessionCount ?: 0).toString())
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = GlassText.NumericSmall)
        Spacer(Modifier.height(3.dp))
        Text(label, style = GlassText.Meta)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = GlassText.Section,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun GlassListRow(
    backdrop: com.kyant.backdrop.Backdrop,
    icon: ToolIcon,
    name: String,
    meta: String,
    value: String,
    compact: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassRow(backdrop)
            .padding(
                horizontal = if (compact) 15.dp else 17.dp,
                vertical = if (compact) 11.dp else 15.dp
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(meta, style = GlassText.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = GlassText.Numeric)
    }
}

@Composable
private fun StatusCard(text: String, backdrop: com.kyant.backdrop.Backdrop) {
    Box(
        Modifier
            .fillMaxWidth()
            .glassRow(backdrop)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = GlassText.Label)
    }
}

// ---------- 工具函数 ----------

private fun computeTrend(state: UiState): Float? {
    val series = state.dailyUsage
    if (series.size < 2) return null
    val half = series.size / 2
    val first = series.take(half).sumOf { it.cost }
    val second = series.drop(half).sumOf { it.cost }
    if (first <= 0.0) return null
    val ratio = ((second - first) / first).toFloat()
    return if (abs(ratio) > 10f) null else ratio
}

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

/** 工具/模型图标：真实品牌 Logo（彩色 PNG drawable 优先），无 Logo 的品牌用主题 Material 图标（vector）兜底。 */
private data class ToolIcon(
    val label: String,
    val color: Color,
    val drawable: Int? = null,
    val vector: ImageVector? = null,
    val colorful: Boolean = false
)

/** 品牌匹配规则：aliases 为品牌在工具/模型名中的常见写法，模糊打分后取最高分者。 */
private data class IconRule(
    val label: String,
    val aliases: List<String>,
    val color: Color,
    val drawable: Int? = null,
    val vector: ImageVector? = null,
    val colorful: Boolean = false,
    val priority: Int = 0
)

private val iconRules = listOf(
    IconRule("Claude", listOf("claude", "anthropic"), Color(0xFFD97757), drawable = R.drawable.ic_tool_claude, colorful = true, priority = 50),
    IconRule("OpenAI", listOf("openai", "gpt", "chatgpt", "codex", "o1", "o3", "o4"), Color(0xFF10A37F), drawable = R.drawable.ic_tool_openai, colorful = true, priority = 45),
    IconRule("Gemini", listOf("gemini"), Color(0xFF7C6BFF), drawable = R.drawable.ic_tool_gemini, colorful = true, priority = 40),
    IconRule("DeepSeek", listOf("deepseek"), Color(0xFF4D6BFE), drawable = R.drawable.ic_tool_deepseek, colorful = true, priority = 40),
    IconRule("Qwen", listOf("qwen", "tongyi", "dashscope"), Color(0xFF615CED), drawable = R.drawable.ic_tool_qwen, colorful = true, priority = 40),
    IconRule("Kimi", listOf("kimi", "moonshot"), Color(0xFF0ABDFF), drawable = R.drawable.ic_tool_kimi, colorful = true, priority = 35),
    IconRule("Mistral", listOf("mistral", "mixtral", "codestral"), Color(0xFFFA520F), drawable = R.drawable.ic_tool_mistral, colorful = true, priority = 30),
    IconRule("Meta", listOf("llama", "meta"), Color(0xFF0866FF), drawable = R.drawable.ic_tool_meta, colorful = true, priority = 30),
    IconRule("Tencent", listOf("hunyuan", "hy3", "hy", "tencent"), Color(0xFF2D9BF0), drawable = R.drawable.ic_tool_tencent, colorful = true, priority = 30),
    IconRule("Zhipu", listOf("glm", "chatglm", "zhipu", "zcode", "zai"), Color(0xFF4F6EF7), drawable = R.drawable.ic_tool_zhipu, colorful = true, priority = 28),
    IconRule("Grok", listOf("grok", "groq", "xai"), Color(0xFF1F2937), drawable = R.drawable.ic_tool_grok, colorful = true, priority = 25),
    IconRule("Copilot", listOf("copilot"), Color(0xFF2DD4BF), vector = Icons.Filled.Rocket, priority = 25),
    IconRule("Cursor", listOf("cursor"), Color(0xFF3F3F46), drawable = R.drawable.ic_tool_cursor, priority = 25),
    IconRule("Trae", listOf("trae"), Color(0xFF5146F5), drawable = R.drawable.ic_tool_trae, colorful = true, priority = 25),
    IconRule("Cline", listOf("cline"), Color(0xFF14B8A6), drawable = R.drawable.ic_tool_cline, colorful = true, priority = 25),
    IconRule("OpenCode", listOf("opencode"), Color(0xFFF97316), drawable = R.drawable.ic_tool_opencode, colorful = true, priority = 22),
    IconRule("Hermes", listOf("hermes"), Color(0xFF374151), drawable = R.drawable.ic_tool_hermes, priority = 20),
    IconRule("LongCat", listOf("longcat"), Color(0xFF8B5CF6), drawable = R.drawable.ic_tool_longcat, colorful = true, priority = 20),
    IconRule("Xiaomi", listOf("mimo", "xiaomi"), Color(0xFFFF6900), drawable = R.drawable.ic_tool_xiaomi, colorful = true, priority = 20),
    IconRule("Alibaba", listOf("alibaba", "aliyun"), Color(0xFFFF6A00), drawable = R.drawable.ic_tool_alibaba, priority = 20),
    IconRule("Hugging Face", listOf("huggingface", "hf"), Color(0xFFFFD21E), drawable = R.drawable.ic_tool_huggingface, priority = 20),
    IconRule("Image", listOf("dall", "flux", "midjourney", "sora", "stable", "imagen", "ideogram", "seedream"), Color(0xFFF472B6), vector = Icons.Filled.Palette, priority = 20),
    IconRule("Kiro", listOf("kiro"), Color(0xFFFBBF24), vector = Icons.Filled.Edit, priority = 15)
)

/** 模糊匹配：对名称做 token 化，按 精确=100 / 前缀=60 / 包含=50 / 编辑距离≤1=40 打分，最高分者胜（同分取 priority）。 */
private fun toolIconFor(name: String): ToolIcon {
    val n = name.lowercase(Locale.US).replace('_', ' ').replace('/', ' ').replace('.', ' ')
    val tokens = n.split(Regex("[\\s\\-+.]+")).filter { it.isNotBlank() }
    var best: IconRule? = null
    var bestScore = 0
    for (rule in iconRules) {
        var score = 0
        for (alias in rule.aliases) {
            when {
                tokens.any { it == alias } -> score = maxOf(score, 100)
                tokens.any { it.startsWith(alias) || alias.length >= 3 && alias.startsWith(it) && it.length >= 2 } ->
                    score = maxOf(score, 60)
                alias.length >= 3 && n.contains(alias) -> score = maxOf(score, 50)
                alias.length >= 3 && tokens.any { it.length >= 3 && levenshtein(it, alias) <= 1 } ->
                    score = maxOf(score, 40)
            }
        }
        if (score > 0 && (score > bestScore || score == bestScore && rule.priority > (best?.priority ?: -1))) {
            bestScore = score
            best = rule
        }
    }
    val r = best ?: return ToolIcon("AI", Color(0xFF64748B), vector = Icons.Filled.AutoAwesome)
    return ToolIcon(r.label, r.color, r.drawable, r.vector, r.colorful)
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            dp[j] = minOf(
                dp[j] + 1,
                dp[j - 1] + 1,
                prev + if (a[i - 1] == b[j - 1]) 0 else 1
            )
            prev = tmp
        }
    }
    return dp[b.length]
}
