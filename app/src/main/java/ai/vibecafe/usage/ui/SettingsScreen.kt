package ai.vibecafe.usage.ui

import ai.vibecafe.usage.core.GlassStyleStore
import ai.vibecafe.usage.core.ThemeMode
import ai.vibecafe.usage.ui.anim.fadeSlideIn
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.LiquidSlider
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.glass.glassTile
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 独立设置页（底部导航第 3 个 Tab）。
 *
 * 整页滚动布局，分组卡片式信息架构：外观 / 背景 / 液态玻璃 / 通用（更新·分享）/ 账号。
 * 每组一张全宽玻璃卡，行内项目带图标底座 + 标题 + 说明 + 右箭头，
 * 分组标题在卡外，错峰滑入。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    showReset: Boolean,
    onCustomBackground: () -> Unit,
    onResetBackground: () -> Unit,
    onShareCard: () -> Unit,
    onLogout: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    appVersion: String
) {
    val p = LocalGlassPalette.current

    Column(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(0),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // —— 页头：大标题 + 副标题（与仪表盘 Header 同级排版）——
        Column(Modifier.padding(top = 4.dp)) {
            Text(
                "设置",
                style = GlassText.Title,
                fontSize = 27.sp,
                color = p.InkHi
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "个性化 · 通用 · 账号",
                style = GlassText.Label,
                fontSize = 12.sp,
                color = p.InkMid
            )
        }

        // —— 外观 ——
        SectionLabel("外观")
        // 卡片把纯玻璃表面导出为 backdrop；卡内玻璃芯片取样「页面 + 卡片表面」组合层，
        // 折射/模糊到真实盖住的内容（exportedBackdrop 不含内容层，无自引用递归风险）
        val lookCardSurface = rememberLayerBackdrop()
        val chipBackdrop = rememberCombinedBackdrop(backdrop, lookCardSurface)
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(40)
                .glassCard(backdrop, cornerRadius = 26.dp, exportedBackdrop = lookCardSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBase(Icons.Filled.DarkMode)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("主题模式", style = GlassText.Body)
                    Text(
                        "当前：${themeMode.label}",
                        style = GlassText.Meta,
                        color = p.InkMid
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    ThemeChip(
                        backdrop = chipBackdrop,
                        mode = mode,
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // —— 背景 ——
        SectionLabel("背景")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(80)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.PhotoLibrary,
                title = "自定义背景",
                subtitle = "从相册选择一张图片铺满页面",
                onClick = onCustomBackground
            )
            if (showReset) {
                CardDivider()
                SettingRow(
                    backdrop = backdrop,
                    icon = Icons.Filled.RestartAlt,
                    title = "恢复默认背景",
                    subtitle = "清除当前背景图",
                    onClick = onResetBackground
                )
            }
        }

        // —— 液态玻璃（catalog 完整配方，全局实时生效）——
        SectionLabel("液态玻璃")
        // 滑杆/滑块是卡内嵌套玻璃：取样「页面 + 卡片表面」组合层（同外观卡）
        val styleCardSurface = rememberLayerBackdrop()
        val sliderBackdrop = rememberCombinedBackdrop(backdrop, styleCardSurface)
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(120)
                .glassCard(backdrop, cornerRadius = 26.dp, exportedBackdrop = styleCardSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBase(Icons.Filled.Tune)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("液态玻璃", style = GlassText.Body)
                    Text(
                        "折射 + 磨砂配方 · 全部玻璃面板实时生效",
                        style = GlassText.Meta,
                        color = p.InkMid
                    )
                }
            }
            val style = GlassStyleStore.style
            val context = LocalContext.current
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "亮度",
                valueText = String.format("%.2f", style.brightness),
                value = style.brightness,
                valueRange = -0.5f..0.5f,
                onChange = { GlassStyleStore.set(context, style.copy(brightness = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "对比度",
                valueText = String.format("%.2f", style.contrast),
                value = style.contrast,
                valueRange = 0.5f..1.5f,
                onChange = { GlassStyleStore.set(context, style.copy(contrast = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "饱和度",
                valueText = String.format("%.2f", style.saturation),
                value = style.saturation,
                valueRange = 0f..2f,
                onChange = { GlassStyleStore.set(context, style.copy(saturation = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "模糊",
                valueText = "${style.blurCardDp.toInt()}dp",
                value = style.blurCardDp,
                valueRange = 0f..32f,
                onChange = { GlassStyleStore.set(context, style.copy(blurCardDp = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "白色叠加",
                valueText = "${(style.whiteOverlay * 100).toInt()}%",
                value = style.whiteOverlay,
                valueRange = 0f..0.5f,
                onChange = { GlassStyleStore.set(context, style.copy(whiteOverlay = it)) }
            )
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.RestartAlt,
                title = "恢复默认",
                subtitle = "还原默认玻璃参数",
                showChevron = false,
                onClick = { GlassStyleStore.set(context, GlassStyleStore.Default) }
            )
        }

        // —— 通用 ——
        SectionLabel("通用")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(160)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            // 软件更新（随流程阶段切换内容）
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBase(Icons.Filled.SystemUpdate)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("软件更新", style = GlassText.Body)
                        Text(
                            "当前版本 $appVersion",
                            style = GlassText.Meta,
                            color = p.InkMid
                        )
                    }
                }

                when (update.status) {
                    UpdateStatus.IDLE -> {}

                    UpdateStatus.CHECKING -> UpdateHint("正在检查更新…", p.InkMid)

                    UpdateStatus.UP_TO_DATE -> {
                        UpdateHint(update.message ?: "已是最新版本", p.AccentInk)
                    }

                    UpdateStatus.AVAILABLE -> {
                        UpdateHint(
                            "发现新版本 ${update.version?.removePrefix("v")}",
                            p.InkHi
                        )
                    }

                    UpdateStatus.DOWNLOADING -> {
                        UpdateHint("正在下载… ${(update.progress * 100).toInt()}%", p.InkMid)
                        LinearProgressIndicator(
                            progress = { update.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = p.Accent,
                            trackColor = p.InkLo.copy(alpha = 0.5f)
                        )
                    }

                    UpdateStatus.DOWNLOADED -> {
                        UpdateHint(
                            "${update.version?.removePrefix("v")} 已下载",
                            p.AccentInk
                        )
                    }

                    // 发现新版本但该版本尚未发布带 APK 的 Release（只打了 tag）
                    UpdateStatus.NO_APK -> {
                        Text(
                            update.message ?: "发现新版本，安装包暂未发布",
                            style = GlassText.Meta,
                            color = p.InkMid,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    UpdateStatus.FAILED -> {
                        update.message?.let { UpdateHint(it, p.Down) }
                    }
                }

                when (update.status) {
                    UpdateStatus.IDLE,
                    UpdateStatus.UP_TO_DATE,
                    UpdateStatus.NO_APK,
                    UpdateStatus.FAILED ->
                        UpdateButton(
                            backdrop = backdrop,
                            label = when (update.status) {
                                UpdateStatus.UP_TO_DATE -> "重新检查"
                                UpdateStatus.FAILED -> "重试检查"
                                else -> "检查更新"
                            },
                            onClick = onCheckUpdate
                        )

                    UpdateStatus.AVAILABLE ->
                        UpdateButton(backdrop = backdrop, label = "下载更新", onClick = onDownloadUpdate, emphasis = true)

                    UpdateStatus.DOWNLOADED ->
                        UpdateButton(backdrop = backdrop, label = "安装更新", onClick = onInstallUpdate, emphasis = true)

                    else -> {}
                }
            }

            CardDivider()

            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.Share,
                title = "分享用量卡",
                subtitle = "生成并分享当前用量卡片",
                onClick = onShareCard
            )
        }

        // —— 账号 ——
        SectionLabel("账号")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(200)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            SettingRow(
                backdrop = backdrop,
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "退出登录",
                subtitle = "清除本机 API Key 并返回登录页",
                danger = true,
                showChevron = false,
                onClick = onLogout
            )
        }

        // —— 页脚 ——
        Text(
            "VibeUsage $appVersion · API 用量统计",
            style = GlassText.Meta,
            fontSize = 10.5.sp,
            color = p.InkLo,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .clickable(remember { MutableInteractionSource() }, null, onClick = {}),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 分组标题（卡片外的小标签）。 */
@Composable
private fun SectionLabel(text: String) {
    val p = LocalGlassPalette.current
    Text(
        text,
        style = GlassText.Label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = p.InkLo,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp)
    )
}

/** 行首图标底座：小玻璃块 + 主题色图标。 */
@Composable
private fun IconBase(icon: ImageVector) {
    val p = LocalGlassPalette.current
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.AccentWash),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = p.AccentInk,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 设置行：图标 + 标题/说明 + 右箭头。
 * 独立页里行不需要自己的玻璃底（卡片已提供），保持层次干净。
 */
@Composable
private fun SettingRow(
    backdrop: Backdrop,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val p = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (danger) p.DownWash else p.AccentWash),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) p.Down else p.AccentInk,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = GlassText.Body,
                color = if (danger) p.Down else p.InkHi
            )
            subtitle?.let {
                Spacer(Modifier.height(1.dp))
                Text(it, style = GlassText.Meta, color = p.InkMid, maxLines = 1)
            }
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = p.InkLo,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 卡片内两行之间的细分隔线（左端对齐文字缩进）。 */
@Composable
private fun CardDivider() {
    val p = LocalGlassPalette.current
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = p.InkLo.copy(alpha = 0.18f)
    )
}

@Composable
private fun UpdateHint(text: String, color: Color) {
    Text(text, style = GlassText.Meta, color = color)
}

/** 更新动作按钮：液态玻璃胶囊；主要（下载/安装）叠青蓝渐变表面。 */
@Composable
private fun UpdateButton(
    backdrop: Backdrop,
    label: String,
    onClick: () -> Unit,
    emphasis: Boolean = false
) {
    val p = LocalGlassPalette.current
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        surfaceBrush = if (emphasis) {
            Brush.horizontalGradient(
                listOf(p.Accent, Color(0xFF4E7BFF)),
                startX = 0f,
                endX = 900f
            )
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = GlassText.Chip,
            color = if (emphasis) Color.White else p.AccentInk
        )
    }
}

@Composable
private fun ThemeChip(
    backdrop: Backdrop,
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(34.dp)
            .glassTile(
                backdrop,
                cornerRadius = 12.dp,
                tint = if (selected) p.AccentWash else null
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, p.AccentRim, shape)
                } else {
                    Modifier
                }
            )
            .clickable(interaction, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            mode.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) p.AccentInk else p.InkMid,
            maxLines = 1
        )
    }
}

/** 液态玻璃参数滑杆行：左侧标签 + 液态滑杆 + 右侧当前值。 */
@Composable
private fun GlassStyleSliderRow(
    backdrop: Backdrop,
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val p = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = GlassText.Label, color = p.InkMid)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = GlassText.NumericSmall, color = p.InkHi)
        }
        LiquidSlider(
            value = { value.coerceIn(valueRange.start, valueRange.endInclusive) },
            onValueChange = onChange,
            valueRange = valueRange,
            visibilityThreshold = 0.01f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
