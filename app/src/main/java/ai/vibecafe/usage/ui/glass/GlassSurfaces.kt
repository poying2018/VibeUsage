package ai.vibecafe.usage.ui.glass

import ai.vibecafe.usage.core.GlassStyleStore
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 玻璃面 —— catalog（Kyant0/AndroidLiquidGlass）完整配方：
 * colorControls(亮度/对比度/饱和度) + blur 磨砂基底（LockScreen 同源）
 * + lens 边缘折射（酷安底部栏壳的同款比例）+ Highlight 45° 边缘高光
 * + Shadow 外投影 + 顶部内高光渐变，恢复玻璃的深度线索。
 *
 * 参数由 [GlassStyleStore] 全局持有（设置页「液态玻璃」卡片实时调控）。
 * 高光/投影/折射都在同一 drawBackdrop 节点层内绘制，不跨 backdrop 缓存边界，
 * 不会复发此前 highlight/shadow/lens 造成的「拼贴」分界线伪影。
 */

/** 卡片（对应 Web 的 `.card`，radius 30）。 */
@Composable
fun Modifier.glassCard(
    backdrop: Backdrop,
    cornerRadius: Dp = 30f.dp,
    tint: Color? = null,
    blurRadius: Dp? = null,
    exportedBackdrop: LayerBackdrop? = null
): Modifier = liquidGlass(
    backdrop = backdrop,
    cornerRadius = cornerRadius,
    blurDp = blurRadius?.value ?: GlassStyleStore.style.blurCardDp,
    tint = tint,
    shadowRadius = 24f.dp,
    exportedBackdrop = exportedBackdrop
)

/** 列表行（对应 Web 的 `.row`，radius 20）。 */
@Composable
fun Modifier.glassRow(
    backdrop: Backdrop,
    cornerRadius: Dp = 20f.dp
): Modifier = liquidGlass(
    backdrop = backdrop,
    cornerRadius = cornerRadius,
    blurDp = GlassStyleStore.style.blurRowDp,
    shadowRadius = 14f.dp
)

/** 小玻璃块（图标底座 / 头像 / logo 容器 / 芯片按钮）。 */
@Composable
fun Modifier.glassTile(
    backdrop: Backdrop,
    cornerRadius: Dp = 12f.dp,
    tint: Color? = null
): Modifier = liquidGlass(
    backdrop = backdrop,
    cornerRadius = cornerRadius,
    blurDp = GlassStyleStore.style.blurTileDp,
    tint = tint,
    shadowRadius = 8f.dp
)

/** catalog 完整配方核心：磨砂（colorControls + blur + 白叠加）+ lens 折射 + 高光 + 投影。 */
@Composable
private fun Modifier.liquidGlass(
    backdrop: Backdrop,
    cornerRadius: Dp,
    blurDp: Float,
    tint: Color? = null,
    shadowRadius: Dp,
    exportedBackdrop: LayerBackdrop? = null
): Modifier {
    val s = GlassStyleStore.style
    val p = LocalGlassPalette.current
    val density = LocalDensity.current
    val blurPx = with(density) { blurDp.coerceAtLeast(0f).dp.toPx() }
    // 折射带宽/强度随圆角缩放（酷安壳 r32 → lens(24,24) 同款比例），小元件设保底值
    val lensHeight = with(density) { (cornerRadius.value * 0.75f).dp.coerceAtLeast(6f.dp).toPx() }
    val lensAmount = with(density) { (cornerRadius.value * 0.85f).dp.coerceAtLeast(8f.dp).toPx() }
    val overlayColor = Color.White.copy(alpha = s.whiteOverlay)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(cornerRadius) },
        effects = {
            colorControls(brightness = s.brightness, contrast = s.contrast, saturation = s.saturation)
            blur(blurPx)
            lens(lensHeight, lensAmount)
        },
        highlight = { Highlight.Default },
        shadow = { Shadow(radius = shadowRadius, color = p.ShadowSoft) },
        onDrawBackdrop = { drawBackdrop ->
            drawBackdrop()
            drawRect(overlayColor)
            tint?.let { drawRect(it) }
        },
        onDrawSurface = {
            drawRect(
                Brush.verticalGradient(
                    0f to p.SheenTop,
                    0.22f to Color.Transparent
                )
            )
        },
        exportedBackdrop = exportedBackdrop
    )
}
