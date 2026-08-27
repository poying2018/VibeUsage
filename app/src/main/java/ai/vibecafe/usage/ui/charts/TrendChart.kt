package ai.vibecafe.usage.ui.charts

import ai.vibecafe.usage.stats.DailyUsage
import ai.vibecafe.usage.ui.formatCost
import ai.vibecafe.usage.ui.formatTokens
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.pow
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

/** 缓出曲线：波浪展开的推进节奏。 */
private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

/** 回弹曲线：数据点落位时轻微过冲再收回，产生"弹性落点"手感。 */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
}

/** 缓存的所有静态文字布局（组合期测量一次，draw 阶段零测量成本）。 */
private class TrendTexts(
    val axisMaxLabel: androidx.compose.ui.text.TextLayoutResult,
    val subLabels: List<androidx.compose.ui.text.TextLayoutResult>,
    val subFractions: List<Float>,
    val dateFirst: androidx.compose.ui.text.TextLayoutResult?,
    val dateLast: androidx.compose.ui.text.TextLayoutResult?,
    val dateMid: androidx.compose.ui.text.TextLayoutResult?,
    val avgLabel: androidx.compose.ui.text.TextLayoutResult,
    val peakLabel: androidx.compose.ui.text.TextLayoutResult?
)

/**
 * 用量趋势面积图：平滑曲线 + 渐变填充。
 * 动效：入场为逐点波浪展开（缓出推进 + 数据点回弹落位），指标切换为序列插值变形；
 * 所有静态文字在组合期测量缓存，draw 阶段零测量，保证满帧流畅。
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
            morph.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        prevValues = animValues
    }
    val fromValues = if (prevValues.size == animValues.size) prevValues else animValues

    // 入场波浪：数据变化（换档/刷新）时整条曲线从基线逐点生长
    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(950, easing = LinearEasing))
    }

    // 选中点索引，-1 表示未选中；数据或指标切换后复位
    var selected by remember(data, metric) { mutableIntStateOf(-1) }

    // 轴与统计值只依赖目标序列（不随动画中间态抖动）
    val axisMax = remember(animValues) { niceAxisMax(animValues.maxOrNull() ?: 0.001) }
    val avgV = remember(animValues) { if (animValues.isEmpty()) 0.0 else animValues.sum() / animValues.size }
    val peakIndex = remember(animValues) { animValues.indices.maxByOrNull { animValues[it] } ?: -1 }

    // ---- 组合期缓存全部静态文字布局（draw 阶段零测量）----
    val texts = remember(data, metric, p, axisMax, avgV, peakIndex) {
        if (data.isEmpty()) null
        else run {
            val n = data.size
            val axisStyle = GlassText.ChartAxis.copy(color = p.InkMid, fontSize = 10.sp)
            val subStyle = GlassText.ChartAxis.copy(
                color = p.InkMid.copy(alpha = p.InkMid.alpha * 0.55f),
                fontSize = 9.sp
            )
            val avgStyle = GlassText.ChartAxis.copy(color = secondary.copy(alpha = 0.85f), fontSize = 9.sp)
            val peakStyle = GlassText.ChartAxis.copy(color = secondary, fontSize = 9.sp)
            val fractions = listOf(3f / 4f, 1f / 2f, 1f / 4f)
            val midIndex = n / 2
            fun date(i: Int) = textMeasurer.measure(AnnotatedString(data[i].date), style = axisStyle)
            TrendTexts(
                axisMaxLabel = textMeasurer.measure(AnnotatedString(axisLabel(axisMax, metric)), style = axisStyle),
                subLabels = fractions.map { f ->
                    textMeasurer.measure(AnnotatedString(axisLabel(axisMax * f, metric)), style = subStyle)
                },
                subFractions = fractions,
                dateFirst = if (n > 1) date(0) else null,
                dateLast = if (n > 1) date(n - 1) else null,
                dateMid = if (n >= 5) date(midIndex) else null,
                avgLabel = textMeasurer.measure(AnnotatedString("均 " + axisLabel(avgV, metric)), style = avgStyle),
                peakLabel = if (peakIndex >= 0) {
                    textMeasurer.measure(AnnotatedString("峰 " + axisLabel(animValues[peakIndex], metric)), style = peakStyle)
                } else null
            )
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(data, metric) {
                // 画布内边距与 draw 阶段保持一致：左右各 4dp；再点同一点取消选中
                fun indexAt(x: Float): Int {
                    if (data.isEmpty()) return -1
                    val pad = 4.dp.toPx()
                    val plotW = (size.width - pad * 2).coerceAtLeast(1f)
                    return (((x - pad) / plotW) * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
                }
                detectTapGestures { offset ->
                    val idx = indexAt(offset.x)
                    selected = if (idx == selected) -1 else idx
                }
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
        if (n == 0 || texts == null) return@Canvas

        // ---- 动画中间态数值 ----
        // 1) 指标变形：上一序列 -> 当前序列
        val m = morph.value
        val morphed = if (m >= 1f) animValues else List(n) { i ->
            val a = fromValues.getOrElse(i) { 0.0 }
            a + (animValues[i] - a) * m
        }
        // 2) 入场波浪：头部占 55% 时间线，其余 45% 逐点错开，形成从左到右的涌动
        val T = progress.value
        val waveHead = 0.55f
        val waveSpan = if (n > 1) 0.45f / (n - 1) else 0f
        fun waveP(i: Int): Float = ((T - i * waveSpan) / waveHead).coerceIn(0f, 1f)
        fun valueOf(i: Int): Double = morphed[i] * easeOutCubic(waveP(i))

        fun xOf(i: Int): Float = padLeft + if (n == 1) plotW / 2f else plotW * i / (n - 1).toFloat()
        fun yOf(v: Double): Float = padTop + plotH * (1f - (v / axisMax).toFloat().coerceIn(0f, 1f))

        // ---- 横向刻度虚线（0 / 1/4 / 1/2 / 3/4 / 满刻度）----
        val gridAlpha = (T * 2.5f).coerceIn(0f, 1f)
        val gridColor = p.InkLo.copy(alpha = p.InkLo.alpha * 0.8f * gridAlpha)
        for (f in 0..4) {
            val y = padTop + plotH * (1f - f / 4f)
            var x = padLeft
            while (x < w - padRight) {
                val seg = minOf(6.dp.toPx(), w - padRight - x)
                drawLine(gridColor, Offset(x, y), Offset(x + seg, y), strokeWidth = 1f.dp.toPx())
                x += 12.dp.toPx()
            }
        }

        // ---- 面积路径（平滑二次贝塞尔，逐点波浪生长）----
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

        // ---- 数据点（点少才画）：随波浪经过逐点回弹落位 ----
        if (n <= 12) {
            for (i in 0 until n) {
                val wp = waveP(i)
                if (wp <= 0f) continue
                val pop = easeOutBack(wp)
                val c = Offset(xOf(i), yOf(morphed[i]))
                drawCircle(
                    Color.White.copy(alpha = 0.95f * wp),
                    radius = 3.6.dp.toPx() * (0.6f + 0.4f * pop),
                    center = c
                )
                drawCircle(
                    accent.copy(alpha = 0.9f * wp),
                    radius = 3.6.dp.toPx() * (0.6f + 0.4f * pop),
                    center = c,
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // ---- 均值参考虚线（波浪过半后淡入）----
        val lateAlpha = ((T - 0.55f) / 0.35f).coerceIn(0f, 1f)
        if (n >= 2 && lateAlpha > 0f) {
            val avgY = yOf(avgV)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
            drawLine(
                secondary.copy(alpha = 0.45f * lateAlpha),
                Offset(padLeft, avgY),
                Offset(w - padRight, avgY),
                strokeWidth = 1.2f.dp.toPx(),
                pathEffect = dash
            )
            val labelY = if (avgY - texts.avgLabel.size.height - 3.dp.toPx() > padTop) {
                avgY - texts.avgLabel.size.height - 3.dp.toPx()
            } else {
                avgY + 4.dp.toPx()
            }
            drawText(texts.avgLabel, topLeft = Offset(padLeft, labelY), alpha = lateAlpha)
        }

        // ---- 轴标签（缓存布局 + 透明度）----
        val labelAlpha = ((T - 0.1f) / 0.35f).coerceIn(0f, 1f)
        if (labelAlpha > 0f) {
            drawText(texts.axisMaxLabel, topLeft = Offset(padLeft, 2.dp.toPx()), alpha = labelAlpha)
            for ((idx, frac) in texts.subFractions.withIndex()) {
                val lab = texts.subLabels[idx]
                drawText(
                    lab,
                    topLeft = Offset(
                        w - padRight - lab.size.width,
                        padTop + plotH * (1f - frac) - lab.size.height - 2.dp.toPx()
                    ),
                    alpha = labelAlpha
                )
            }
            val t = texts
            if (t.dateFirst != null && t.dateLast != null) {
                val labelBottom = h - t.dateFirst.size.height - 1.dp.toPx()
                drawText(t.dateFirst, topLeft = Offset(padLeft, labelBottom), alpha = labelAlpha)
                drawText(
                    t.dateLast,
                    topLeft = Offset(w - padRight - t.dateLast.size.width, labelBottom),
                    alpha = labelAlpha
                )
                val mid = n / 2
                val midLab = t.dateMid
                if (midLab != null) {
                    val midX = xOf(mid) - midLab.size.width / 2f
                    if (midX > padLeft + t.dateFirst.size.width + 8.dp.toPx() &&
                        midX + midLab.size.width < w - padRight - t.dateLast.size.width - 8.dp.toPx()
                    ) {
                        drawText(midLab, topLeft = Offset(midX, labelBottom), alpha = labelAlpha)
                    }
                }
            }
        }

        // ---- 峰值标注（波浪结束后淡入；变形期间隐藏避免错位）----
        val peakAlpha = ((T - 0.85f) / 0.15f).coerceIn(0f, 1f) * m
        if (n >= 7 && peakIndex >= 0 && peakIndex != selected && peakAlpha > 0f && texts.peakLabel != null) {
            val px = xOf(peakIndex)
            val py = yOf(morphed[peakIndex])
            val d = 3.4.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(px, py - d); lineTo(px + d, py)
                    lineTo(px, py + d); lineTo(px - d, py); close()
                },
                secondary.copy(alpha = 0.95f * peakAlpha)
            )
            val lx = (px - texts.peakLabel.size.width / 2f)
                .coerceIn(padLeft, (w - padRight - texts.peakLabel.size.width).coerceAtLeast(padLeft))
            val ly = (py - texts.peakLabel.size.height - 9.dp.toPx()).coerceAtLeast(1.dp.toPx())
            drawText(texts.peakLabel, topLeft = Offset(lx, ly), alpha = peakAlpha)
        }

        // ---- 选中指示：参考线 + 高亮点 + 玻璃气泡（用户交互后出现，无入场耦合）----
        if (selected in 0 until n) {
            val i = selected
            val px = xOf(i)
            val py = yOf(valueOf(i))
            drawLine(
                p.InkHi.copy(alpha = 0.45f),
                Offset(px, padTop),
                Offset(px, baselineY),
                strokeWidth = 1.2f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
            drawCircle(Color.White.copy(alpha = 0.97f), radius = 4.6.dp.toPx(), center = Offset(px, py))
            drawCircle(
                accent.copy(alpha = 0.95f),
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
                    listOf(p.InkHi.copy(alpha = 0.16f), p.InkHi.copy(alpha = 0.10f)),
                    startY = by,
                    endY = by + bubbleH
                ),
                topLeft = bubbleTopLeft,
                size = bubbleSize,
                cornerRadius = bubbleCorner
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.5f),
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
