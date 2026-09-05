package ai.vibecafe.usage.ui.ag

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.delay
import java.util.Locale

/** 额度页供应商下拉选项。 */
data class QuotaOption(val label: String, val color: Color)

/**
 * 额度页供应商切换器（液态玻璃下拉，参考 tongzhewang 液态玻璃下拉组件的动效）：
 * - 折叠态：玻璃胶囊（色点 + 平台名），展开时原胶囊淡出放大消失
 * - 展开：菜单从胶囊的**实际矩形**形变长出（测量两者尺寸做非均匀缩放，起点与胶囊严丝合缝，
 *   弹簧过冲轻微回弹）；选项行错峰上浮淡入；选中行右侧白色对勾
 * - 收起：同一几何的逆形变——菜单物理吸回胶囊（弹簧，非 tween），与胶囊淡入同步交接
 * - 动画进度一律在 draw 相位读取（graphicsLayer 内），动画期间零重组；
 *   禁用逐帧 Modifier.blur（RenderEffect 在 MuMu 转译层每帧重建会卡死）
 * - 点行选择并收起；点遮罩/页面空白处收起（全屏收起由调用方挂载，见 expanded/onExpandedChange）
 * [menuBackdrop] 应传入叠加了内容层的合成 backdrop，菜单才能模糊到其背后的滚动内容。
 */
@Composable
fun QuotaSwitcher(
    options: List<QuotaOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    buttonBackdrop: Backdrop,
    menuBackdrop: Backdrop,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    var menuVisible by remember { mutableStateOf(false) }
    val current = options[selectedIndex.coerceIn(0, options.lastIndex)]
    val palette = LocalGlassPalette.current
    // 形变锚点：胶囊与菜单的实际尺寸（菜单左上角与胶囊左上角同源，缩放原点取 (0,0) 即可对位）
    var chipSize by remember { mutableStateOf(IntSize.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    // 原胶囊淡出放大（liquid 态 label 消失），弹簧回弹
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
        label = "quotaLabel"
    )
    // 形变进度：0 = 折叠成胶囊矩形 → 1 = 完全展开，弹簧带轻微过冲
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            menuVisible = true
            reveal.snapTo(0f)
            reveal.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
        } else if (menuVisible) {
            // 收起：物理弹簧吸回胶囊（比展开更硬的弹簧 → 利落但仍有惯性挤压），落位后再移除
            reveal.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 640f))
            menuVisible = false
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Column {
            LiquidButton(
                onClick = { onExpandedChange(!expanded) },
                backdrop = buttonBackdrop,
                modifier = Modifier
                    .onSizeChanged { chipSize = it }
                    .graphicsLayer {
                        alpha = labelAlpha
                        val s = 1f + 0.05f * (1f - labelAlpha)
                        scaleX = s
                        scaleY = s
                    }
                    .height(44.dp)
            ) {
                Spacer(Modifier.width(18.dp))
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
                    color = palette.InkHi,
                    maxLines = 1
                )
                Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.height(6.dp))
            content()
        }

        if (menuVisible) {
            // 透明点击捕获层：点面板外任意处收起。不加暗幕——半透明遮罩只覆盖内容区，
            // 与未覆盖区域（留白/下半屏）明暗不一致，会让整屏出现矩形"颜色分层"（参考组件同样无暗幕）
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onExpandedChange(false) }
            )

            Box(
                Modifier
                    .onSizeChanged { menuSize = it }
                    .graphicsLayer {
                        // draw 相位读进度：动画期间零重组（重组相位读会导致整棵子树每帧重组）
                        val p = reveal.value
                        // 非均匀缩放：p=0 时菜单矩形恰好压在胶囊上（宽高比对位），展开即"从胶囊长出"
                        val sx0 = (chipSize.width.coerceAtLeast(1).toFloat() / menuSize.width.coerceAtLeast(1))
                            .coerceIn(0.2f, 0.9f)
                        val sy0 = (chipSize.height.coerceAtLeast(1).toFloat() / menuSize.height.coerceAtLeast(1))
                            .coerceIn(0.06f, 0.5f)
                        scaleX = sx0 + (1f - sx0) * p
                        scaleY = sy0 + (1f - sy0) * p
                        transformOrigin = TransformOrigin(0f, 0f)
                        alpha = (p * 4f).coerceAtMost(1f)
                    }
            ) {
                Column(
                    Modifier
                        // 贴内容宽度的窄面板（不再横向撑满），整体左对齐在按钮下方；
                        // 供应商已增至 11 个，超屏高时面板内滚动
                        .widthIn(min = 230.dp, max = 320.dp)
                        .heightIn(max = 430.dp)
                        // 玻璃壳必须套在滚动容器**外侧**：drawBackdrop 的圆角裁切层包住滚动视口，
                        // 行内容才只会在壳内位移；反过来（滚动在外）整块玻璃会随内容平移，
                        // 被滚动视口的直角边界裁切 → 滚动时上/下角变直角
                        .glassCard(menuBackdrop, cornerRadius = 20.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    options.forEachIndexed { i, opt ->
                        val selected = i == selectedIndex.coerceIn(0, options.lastIndex)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onSelect(i)
                                    onExpandedChange(false)
                                }
                                // 参考动效：行错峰上浮淡入；弹簧过冲让行浮过头 1~2dp 再落回（液态感）
                                .graphicsLayer {
                                    val c = (reveal.value * 1.7f - i * 0.09f).coerceIn(0f, 1.18f)
                                    alpha = 0.35f + 0.65f * c.coerceIn(0f, 1f)
                                    translationY = 12.dp.toPx() * (1f - c)
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
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
                                color = if (selected) palette.InkStrong else palette.InkMid,
                                maxLines = 1
                            )
                            Spacer(Modifier.weight(1f))
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    Modifier.size(16.dp),
                                    tint = palette.InkStrong
                                )
                            }
                        }
                    }
                }
                // 液滴高光：展开瞬间白色闪光，随进度消退（弹簧过冲时钳 0）
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = (0.45f * (1f - reveal.value)).coerceAtLeast(0f) }
                        .background(Color.White)
                )
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

