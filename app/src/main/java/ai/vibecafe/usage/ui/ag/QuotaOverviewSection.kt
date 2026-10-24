package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.vibecafe.usage.data.quota.QuotaHistoryStore
import ai.vibecafe.usage.data.quota.preferredQuotaGroup
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import ai.vibecafe.usage.ui.theme.cardBackground
import ai.vibecafe.usage.ui.theme.ensureContrast
import com.kyant.backdrop.Backdrop

/**
 * 「已接入平台」总览：把已接入的所有平台摊在一张卡里。
 *
 * 解决的问题：额度页一次只看一个平台（要逐个点下拉），而且哪个平台的凭据失效了
 * 要挨个点开才知道。这里列出全部已接入平台，一眼看到额度摘要、7 天消耗趋势和失败状态，
 * 并支持「刷新全部」一次把 13 家都拉一遍。
 *
 * - 只列**已接入**的平台（[ProviderState.loggedIn]），未接入的不占位置；
 * - 查询失败的平台用红字标出 —— 这是本区块存在的意义；
 * - 每行右侧是迷你趋势（近 7 天，每天一根柱），只有 ≥2 个数据点才画。
 */
@Composable
fun QuotaOverviewSection(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    viewModel: ExtraQuotaViewModel = viewModel()
) {
    val palette = LocalGlassPalette.current
    val stateMap by viewModel.state.collectAsStateWithLifecycle()
    val historyMap by viewModel.history.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    val connected = remember(stateMap) {
        ExtraProvider.entries.filter { stateMap[it.id]?.loggedIn == true }
    }
    val failedCount = connected.count { stateMap[it.id]?.error != null }
    val anyLoading = connected.any { stateMap[it.id]?.isLoading == true }

    Box(
        modifier
            .fillMaxWidth()
            .glassCard(backdrop, cornerRadius = 20.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 与 ProviderPage 的 SectionHeader 同款：accent 竖条 + 小字标签
                Box(
                    Modifier
                        .size(width = 3.5.dp, height = 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.Accent)
                )
                Spacer(Modifier.width(7.dp))
                Text("已接入平台", style = GlassText.Section, color = palette.InkHi)
                Spacer(Modifier.width(6.dp))
                Text("(${connected.size})", fontSize = 12.sp, color = palette.InkMid)
                Spacer(Modifier.weight(1f))
                // 异常数量常驻：折叠状态下也要能一眼看到「有平台挂了」
                if (failedCount > 0) {
                    Text("$failedCount 个异常", fontSize = 11.sp, color = palette.Down)
                    Spacer(Modifier.width(8.dp))
                }
                LiquidButton(
                    onClick = { viewModel.refreshAll() },
                    backdrop = backdrop,
                    enabled = connected.isNotEmpty() && !anyLoading,
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = palette.InkHi
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新全部", fontSize = 11.sp, color = palette.InkHi)
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                    tint = palette.InkMid
                )
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                if (connected.isEmpty()) {
                    Text(
                        "还没有接入任何平台。用上方的平台下拉切换后即可接入。",
                        fontSize = 11.sp,
                        color = palette.InkMid,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    connected.forEachIndexed { index, provider ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(palette.InkHi.copy(alpha = 0.06f))
                            )
                        }
                        OverviewRow(
                            provider = provider,
                            ps = stateMap[provider.id] ?: ProviderState(),
                            history = historyMap[provider.id].orEmpty(),
                            onRefresh = { viewModel.refresh(provider.id) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "接入更多平台：用上方的平台下拉切换。",
                        fontSize = 10.sp,
                        color = palette.InkLo,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                    )
                }
            }
        }
    }
}

