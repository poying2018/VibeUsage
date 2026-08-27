package ai.vibecafe.usage.ui.glass

import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlin.math.floor
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.material3.Text
import kotlin.math.abs

private val Pill = RoundedCornerShape(percent = 50)
private val EaseOutQuint = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 液态玻璃分段控件 —— Web 原型 `.range-shell` 的 1:1 Compose 实现。
 *
 * 视觉：
 *  - 选择条 58dp 高胶囊，rgba(255,255,255,.5) + blur(18) + saturate(150%)，内描边 1px 白 70%。
 *  - 滑块与选择条上下齐平；吸附到最左/最右段时外侧缘贴紧选择条边框（不留缝），中间段按文案宽度量体裁衣并居中。
 *  - 滑块为「纯玻璃」：只有 1px 白描边 + 边缘折射，不加任何白底/高光（全透）。
 *
 * 交互（与 Web 版逐条对齐）：
 *  - 只有按在滑块内部才会触发按压放大（1 → 1.18）与拖拽跟随；
 *  - 按下瞬间折射强度拉满（对应 feDisplacementMap scale 6 → 15），实时把选择条边界折射成光线；
 *  - 点击其它分段只做 Q 弹位移，不放大；
 *  - 拖拽松手吸附到最近分段。
 *
 * @param backdrop 页面背景 backdrop，玻璃层从这里取样
 */
