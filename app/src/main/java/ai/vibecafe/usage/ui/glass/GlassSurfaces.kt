package ai.vibecafe.usage.ui.glass

import ai.vibecafe.usage.ui.theme.Glass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow

/**
 * 玻璃卡片 —— 对应 Web 的 `.card`：
 * radius 30 / rgba(255,255,255,.55) / blur(28) saturate(150%) / 1px 白描边 70% /
 * 0 30px 80px rgba(70,90,130,.18)
 */
fun Modifier.glassCard(
    backdrop: Backdrop,
    cornerRadius: Dp = 30f.dp,
    tint: Color = Glass.Surface,
    blurRadius: Dp = 28f.dp
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(cornerRadius) },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
    },
    highlight = null,
    shadow = {
        Shadow(
            radius = 40f.dp,
            offset = DpOffset(0f.dp, 16f.dp),
            color = Glass.ShadowSoft
        )
    },
    onDrawSurface = {
        drawRoundRect(color = tint, cornerRadius = CornerRadius(cornerRadius.toPx()))
    },
    onDrawFront = {
        drawRim(Glass.Rim, 1f.dp.toPx(), cornerRadius.toPx())
    }
)

/**
 * 玻璃列表行 —— 对应 Web 的 `.row`：
 * radius 20 / rgba(255,255,255,.5) / blur(16) / 1px 白描边 60% / 0 8px 20px rgba(70,90,130,.1)
 */
fun Modifier.glassRow(
    backdrop: Backdrop,
    cornerRadius: Dp = 20f.dp
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(cornerRadius) },
    effects = {
        vibrancy()
        blur(16f.dp.toPx())
    },
    highlight = null,
    shadow = {
        Shadow(
            radius = 20f.dp,
            offset = DpOffset(0f.dp, 8f.dp),
            color = Glass.ShadowRow
        )
    },
    onDrawSurface = {
        drawRoundRect(color = Glass.SurfaceSoft, cornerRadius = CornerRadius(cornerRadius.toPx()))
    },
    onDrawFront = {
        drawRim(Color.White.copy(alpha = 0.6f), 1f.dp.toPx(), cornerRadius.toPx())
    }
)

/** 小玻璃块（列表行左侧图标底座 / 头像 / logo 容器）。 */
fun Modifier.glassTile(
    backdrop: Backdrop,
    cornerRadius: Dp = 12f.dp
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(cornerRadius) },
    effects = {
        vibrancy()
        blur(10f.dp.toPx())
    },
    highlight = null,
    shadow = null,
    onDrawSurface = {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    },
    onDrawFront = {
        drawRim(Glass.Rim, 1f.dp.toPx(), cornerRadius.toPx())
    }
)

/** 内描边（CSS `border:1px solid` / `inset 0 0 0 1px` 的等价画法）。 */
internal fun DrawScope.drawRim(color: Color, stroke: Float, radius: Float) {
    val half = stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((radius - half).coerceAtLeast(0f)),
        style = Stroke(stroke)
    )
}
