package ai.vibecafe.usage.ui.charts

import ai.vibecafe.usage.stats.DailyUsage
import ai.vibecafe.usage.ui.formatCost
import ai.vibecafe.usage.ui.formatTokens
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/** 趋势图纵轴指标：消耗金额 / Tokens 总量。 */
enum class TrendMetric { COST, TOKENS }

/** 纵轴上限吸附到「整数阶梯」，避免 781.7M / 521.2M 这类随意刻度。 */
private fun niceAxisMax(v: Double): Double {
    if (v <= 0.0) return 1.0
    val base = Math.pow(10.0, Math.floor(Math.log10(v)))
    val m = v / base
    val ladder = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
    val nice = ladder.first { m <= it }
    return nice * base
}

/** 轴标签：与数据气泡同源格式化，但去掉无意义的 ".0"（800.0M -> 800M / $4.00 -> $4）。 */
private fun axisLabel(v: Double, metric: TrendMetric): String {
    val s = when (metric) {
        TrendMetric.COST -> formatCost(v)
        TrendMetric.TOKENS -> formatTokens(v.toLong())
    }
    // 仅去掉紧邻单位后缀（k/M/B）或串尾的零小数，避免误伤 380.6 中的有效位
    return s.replace(Regex("\\.0+(?=[kMB]|$)"), "")
}

/**
 * 用量趋势面积图：平滑曲线 + 渐变填充 + 入场展开动画。
 * 数据来自 [DailyUsage] 序列（日 / 小时粒度），纵轴由 [metric] 决定。
 * 支持点按 / 横向拖动：竖直虚线参考线 + 玻璃数值气泡，可与纵向滚动并存。
 */