// ─── 未接入：一键授权（支持 OAuth 的供应商）+ 粘贴凭据后备 ───

@Composable
private fun LoginArea(
    provider: ExtraProvider,
    ps: ProviderState,
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel
) {
    var inputs by remember(provider.id) { mutableStateOf(List(provider.credLabels.size) { "" }) }
    val palette = LocalGlassPalette.current

    Column {
        if (provider.oauth != null) {
            LiquidButton(
                onClick = { viewModel.loginOAuth(provider.id) },
                backdrop = backdrop,
                enabled = !ps.isLoading,
                surfaceColor = provider.color,
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                if (ps.isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ps.oauthCode != null) "等待浏览器授权…" else "正在发起授权…",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White
                    )
                } else {
                    Text("一键授权登录", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
            // GitHub 设备码：浏览器打开 github.com/login/device 后需手动输入此码，点击即可复制
            ps.oauthCode?.let { code ->
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                var copied by remember(code) { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        delay(2000)
                        copied = false
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("授权码已自动复制，浏览器打开后直接粘贴；点击授权码可再次复制：", fontSize = 11.sp, color = palette.InkMid)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        code,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Black,
                        color = palette.InkHi,
                        letterSpacing = 2.5.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
                            copied = true
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
                            copied = true
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "复制授权码",
                            Modifier.size(15.dp),
                            tint = if (copied) palette.Accent else palette.InkMid
                        )
                    }
                    if (copied) {
                        Text("已复制，去浏览器粘贴", fontSize = 11.sp, color = palette.Accent)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("或手动粘贴凭据", fontSize = 11.sp, color = palette.InkMid)
            Spacer(Modifier.height(8.dp))
        }
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
                unfocusedBorderColor = palette.Rim,
                cursorColor = provider.color,
                focusedLabelColor = provider.color,
                unfocusedLabelColor = palette.InkMid,
                focusedTextColor = palette.InkHi,
                unfocusedTextColor = palette.InkHi
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
            if (ps.isLoading && provider.oauth == null) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("查询中…", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            } else {
                Text("接入", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        if (ps.error != null) {
            Text(ps.error!!, color = palette.Down, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            provider.hint,
            color = palette.InkLo,
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
        ExtraProvider.GITHUB -> listOf("ghp_… / ghu_…")
        ExtraProvider.OPENROUTER -> listOf("sk-or-v1-…")
        ExtraProvider.DOUBAO -> listOf("粘贴 sessionid 的值或整条 Cookie")
        ExtraProvider.AGNES -> listOf("登录邮箱或用户名", "登录密码")
    }

// ─── 已接入：状态行 + 分组明细 ───

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LoggedArea(
    provider: ExtraProvider,
    ps: ProviderState,
    viewModel: ExtraQuotaViewModel
) {
    val palette = LocalGlassPalette.current
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
                Icon(Icons.Filled.Refresh, "刷新", Modifier.size(16.dp), tint = palette.InkMid)
            }
            IconButton(onClick = { viewModel.logout(provider.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Logout, "解绑", Modifier.size(15.dp), tint = palette.InkMid)
            }
        }

        if (ps.error != null) {
            Text(
                ps.error!!,
                color = palette.Down,
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
                color = palette.InkMid
            )
            Spacer(Modifier.height(8.dp))
            bars.forEachIndexed { idx, bar ->
                if (idx > 0) Spacer(Modifier.height(12.dp))
                BarRow(bar)
            }
        }

        if (ps.models.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                ps.modelsLabel ?: "可用模型",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = palette.InkMid
            )
            Spacer(Modifier.height(7.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ps.models.take(20).forEach { model ->
                    Text(
                        model,
                        fontSize = 10.sp,
                        color = palette.InkHi,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.InkHi.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (ps.models.size > 20) {
                    Text(
                        "+${ps.models.size - 20} 个",
                        fontSize = 10.sp,
                        color = palette.InkMid,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }
        }

        if (ps.groups.isEmpty() && !ps.isLoading && ps.error == null) {
            Text(
                "暂无额度数据",
                color = palette.InkMid,
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
private fun BarRow(bar: Bar) {
    val palette = LocalGlassPalette.current
    val percent = bar.percentRemaining.coerceIn(0, 100)
    val barColor = quotaGaugeColor(percent)
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
                    color = palette.InkHi,
                    maxLines = 1
                )
                bar.counts?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 11.sp, color = palette.InkMid, maxLines = 1)
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
        // 进度条：按余量渐变（100% 绿 → 0% 红）
        QuotaBarFill(percent, 8.dp, palette.InkHi.copy(alpha = 0.10f))
        if (bar.reset != null) {
            Text(
                bar.reset,
                fontSize = 10.sp,
                color = palette.InkMid,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
