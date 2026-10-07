package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 额度余量 → 平滑色相渐变：0% 红(4°) 经橙/黄过渡到 100% 绿(140°)。 */
internal fun quotaGaugeColor(percentRemaining: Int): Color {
    val hue = 4f + (percentRemaining.coerceIn(0, 100) / 100f) * 136f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.98f)))
}

/**
 * 发光胶囊额度条：暗轨道 + 同色辉光填充 + 顶部内高光。
 *
 * 三层结构对应液态玻璃的观感——轨道是压暗的玻璃底，填充层用彩色 shadow 往外洇一圈光
 * （和 App 的霓虹/光斑背景呼应），再在最上沿叠一条白色渐变模拟玻璃上沿受光。
 *
 * 注意：轨道用 `background(color, shape)` 而不是 `clip(shape)`，否则填充层的辉光
 * 会被轨道的裁剪边界切掉，glow 就白做了。
 *
 * 彩色 shadow 需要 API 28+（minSdk 26 时低版本自动降级为普通投影，不影响可读性）。
 */
@Composable
internal fun QuotaBarFill(percent: Int, height: Dp, trackColor: Color) {
    val color = quotaGaugeColor(percent)
    val pct = percent.coerceIn(0, 100)
    val shape = RoundedCornerShape(50)

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor, shape)
    ) {
        if (pct > 0) {
            Box(
                Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(height)
                    // 同色辉光：把填充层当光源，ambient 给一圈环境光、spot 给定向光
                    .shadow(
                        elevation = 7.dp,
                        shape = shape,
                        ambientColor = color.copy(alpha = 0.45f),
                        spotColor = color.copy(alpha = 0.55f),
                    )
                    .clip(shape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(lerp(color, Color.White, 0.26f), color)
                        )
                    )
            ) {
                // 顶部内高光：压在胶囊上沿，随父级 clip 自然贴合圆角
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(height * 0.42f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.32f), Color.Transparent)
                            )
                        )
                )
            }
        }
    }
}