@Composable
fun LiquidGlassSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    height: Dp = 58.dp
) {
    val p = LocalGlassPalette.current
    val count = items.size
    if (count == 0) return

    // 选择条本体导出成一层 backdrop，让滑块把它一并折射进来 ——
    // 这样滑块是「盖在选项上、清楚包住该选项字体」的玻璃块，而不是掏空成背景的洞
    val shellBackdrop = rememberLayerBackdrop()
    // 文案烘焙进一层 backdrop，滑块才能采样到文字并在滑动时折射它（液态玻璃精髓）
    val textBackdrop = rememberLayerBackdrop()
    // 页面背景 + 选择条本体 + 文字，三者一并折射
    val pillBackdrop = rememberCombinedBackdrop(backdrop, shellBackdrop, textBackdrop)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val trackWidthPx = constraints.maxWidth.toFloat()
        val segmentWidthPx = trackWidthPx / count
        val segmentWidth = with(density) { segmentWidthPx.toDp() }

        // 测量每段文案真实宽度，供滑块「量体裁衣」：吸附到哪段就抱住那段文字的大小
        val textMeasurer = rememberTextMeasurer()
        // 文字与玻璃折射带之间的最小安全距离（内边距）。
        // 放大到 22dp：边缘透镜折射最深约 14dp（按压态），22dp 能稳稳把字形挡在折射带之外，
        // 不会再出现「吸附后边缘折射扫到字」。
        // 关键：不再按「单段宽度 − 间隙」去 clamp 滑块宽度——否则宽标签（如「24小时」）的
        // pill 会被压窄、文字贴边被折射扫到，且在段宽不同的设备上观感漂移。现在宽度 = 文字宽 + 2*pad，
        // 必要时刻意允许比单段更宽（最多到整条宽），跨设备观感一致。
        val pillPadPx = with(density) { 22.dp.toPx() }
        val pillMinPx = with(density) { 56.dp.toPx() }
        val textWidthsPx = remember(items) {
            items.map { label ->
                textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = GlassText.SegmentActive
                ).size.width.toFloat()
            }
        }
        // 7 档后单段变窄（如「24小时」四字），按段宽自动缩小字号，保证文字不挤不溢出
        val fitScales = remember(items, segmentWidthPx) {
            val minPad = with(density) { 6.dp.toPx() }
            items.map { label ->
                val w = textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = GlassText.SegmentActive
                ).size.width.toFloat()
                val avail = segmentWidthPx - minPad
                if (w > avail && w > 0f) (avail / w).coerceIn(0.72f, 1f) else 1f
            }
        }
        // 每个段「吸附到位」时滑块的目标【宽度】与【中心】：
        //  - 宽度 = 文字宽 + 2*pad，夹在 [pillMinPx, 整条宽*0.98] 之间（允许比单段更宽，跨段也不削内边距）。
        //  - 中间段：中心 = 段中心，文字居中、两侧留足 pad。
        //  - 最左段：左缘贴紧选择条左边框(x=0) ⇒ 中心 = 宽度/2。
        //  - 最右段：右缘贴紧选择条右边框(x=trackWidth) ⇒ 中心 = trackWidth − 宽度/2。
        // 效果：极端段滑块外缘与条边框严丝合缝；文字始终被 pad 护在折射带之外。
        // 极端段宽度直接取「段宽」：这样外缘既贴紧选择条边框，文字又恰好落在滑块正中
        // （pill 中心 = 段宽/2 = 段中心 = 文字中心），两侧内边距对称、文字始终居中。
        // 中间段仍按文字宽度量体裁衣（文字宽 + 2*pad）。
        val pillWidths = remember(items, pillPadPx, pillMinPx, segmentWidthPx, trackWidthPx, textWidthsPx) {
            FloatArray(count) { i ->
                when (i) {
                    0 -> segmentWidthPx
                    count - 1 -> segmentWidthPx
                    else -> (textWidthsPx[i] + 2f * pillPadPx).coerceIn(pillMinPx, trackWidthPx * 0.98f)
                }
            }
        }
        val pillCenters = remember(count, segmentWidthPx, pillWidths, trackWidthPx) {
            FloatArray(count) { i ->
                when (i) {
                    0 -> pillWidths[0] / 2f                       // 段宽/2 = 文字中心 → 贴左缘且文字居中
                    count - 1 -> trackWidthPx - pillWidths[count - 1] / 2f
                    else -> (i + 0.5f) * segmentWidthPx           // 中间段：中心=文字中心，文字居中
                }
            }
        }

        val drag = rememberGlassDragAnimation(
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(count - 1).toFloat(),
            pressedScale = 1.18f
        )

        // selectedIndex 越界（如 -1）表示「当前无选中段」（自定义范围档），滑块隐藏
        val hasSelection = selectedIndex in 0 until count
        val pillAlpha by animateFloatAsState(
            targetValue = if (hasSelection) 1f else 0f,
            animationSpec = tween(220, easing = EaseOutQuint),
            label = "pillAlpha"
        )

        // 外部（ViewModel）改变选中项时，滑块 Q 弹过去
        LaunchedEffect(selectedIndex, drag) {
            if (hasSelection && abs(drag.targetValue - selectedIndex) > 0.01f) {
                drag.springTo(selectedIndex.toFloat())
            }
        }

        // ---------- 1. 选择条玻璃本体 ----------
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Pill },
                    effects = {
                        vibrancy()
                        colorControls(saturation = 1.8f)   // 提饱和，背后内容颜色更醒目
                        blur(10f.dp.toPx())                // 模糊收窄，内容形态可辨
                    },
                    highlight = null,
                    shadow = {
                        Shadow(
                            radius = 32f.dp,
                            offset = androidx.compose.ui.unit.DpOffset(0f.dp, 12f.dp),
                            color = p.ShadowBar
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 6f.dp,
                            offset = androidx.compose.ui.unit.DpOffset(0f.dp, 2f.dp),
                            color = p.InnerTint
                        )
                    },
                    // 选择条导出成 shellBackdrop，供滑块一并折射（滑块「包住」选项字体）
                    exportedBackdrop = shellBackdrop,
                    onDrawSurface = {
                        drawRoundRect(
                            color = p.SelectorSurface,
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    },
                    // inset 0 0 0 1px rgba(255,255,255,.7)
                    onDrawFront = { drawInsetRim(p.Rim, 1f.dp.toPx()) }
                )
        )

        // ---------- 2. 文案层（先于滑块绘制，并烘焙进 textBackdrop 供滑块折射）----------
        // 必须是滑块的「下层」：内容写入 textBackdrop 后，顶层滑块才能采样到文字、
        // 在滑动时把文字折射成光线 —— 这才是液态玻璃的精髓（之前文字浮在滑块之上、
        // 且不在任何 backdrop 中，导致滑块既折射不到文字、又显得不透明）。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(textBackdrop)
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .pointerInput(count, segmentWidthPx, selectedIndex) {
                        val slop = 5f.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val pillLeft = drag.value * segmentWidthPx
                            // 只有按在滑块内部才允许按压 / 拖拽
                            val onPill = startX >= pillLeft && startX <= pillLeft + segmentWidthPx
                            val downIndex =
                                (startX / segmentWidthPx).toInt().fastCoerceIn(0, count - 1)
                            val startValue = drag.value
                            var totalDx = 0f
                            var moved = false

                            if (onPill) drag.press()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (change.changedToUpIgnoreConsumed()) {
                                    if (onPill) {
                                        drag.release()
                                        if (moved) {
                                            val target = drag.targetValue
                                                .fastRoundToInt()
                                                .fastCoerceIn(0, count - 1)
                                            drag.springTo(target.toFloat())
                                            if (target != selectedIndex) onSelect(target)
                                        }
                                    } else if (downIndex != selectedIndex) {
                                        drag.springTo(downIndex.toFloat())
                                        onSelect(downIndex)
                                    }
                                    break
                                }

                                if (onPill) {
                                    totalDx += change.positionChange().x
                                    if (!moved && abs(totalDx) > slop) moved = true
                                    if (moved) {
                                        change.consume()
                                        drag.updateValue(startValue + totalDx / segmentWidthPx)
                                    }
                                }
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, label ->
                    val active = index == selectedIndex
                    val color by animateColorAsState(
                        targetValue = if (active) p.InkStrong else p.InkMid,
                        animationSpec = tween(280, easing = EaseOutQuint),
                        label = "segmentColor"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (active) 1.06f else 1f,
                        animationSpec = tween(280, easing = EaseOutQuint),
                        label = "segmentScale"
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val fit = fitScales[index]
                        Text(
                            text = label,
                            style = (if (active) GlassText.SegmentActive else GlassText.SegmentIdle)
                                .copy(fontSize = GlassText.SegmentActive.fontSize * fit),
                            color = color,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                        )
                    }
                }
            }
        }

        // ---------- 3. 滑块（顶层玻璃，采样 textBackdrop 折射文字）----------
        // 滑块宽度随当前选项的文案宽度自动匹配：吸附到哪段就「抱住」那段文字的大小，
        // 滑动时宽度在相邻两段之间平滑过渡（iOS 分段控件同款手感）
        // 滑块位置/宽度在相邻两段目标之间平滑插值；并在两端【允许弹簧外溢】——
        // 吸附到最左/最右时不把位置钉死在条边框内，让 Q 弹过冲可以跑出选择条，
        // 落定时（f 恰为 0 / count-1）再精确与边框重合。
        val f = drag.value
        val pillCenterX: Float
        val pillWidthPx: Float
        when {
            // 左侧过冲：f<0 时向左外推，滑块左缘短暂跑出选择条左端
            f <= 0f -> {
                val t = f
                pillCenterX = lerp(pillCenters[0], pillCenters[1], t)
                pillWidthPx = lerp(pillWidths[0], pillWidths[1], t)
            }
            // 右侧过冲：f>count-1 时向右外推，滑块右缘短暂跑出选择条右端
            f >= (count - 1).toFloat() -> {
                val t = f - (count - 2)
                pillCenterX = lerp(pillCenters[count - 2], pillCenters[count - 1], t)
                pillWidthPx = lerp(pillWidths[count - 2], pillWidths[count - 1], t)
            }
            else -> {
                val i0 = floor(f).toInt().coerceIn(0, count - 2)
                val i1 = i0 + 1
                val t = f - i0
                pillCenterX = lerp(pillCenters[i0], pillCenters[i1], t)
                pillWidthPx = lerp(pillWidths[i0], pillWidths[i1], t)
            }
        }
        val pillLeftX = pillCenterX - pillWidthPx / 2f
        Box(
            Modifier
                .graphicsLayer {
                    translationX = pillLeftX
                    alpha = pillAlpha
                }
                .width(with(density) { pillWidthPx.toDp() })
                .fillMaxHeight()
                .drawBackdrop(
                    backdrop = pillBackdrop,
                    shape = { Pill },
                    effects = {
                        val p = drag.pressProgress
                        // saturate(150%) brightness(1.05)
                        colorControls(brightness = 0.05f, saturation = 1.5f)
                        // 关键：不做任何磨砂模糊 —— 模糊会把选项文字糊掉、挡住字体。
                        // 透镜折射只作用在边缘，中心文字保持清晰可辨认
                        lens(
                            refractionHeight = lerp(6f.dp.toPx(), 14f.dp.toPx(), p),
                            refractionAmount = lerp(8f.dp.toPx(), 18f.dp.toPx(), p),
                            depthEffect = false,
                            chromaticAberration = true
                        )
                    },
                    highlight = null,
                    shadow = {
                        Shadow(
                            radius = 24f.dp,
                            offset = androidx.compose.ui.unit.DpOffset(0f.dp, 8f.dp),
                            color = p.ShadowPill
                        )
                    },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        // 拖拽速度带来的轻微拉伸，让玻璃有「液体」惯性
                        val v = drag.velocity / 10f
                        scaleX /= 1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        // 全透：不再叠加任何白色底色，滑块只是一块「看得见折射、却无遮挡」
                        // 的清玻璃，选项字体毫无保留地透出
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0f),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    },
                    // border:1px —— 与选择条同一水平的描边（p.Rim），柔和不刺眼
                    onDrawFront = { drawInsetRim(p.Rim, 1f.dp.toPx()) }
                )
        )
    }
}

/** 画 1px 内描边（等价于 CSS 的 inset 0 0 0 1px / border:1px）。 */
private fun DrawScope.drawInsetRim(color: Color, stroke: Float) {
    val half = stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((size.height - stroke) / 2f),
        style = Stroke(stroke)
    )
}