/** 总览里的单个平台行：图标 + 名称 + 额度摘要 + 7 天趋势 + 刷新按钮。 */
@Composable
private fun OverviewRow(
    provider: ExtraProvider,
    ps: ProviderState,
    history: List<QuotaHistoryStore.Snapshot>,
    onRefresh: () -> Unit
) {
    val palette = LocalGlassPalette.current
    // 额度口径与趋势口径必须一致（都是「额度窗口」那条），否则两处读数对不上
    // 玻璃卡片实际呈现的颜色：前景色（品牌色 / 额度数字）要对着它算对比度
    val cardBg = palette.cardBackground()
    val groupName = preferredQuotaGroup(ps.groups)
        ?: history.lastOrNull()?.summary?.keys?.firstOrNull()
    val percent = ps.groups[groupName]?.firstOrNull()?.percentRemaining
    // 趋势：每天一根柱，取当天该平台的额度窗口剩余百分比
    val trend = history.map { snap ->
        snap.summary[groupName] ?: snap.summary.values.firstOrNull() ?: 0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌图标底座
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(readableBrandColor(provider.color, cardBg).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                provider.icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = readableBrandColor(provider.color, cardBg)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标题吃掉剩余空间：品牌名有长有短（MiniMax-M3.1-Flash 这种），
                // 不让位的话后面的「剩余 x%」会被挤出去看不见。
                Text(
                    provider.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.InkHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 失败红点：标题行右侧没有额度数字时，红点是这个行唯一的告警信号
                if (ps.error != null) {
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(palette.Down)
                    )
                }
                if (percent != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "剩余 $percent%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = quotaGaugeColor(percent).ensureContrast(cardBg),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ps.account ?: "凭据已生效",
                    fontSize = 10.sp,
                    color = palette.InkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (groupName != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· $groupName",
                        fontSize = 10.sp,
                        color = palette.InkLo,
                        maxLines = 1
                    )
                }
            }
            // 错误文案红字直出：哪个平台凭据失效了，不用逐个点开就知道
            ps.error?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    err,
                    fontSize = 11.sp,
                    color = palette.Down,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TrendSparkline(trend, readableBrandColor(provider.color, cardBg))
        Spacer(Modifier.width(6.dp))
        if (ps.isLoading) {
            Box(
                Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    Modifier.size(14.dp),
                    color = readableBrandColor(provider.color, cardBg),
                    strokeWidth = 2.dp
                )
            }
        } else {
            // 操作加一层底面：裸图标在这个尺寸下读起来像装饰，不像可点的控件
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.InkHi.copy(alpha = 0.06f))
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    "刷新 ${provider.title}",
                    Modifier.size(15.dp),
                    tint = palette.InkMid
                )
            }
        }
    }
}

/**
 * 近 7 天迷你趋势：每天一根柱，柱高 = 当天额度窗口的剩余百分比。
 *
 * 尺寸刻意压到 36×18dp —— 它只是「这周是不是在快速消耗」的旁证，
 * 不该跟额度数字抢注意力；宽度也得省下来给品牌名和账号名
 * （这一行要塞「平台名 + 剩余% + 账号 + 分组」，宽度一紧张名字就被截没了）。
 * 只有 1 个数据点时不画（没有趋势可言）。
 */
@Composable
private fun TrendSparkline(values: List<Int>, color: Color) {
    if (values.size < 2) return
    Canvas(Modifier.size(30.dp, 18.dp)) {
        val n = values.size
        val slot = size.width / n
        val barWidth = (slot * 0.5f).coerceAtLeast(1.5f)
        values.forEachIndexed { i, v ->
            // 0% 也要留一根细柱，否则「彻底用完」和「没数据」看起来一样
            val h = (size.height * (v.coerceIn(0, 100) / 100f)).coerceAtLeast(1.5f)
            // 圆角不能超过柱高的一半：否则矮柱会被画成一个圆点，
            // 「还剩 30%」和「还剩 8%」在图上长得一模一样
            val r = minOf(barWidth / 2f, h / 2f)
            drawRoundRect(
                color = color.copy(alpha = 0.9f),
                topLeft = Offset(slot * i + (slot - barWidth) / 2f, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(r, r)
            )
        }
    }
}
