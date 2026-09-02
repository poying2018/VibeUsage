package ai.vibecafe.usage.ui.glass

import ai.vibecafe.usage.core.GlassStyleStore
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls

/**
 * 玻璃面 —— 统一采用 kyant catalog LockScreen 演示的配方：
 * drawPlainBackdrop + colorControls(亮度/对比度/饱和度) + blur + 半透明白叠加。
 *
 * 参数由 [GlassStyleStore] 全局持有（设置页「液态玻璃」卡片实时调控）。
 * 不画 highlight/shadow/lens：LockScreen 配方靠 colorControls + 白色叠加产生磨砂质感，
 * 额外图层会跨过 backdrop 缓存边界、在不同 GPU 上放大色差形成"拼贴"分界线。
 */

/** 卡片（对应 Web 的 `.card`，radius 30）。 */
@Composable
fun Modifier.glassCard(
    backdrop: Backdrop,
    cornerRadius: Dp = 30f.dp,
    tint: Color? = null,
    blurRadius: Dp? = null
): Modifier = lockScreenGlass(
    backdrop = backdrop,
    cornerRadius = cornerRadius,
    blurDp = blurRadius?.value ?: GlassStyleStore.style.blurCardDp,
    overlay = tint
)

/** 列表行（对应 Web 的 `.row`，radius 20）。 */
@Composable
fun Modifier.glassRow(
    backdrop: Backdrop,
    cornerRadius: Dp = 20f.dp
): Modifier = lockScreenGlass(
    backdrop = backdrop,
    cornerRadius = cornerRadius,
    blurDp = GlassStyleStore.style.blurRowDp
)

/** 小玻璃块（图标底座 / 头像 / logo 容器 / 芯片按钮）。 */
@Composable
fun Modifier.glassTile(
    backdrop: Backdrop,
    cornerRadius: Dp = 12f.dp,
    tint: Color? = null
): Modifier = lockScreenGlass(
    backdrop = backdrop,
    cornerRadius = cornerRadius,
    blurDp = GlassStyleStore.style.blurTileDp,
    tint = tint
)

/** LockScreen 配方核心：colorControls + blur + 白色叠加（tint 可选叠加其上）。 */
@Composable
private fun Modifier.lockScreenGlass(
    backdrop: Backdrop,
    cornerRadius: Dp,
    blurDp: Float,
    overlay: Color? = null,
    tint: Color? = null
): Modifier {
    val s = GlassStyleStore.style
    val density = LocalDensity.current
    val blurPx = with(density) { blurDp.coerceAtLeast(0f).dp.toPx() }
    val overlayColor = overlay ?: Color.White.copy(alpha = s.whiteOverlay)
    return this.drawPlainBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(cornerRadius) },
        effects = {
            colorControls(brightness = s.brightness, contrast = s.contrast, saturation = s.saturation)
            blur(blurPx)
        },
        onDrawBackdrop = { drawBackdrop ->
            drawBackdrop()
            drawRect(overlayColor)
            tint?.let { drawRect(it) }
        }
    )
}
