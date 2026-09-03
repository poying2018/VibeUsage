package ai.vibecafe.usage.ui.ag

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import com.kyant.backdrop.Backdrop
import java.util.Locale

private val MutedColor = Color(0xFF9A9AAF)
private val ErrorColor = Color(0xFFFF5A5A)

/** 额度页供应商下拉选项。 */
data class QuotaOption(val label: String, val color: Color)

/**
 * 额度页供应商切换器（液态玻璃下拉）：
 * 收起时为胶囊按钮（色点 + 当前平台 + 箭头）；展开后为全屏半透明遮罩 + 背后内容实时模糊的玻璃菜单。
 * [menuBackdrop] 应传入叠加了内容层的合成 backdrop，菜单才能模糊到其背后的滚动内容。
 */
@Composable
fun QuotaSwitcher(
    options: List<QuotaOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    buttonBackdrop: Backdrop,
    menuBackdrop: Backdrop,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options[selectedIndex.coerceIn(0, options.lastIndex)]
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "quotaArrow")

    Box(Modifier.fillMaxWidth()) {
        Column {
            LiquidButton(
                onClick = { expanded = !expanded },
                backdrop = buttonBackdrop,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(current.color)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    current.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "切换平台",
                    Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                    tint = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            content()
        }

        if (expanded) {
            // 遮罩：点击任意处收起
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = false }
            )
            Column(
                Modifier
                    .padding(top = 50.dp)
                    .glassCard(menuBackdrop, cornerRadius = 20.dp)
                    .padding(vertical = 6.dp)
            ) {
                options.forEachIndexed { i, opt ->
                    val selected = i == selectedIndex.coerceIn(0, options.lastIndex)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onSelect(i)
                                expanded = false
                            }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(opt.color)
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(
                            opt.label,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) Color.White else MutedColor,
                            maxLines = 1
                        )
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选择",
                                Modifier.size(16.dp),
                                tint = opt.color
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 凭据型供应商独立页面：整页展示接入与额度明细，按时间窗口（5 小时 / 每周等）分组。
 */
@Composable
fun ProviderPage(
    provider: ExtraProvider,
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel = viewModel()
) {
    val stateMap by viewModel.state.collectAsStateWithLifecycle()
    val ps = stateMap[provider.id] ?: ProviderState()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(provider.title, provider.color, provider.icon)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .glassCard(backdrop, cornerRadius = 20.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (!ps.loggedIn) {
                LoginArea(provider, ps, backdrop, viewModel)
            } else {
                LoggedArea(provider, ps, viewModel)
            }
        }
    }
}

// ─── 未接入：粘贴凭据（1 个 API Key，或方舟 AK/SK 双字段）───

@Composable
private fun LoginArea(
    provider: ExtraProvider,
    ps: ProviderState,
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel
) {
    var inputs by remember(provider.id) { mutableStateOf(List(provider.credLabels.size) { "" }) }

    Column {
        provider.credLabels.forEachIndexed { i, label ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = inputs[i],
                onValueChange = { v ->
                    inputs = inputs.toMutableList().also { it[i] = v }
                    viewModel.clearError(provider.id)
                },
                label = { Text(label) },
                placeholder = { Text(provider.credPlaceholders[i]) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = provider.color,
                    unfocusedBorderColor = Color(0xFF3A3A4E),
                    cursorColor = provider.color,
                    focusedLabelColor = provider.color,
                    unfocusedLabelColor = MutedColor,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
        }
        Spacer(Modifier.height(10.dp))
        LiquidButton(
            onClick = { viewModel.login(provider.id, inputs) },
            backdrop = backdrop,
            enabled = inputs.all { it.isNotBlank() } && !ps.isLoading,
            surfaceColor = provider.color,
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            if (ps.isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("查询中…", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            } else {
                Text("接入", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        if (ps.error != null) {
            Text(ps.error!!, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            provider.hint,
            color = Color(0xFF5A5A6E),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private val ExtraProvider.credPlaceholders: List<String>
    get() = when (this) {
        ExtraProvider.MINIMAX -> listOf("sk-cp-...")
        ExtraProvider.GLM -> listOf("xxxxxxxx.yyyyyyyy")
        ExtraProvider.KIMI -> listOf("sk-kimi-...")
        ExtraProvider.DEEPSEEK -> listOf("sk-...")
        ExtraProvider.INFINI -> listOf("sk-cp-...")
        ExtraProvider.BAILIAN -> listOf("sk-...（DashScope 业务 Key）")
        ExtraProvider.ARK -> listOf("AKTPL...（AccessKey ID）", "Secret AccessKey")
    }

// ─── 已接入：状态行 + 分组明细 ───

@Composable
private fun LoggedArea(
    provider: ExtraProvider,
    ps: ProviderState,
    viewModel: ExtraQuotaViewModel
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (ps.isLoading) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = provider.color, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                if (ps.account != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(provider.color.copy(alpha = 0.16f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(ps.account!!, fontSize = 10.sp, color = provider.color, maxLines = 1)
                    }
                }
            }
            IconButton(onClick = { viewModel.refresh(provider.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "刷新", Modifier.size(16.dp), tint = MutedColor)
            }
            IconButton(onClick = { viewModel.logout(provider.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Logout, "解绑", Modifier.size(15.dp), tint = MutedColor)
            }
        }

        if (ps.error != null) {
            Text(
                ps.error!!,
                color = ErrorColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }

        ps.groups.forEach { (groupTitle, bars) ->
            Spacer(Modifier.height(8.dp))
            Text(
                groupTitle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MutedColor
            )
            Spacer(Modifier.height(8.dp))
            bars.forEachIndexed { idx, bar ->
                if (idx > 0) Spacer(Modifier.height(12.dp))
                BarRow(bar, provider.color)
            }
        }

        if (ps.groups.isEmpty() && !ps.isLoading && ps.error == null) {
            Text(
                "暂无额度数据",
                color = MutedColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** 与概览页 SectionTitle 同款：accent 竖条 + 大写小字标签 + 平台色圆点。 */
@Composable
private fun SectionHeader(text: String, color: Color, icon: ImageVector) {
    val palette = LocalGlassPalette.current
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
            color = palette.InkHi
        )
        Spacer(Modifier.width(8.dp))
        Icon(icon, null, Modifier.size(13.dp), tint = color)
    }
}

@Composable
private fun BarRow(bar: Bar, color: Color) {
    val percent = bar.percentRemaining.coerceIn(0, 100)
    val barColor = when {
        percent >= 50 -> color
        percent >= 20 -> Color(0xFFFFB020)
        else -> Color(0xFFFF5A5A)
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    bar.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1
                )
                bar.counts?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 11.sp, color = MutedColor, maxLines = 1)
                }
            }
            Text(
                buildString {
                    append("$percent%")
                    bar.usedPercent?.let { append(" · 已用 $it%") }
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barColor)
            )
        }
        if (bar.reset != null) {
            Text(
                bar.reset,
                fontSize = 10.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
