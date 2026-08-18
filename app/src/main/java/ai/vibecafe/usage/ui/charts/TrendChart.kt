package ai.vibecafe.usage.ui.charts

import ai.vibecafe.usage.stats.DailyUsage
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.vibecafe.usage.ui.formatCost
import kotlin.math.max

/**
 * 用量趋势面积图：平滑曲线 + 渐变填充 + 入场展开动画。
 * 数据来自 [DailyUsage] 序列（日 / 小时粒度），纵轴为消耗金额（USD）。
 */
@Composable
fun TrendChart(
    data: List<DailyUsage>,
    modifier: Modifier = Modifier,
    height: Dp = 152.dp,
    accent: Color = Color(0xFF0FC6B6),
    secondary: Color = Color(0xFF5C7FFF)
) {
    val p = LocalGlassPalette.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
    }
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val padLeft = 4.dp.toPx()
        val padRight = 4.dp.toPx()
        val padTop = 22.dp.toPx()    // 留给 y 轴最大值标签
        val padBottom = 18.dp.toPx() // 留给首尾日期标签
        val plotW = (w - padLeft - padRight).coerceAtLeast(1f)
        val plotH = (h - padTop - padBottom).coerceAtLeast(1f)
        val baselineY = padTop + plotH

        val n = data.size
        if (n == 0) return@Canvas
        val maxCost = data.maxOf { it.cost }
        val maxV = max(maxCost, 0.001)

        fun xOf(i: Int): Float = padLeft + if (n == 1) plotW / 2f else plotW * i / (n - 1).toFloat()
        fun yOf(cost: Double): Float = padTop + plotH * (1f - (cost / maxV).toFloat().coerceIn(0f, 1f))

        // ---- 横向刻度虚线（0 / 1/3 / 2/3 / 满刻度）----
        val gridColor = p.InkLo.copy(alpha = p.InkLo.alpha * 0.8f)
        for (f in 0..3) {
            val y = padTop + plotH * (1f - f / 3f)
            var x = padLeft
            while (x < w - padRight) {
                val seg = minOf(6.dp.toPx(), w - padRight - x)
                drawLine(gridColor, Offset(x, y), Offset(x + seg, y), strokeWidth = 1f.dp.toPx())
                x += 12.dp.toPx()
            }
        }

        // ---- 面积路径（平滑二次贝塞尔）----
        val area = Path()
        val line = Path()
        if (n >= 1) {
            val start = Offset(xOf(0), yOf(data[0].cost))
            line.moveTo(start.x, start.y)
            area.moveTo(start.x, start.y)
            for (i in 1 until n) {
                val prev = Offset(xOf(i - 1), yOf(data[i - 1].cost))
                val cur = Offset(xOf(i), yOf(data[i].cost))
                val midX = (prev.x + cur.x) / 2f
                line.quadraticTo(prev.x, prev.y, midX, (prev.y + cur.y) / 2f)
                area.quadraticTo(prev.x, prev.y, midX, (prev.y + cur.y) / 2f)
            }
            val last = Offset(xOf(n - 1), yOf(data[n - 1].cost))
            line.lineTo(last.x, last.y)
            area.lineTo(last.x, last.y)
            area.lineTo(last.x, baselineY)
            area.lineTo(start.x, baselineY)
            area.close()
        }

        // ---- 入场展开：从左向右揭示 ----
        val reveal = progress.value
        clipRect(left = 0f, top = 0f, right = w * reveal, bottom = h) {
            // 渐变填充
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to accent.copy(alpha = 0.34f),
                        0.65f to accent.copy(alpha = 0.08f),
                        1f to Color.Transparent
                    ),
                    startY = padTop,
                    endY = baselineY
                )
            )
            // 主曲线（teal → violet 渐变描边）
            drawPath(
                path = line,
                brush = Brush.linearGradient(listOf(accent, secondary)),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // ---- 数据点（点少才画，避免密集噪点）----
        if (n <= 12) {
            val dotAlpha = reveal
            for (i in 0 until n) {
                val c = Offset(xOf(i), yOf(data[i].cost))
                drawCircle(Color.White.copy(alpha = 0.95f * dotAlpha), radius = 3.6.dp.toPx(), center = c)
                drawCircle(
                    accent.copy(alpha = 0.9f * dotAlpha),
                    radius = 3.6.dp.toPx(),
                    center = c,
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // ---- 标签 ----
        val labelColor = p.InkMid
        val maxLabel = textMeasurer.measure(
            AnnotatedString(formatCost(maxCost)),
            style = GlassText.ChartAxis.copy(color = labelColor, fontSize = 10.sp)
        )
        drawText(maxLabel, topLeft = Offset(padLeft, 2.dp.toPx()))
        if (n > 1) {
            val firstLabel = textMeasurer.measure(
                AnnotatedString(data.first().date),
                style = GlassText.ChartAxis.copy(color = labelColor, fontSize = 10.sp)
            )
            val lastLabel = textMeasurer.measure(
                AnnotatedString(data.last().date),
                style = GlassText.ChartAxis.copy(color = labelColor, fontSize = 10.sp)
            )
            drawText(firstLabel, topLeft = Offset(padLeft, h - firstLabel.size.height - 1.dp.toPx()))
            drawText(
                lastLabel,
                topLeft = Offset(w - padRight - lastLabel.size.width, h - lastLabel.size.height - 1.dp.toPx())
            )
        }
    }
}
