package ai.vibecafe.usage.ui.charts

import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** 环形图的一个扇区。 */
data class DonutSlice(
    val label: String,
    val value: Float,
    val color: Color
)

/**
 * 占比环形图：圆角扇区 + 逐段展开动画 + 中心汇总文字。
 * 适合展示应用 / 模型分布占比。
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = 138.dp,
    stroke: Dp = 18.dp,
    centerTop: String = "",
    centerBottom: String = ""
) {
    val p = LocalGlassPalette.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val textMeasurer = rememberTextMeasurer()
    val total = slices.sumOf { it.value.toDouble() }.toFloat()

    Canvas(modifier.size(diameter)) {
        val w = diameter.toPx()
        val h = diameter.toPx()
        val center = Offset(w / 2f, h / 2f)
        val radius = (w - stroke.toPx()) / 2f
        val gapDeg = if (slices.size > 1) 3.5f else 0f
        val reveal = progress.value

        if (total <= 0f || slices.isEmpty()) {
            // 空态：一圈浅色环
            drawArc(
                color = p.InkLo,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            )
        } else {
            var start = -90f
            val visible = slices.filter { it.value > 0f }
            val visibleTotal = visible.sumOf { it.value.toDouble() }.toFloat()
            visible.forEachIndexed { index, slice ->
                val sweep = (slice.value / visibleTotal) * 360f
                val drawSweep = ((sweep - gapDeg).coerceAtLeast(if (index == visible.size - 1) sweep * 0.8f else 0.5f)) * reveal
                drawArc(
                    color = slice.color,
                    startAngle = start,
                    sweepAngle = drawSweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
                )
                start += sweep
            }
        }

        // ---- 中心汇总文字 ----
        val topLayout = textMeasurer.measure(
            AnnotatedString(centerTop),
            style = GlassText.NumericSmall.copy(color = p.InkHi, fontSize = 21.sp)
        )
        val bottomLayout = textMeasurer.measure(
            AnnotatedString(centerBottom),
            style = GlassText.ChartAxis.copy(color = p.InkMid, fontSize = 11.sp)
        )
        val gapY = 3.dp.toPx()
        val totalH = topLayout.size.height + gapY + bottomLayout.size.height
        val topY = center.y - totalH / 2f
        drawText(topLayout, topLeft = Offset(center.x - topLayout.size.width / 2f, topY))
        drawText(bottomLayout, topLeft = Offset(center.x - bottomLayout.size.width / 2f, topY + topLayout.size.height + gapY))
    }
}

/** 供图例使用：把占比转成可读的百分比文案。 */
fun percentText(percentage: Float): String = "${min(percentage, 100f).let { String.format("%.0f", it) }}%"