@Composable
fun TrendChart(
    data: List<DailyUsage>,
    modifier: Modifier = Modifier,
    height: Dp = 152.dp,
    metric: TrendMetric = TrendMetric.COST,
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

    fun elemValue(d: DailyUsage): Double = when (metric) {
        TrendMetric.COST -> d.cost
        TrendMetric.TOKENS -> d.tokens.toDouble()
    }

    // 指标切换动画：从上一指标的数值序列插值到当前序列，曲线"流动"过渡
    // key 必须含 metric：切换指标时重算数值序列，触发 morph 动画
    val animValues = remember(data, metric) { data.map { elemValue(it) } }
    var prevValues by remember { mutableStateOf(animValues) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(animValues) {
        if (animValues != prevValues && prevValues.size == animValues.size) {
            morph.snapTo(0f)
            morph.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        }
        prevValues = animValues
    }
    val fromValues = if (prevValues.size == animValues.size) prevValues else animValues

    // 选中点索引，-1 表示未选中；数据或指标切换后复位
    var selected by remember(data, metric) { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(data, metric) {
                // 画布内边距与 draw 阶段保持一致：左右各 4dp
                fun indexAt(x: Float): Int {
                    if (data.isEmpty()) return -1
                    val pad = 4.dp.toPx()
                    val plotW = (size.width - pad * 2).coerceAtLeast(1f)
                    return (((x - pad) / plotW) * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
                }
                detectTapGestures { offset -> selected = indexAt(offset.x) }
            }
            .pointerInput(data, metric) {
                // 仅响应横向拖动，不与外层纵向滚动手势冲突
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val pad = 4.dp.toPx()
                    val plotW = (size.width - pad * 2).coerceAtLeast(1f)
                    selected = (((change.position.x - pad) / plotW) * (data.size - 1))
                        .roundToInt().coerceIn(0, data.size - 1)
                }
            }
    ) {
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

        fun elemValue(d: DailyUsage): Double = when (metric) {
            TrendMetric.COST -> d.cost
            TrendMetric.TOKENS -> d.tokens.toDouble()
        }

        fun formatValue(v: Double): String = when (metric) {
            TrendMetric.COST -> formatCost(v)
            TrendMetric.TOKENS -> formatTokens(v.toLong())
        }

        // 指标切换动画：从上一序列插值到当前序列
        val morphT = morph.value
        val values = if (morphT >= 1f) animValues else List(animValues.size) { i ->
            val a = fromValues.getOrElse(i) { 0.0 }
            a + (animValues[i] - a) * morphT
        }
        fun valueOf(i: Int): Double = values.getOrElse(i) { 0.0 }

        // 纵轴上限吸附到整数阶梯，四分位刻度都是整数
        val axisMax = niceAxisMax(values.maxOrNull() ?: 0.001)
        val avgV = if (values.isEmpty()) 0.0 else values.sum() / values.size

        fun xOf(i: Int): Float = padLeft + if (n == 1) plotW / 2f else plotW * i / (n - 1).toFloat()
        fun yOf(v: Double): Float = padTop + plotH * (1f - (v / axisMax).toFloat().coerceIn(0f, 1f))

        // ---- 横向刻度虚线（0 / 1/4 / 1/2 / 3/4 / 满刻度）----
        val gridColor = p.InkLo.copy(alpha = p.InkLo.alpha * 0.8f)
        for (f in 0..4) {
            val y = padTop + plotH * (1f - f / 4f)
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
        val start = Offset(xOf(0), yOf(valueOf(0)))
        line.moveTo(start.x, start.y)
        area.moveTo(start.x, start.y)
        for (i in 1 until n) {
            val prev = Offset(xOf(i - 1), yOf(valueOf(i - 1)))
            val cur = Offset(xOf(i), yOf(valueOf(i)))
            val midX = (prev.x + cur.x) / 2f
            line.quadraticTo(prev.x, prev.y, midX, (prev.y + cur.y) / 2f)
            area.quadraticTo(prev.x, prev.y, midX, (prev.y + cur.y) / 2f)
        }
        val last = Offset(xOf(n - 1), yOf(valueOf(n - 1)))
        line.lineTo(last.x, last.y)
        area.lineTo(last.x, last.y)
        area.lineTo(last.x, baselineY)
        area.lineTo(start.x, baselineY)
        area.close()

        // ---- 入场展开：从左向右揭示 ----
        val reveal = progress.value
        clipRect(left = 0f, top = 0f, right = w * reveal, bottom = h) {
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
                val c = Offset(xOf(i), yOf(valueOf(i)))
                drawCircle(Color.White.copy(alpha = 0.95f * dotAlpha), radius = 3.6.dp.toPx(), center = c)
                drawCircle(
                    accent.copy(alpha = 0.9f * dotAlpha),
                    radius = 3.6.dp.toPx(),
                    center = c,
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // ---- 峰值标注（数据较多时才显示，避免与数据点重复）----
        if (n >= 7) {
            val maxI = values.indices.maxByOrNull { values[it] } ?: -1
            if (maxI >= 0 && maxI != selected) {
                val px = xOf(maxI)
                val py = yOf(valueOf(maxI))
                val d = 3.4.dp.toPx()
                // 菱形标记
                drawPath(
                    Path().apply {
                        moveTo(px, py - d); lineTo(px + d, py)
                        lineTo(px, py + d); lineTo(px - d, py); close()
                    },
                    secondary.copy(alpha = 0.95f * reveal)
                )
                val peakLab = textMeasurer.measure(
                    AnnotatedString("峰 " + axisLabel(valueOf(maxI), metric)),
                    style = GlassText.ChartAxis.copy(color = secondary, fontSize = 9.sp)
                )
                val lx = (px - peakLab.size.width / 2f).coerceIn(padLeft, (w - padRight - peakLab.size.width).coerceAtLeast(padLeft))
                val ly = (py - peakLab.size.height - 9.dp.toPx()).coerceAtLeast(1.dp.toPx())
                drawText(peakLab, topLeft = Offset(lx, ly))
            }
        }

        // ---- 均值参考虚线 ----
        if (n >= 2) {
            val avgY = yOf(avgV)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
            drawLine(
                secondary.copy(alpha = 0.45f * reveal),
                Offset(padLeft, avgY),
                Offset(w - padRight, avgY),
                strokeWidth = 1.2f.dp.toPx(),
                pathEffect = dash
            )
            val avgLabel = textMeasurer.measure(
                AnnotatedString("均 " + formatValue(avgV)),
                style = GlassText.ChartAxis.copy(color = secondary.copy(alpha = 0.85f * reveal), fontSize = 9.sp)
            )
            val labelY = if (avgY - avgLabel.size.height - 3.dp.toPx() > padTop) {
                avgY - avgLabel.size.height - 3.dp.toPx()
            } else {
                avgY + 4.dp.toPx()
            }
            drawText(avgLabel, topLeft = Offset(padLeft, labelY))
        }

        // ---- 标签 ----
        val labelColor = p.InkMid
        val axisStyle = GlassText.ChartAxis.copy(color = labelColor, fontSize = 10.sp)
        val maxLabel = textMeasurer.measure(AnnotatedString(axisLabel(axisMax, metric)), style = axisStyle)
        drawText(maxLabel, topLeft = Offset(padLeft, 2.dp.toPx()))

        // 1/4、1/2、3/4 刻度值（右侧对齐，弱化显示；整数阶梯保证可读）
        val subStyle = GlassText.ChartAxis.copy(
            color = labelColor.copy(alpha = labelColor.alpha * 0.55f),
            fontSize = 9.sp
        )
        for (frac in listOf(3f / 4f, 1f / 2f, 1f / 4f)) {
            val lab = textMeasurer.measure(AnnotatedString(axisLabel(axisMax * frac, metric)), style = subStyle)
            drawText(
                lab,
                topLeft = Offset(
                    w - padRight - lab.size.width,
                    padTop + plotH * (1f - frac) - lab.size.height - 2.dp.toPx()
                )
            )
        }

        // ---- x 轴：首 / 中 / 尾 ----
        if (n > 1) {
            val firstLab = textMeasurer.measure(AnnotatedString(data.first().date), style = axisStyle)
            val lastLab = textMeasurer.measure(AnnotatedString(data.last().date), style = axisStyle)
            val labelBottom = h - firstLab.size.height - 1.dp.toPx()
            drawText(firstLab, topLeft = Offset(padLeft, labelBottom))
            drawText(lastLab, topLeft = Offset(w - padRight - lastLab.size.width, labelBottom))
            if (n >= 5) {
                val mid = n / 2
                val midLab = textMeasurer.measure(AnnotatedString(data[mid].date), style = axisStyle)
                val midX = xOf(mid) - midLab.size.width / 2f
                if (midX > padLeft + firstLab.size.width + 8.dp.toPx() &&
                    midX + midLab.size.width < w - padRight - lastLab.size.width - 8.dp.toPx()
                ) {
                    drawText(midLab, topLeft = Offset(midX, labelBottom))
                }
            }
        }

        // ---- 选中指示：参考线 + 高亮点 + 玻璃气泡 ----
        if (selected in 0 until n) {
            val i = selected
            val px = xOf(i)
            val py = yOf(valueOf(i))
            drawLine(
                p.InkHi.copy(alpha = 0.45f * reveal),
                Offset(px, padTop),
                Offset(px, baselineY),
                strokeWidth = 1.2f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
            drawCircle(Color.White.copy(alpha = 0.97f * reveal), radius = 4.6.dp.toPx(), center = Offset(px, py))
            drawCircle(
                accent.copy(alpha = 0.95f * reveal),
                radius = 4.6.dp.toPx(),
                center = Offset(px, py),
                style = Stroke(width = 1.8f.dp.toPx())
            )

            val v = valueOf(i)
            val t1 = textMeasurer.measure(
                AnnotatedString(data[i].date),
                style = GlassText.ChartAxis.copy(color = p.InkHi, fontSize = 10.sp)
            )
            val detail = when (metric) {
                TrendMetric.COST -> formatCost(v) + " · " + formatTokens(data[i].tokens)
                TrendMetric.TOKENS -> formatTokens(v.toLong()) + " · " + formatCost(data[i].cost)
            }
            val t2 = textMeasurer.measure(
                AnnotatedString(detail),
                style = GlassText.ChartAxis.copy(color = p.InkMid, fontSize = 10.sp)
            )
            val bubbleW = max(t1.size.width, t2.size.width) + 20.dp.toPx()
            val bubbleH = t1.size.height + t2.size.height + 13.dp.toPx()
            val bx = (px - bubbleW / 2f).coerceIn(padLeft, (w - padRight - bubbleW).coerceAtLeast(padLeft))
            val by = (if (py - bubbleH - 12.dp.toPx() >= 2.dp.toPx()) {
                py - bubbleH - 12.dp.toPx()
            } else {
                py + 12.dp.toPx()
            }).coerceIn(2.dp.toPx(), (h - bubbleH - padBottom).coerceAtLeast(2.dp.toPx()))

            val bubbleTopLeft = Offset(bx, by)
            val bubbleSize = Size(bubbleW, bubbleH)
            val bubbleCorner = CornerRadius(10.dp.toPx())
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(p.InkHi.copy(alpha = 0.16f * reveal), p.InkHi.copy(alpha = 0.10f * reveal)),
                    startY = by,
                    endY = by + bubbleH
                ),
                topLeft = bubbleTopLeft,
                size = bubbleSize,
                cornerRadius = bubbleCorner
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.5f * reveal),
                topLeft = bubbleTopLeft,
                size = bubbleSize,
                cornerRadius = bubbleCorner,
                style = Stroke(width = 1f.dp.toPx())
            )
            drawText(t1, topLeft = Offset(bx + 10.dp.toPx(), by + 7.dp.toPx()))
            drawText(t2, topLeft = Offset(bx + 10.dp.toPx(), by + 7.dp.toPx() + t1.size.height))
        }
    }
}
