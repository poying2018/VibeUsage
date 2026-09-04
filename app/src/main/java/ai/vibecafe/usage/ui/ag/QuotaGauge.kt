package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp

/** 额度余量 → 平滑色相渐变：0% 红(4°) 经橙/黄过渡到 100% 绿(140°)。 */
internal fun quotaGaugeColor(percentRemaining: Int): Color {
    val hue = 4f + (percentRemaining.coerceIn(0, 100) / 100f) * 136f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.98f)))
}

/** 玻璃轨道 + 带光泽渐变的额度进度条（统一圆角胶囊造型）。 */
@Composable
internal fun QuotaBarFill(percent: Int, height: Dp, trackColor: Color) {
    val color = quotaGaugeColor(percent)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(lerp(color, Color.White, 0.22f), color)
                    )
                )
        )
    }
}
