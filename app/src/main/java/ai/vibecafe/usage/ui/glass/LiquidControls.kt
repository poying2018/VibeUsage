package ai.vibecafe.usage.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 移植自 kyant AndroidLiquidGlass catalog 的两个交互组件：
 * LiquidSlider（胶囊轨道 + 液态玻璃滑块）与 LiquidButton（液态玻璃胶囊按钮）。
 * 与 catalog 的差异：
 * - Capsule() 形状用 RoundedCornerShape(percent = 50) 等效替代（项目未引入 shapes 库）；
 * - 配色取自 LocalGlassPalette；
 * - LiquidButton 增加 enabled 与 surfaceBrush（渐变表面）参数。
 */

@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val palette = LocalGlassPalette.current
    val accentColor = palette.Accent
    val trackColor = palette.InkLo.copy(alpha = 0.25f)

    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = value(),
            valueRange = valueRange,
            visibilityThreshold = visibilityThreshold,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> }
        )
    }

    // 手势闭包里要用的 onValueChange 必须始终是**最新**的那一个：
    // pointerInput 的 key 恒定（valueRange/isLtr 不变），手势协程只在首次组合时启动，
    // 若直接捕获参数里的 onValueChange，它会冻结在首次组合的快照上——
    // 而调用方（设置页）传进来的 lambda 捕获的是当时的 style 值副本，
    // 结果是「调第二个滑杆会把第一个静默写回旧值」。
    // 用 rememberUpdatedState 持有最新引用，闭包里每次读到的都是当前值。
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val trackBackdrop = rememberLayerBackdrop()

    // 拇指玻璃的 backdrop 取样 lambda 必须在**重组之间保持同一个实例**：
    // rememberBackdrop 的 key 是 (backdrop, onDraw)，lambda 每次重组都是新对象，
    // 就会重建 combined backdrop → draw cache 失效 → 拖动滑杆时每帧重跑
    // blur + lens（设置页 5 个滑杆 × 每帧）。
    // 这里把按压进度做成 State，lambda 只捕获它、在 **draw 阶段**读取，
    // lambda 本身用 remember 固定住，拖动时只重绘不重建。
    val thumbProgress = remember(dampedDragAnimation) {
        derivedStateOf { dampedDragAnimation.pressProgress }
    }
    val thumbBackdropOnDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit =
        remember(trackBackdrop) {
            { drawBackdrop ->
                val progress = thumbProgress.value
                val scaleX = lerp(2f / 3f, 1f, progress)
                val scaleY = lerp(0f, 1f, progress)
                scale(scaleX, scaleY) {
                    drawBackdrop()
                }
            }
        }
    val thumbBackdrop = rememberBackdrop(trackBackdrop, thumbBackdropOnDraw)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32f.dp)
            .pointerInput(valueRange, isLtr) {
                // 整条轨道区域可按可拖（M3 滑杆交互）：
                // 按下立即播放玻璃按压动效；横向意图（横向位移先越过 touchSlop）
                // 才接管手势，值直接取指针横向落点并跟随；没走出 slop 的轻点则跳到落点。
                // 竖直意图必须放行给父级滚动——否则滑杆会吞掉页面纵向滑动。
                val slop = viewConfiguration.touchSlop
                fun emit(x: Float) {
                    val fraction = if (isLtr) {
                        (x / size.width).coerceIn(0f, 1f)
                    } else {
                        1f - (x / size.width).coerceIn(0f, 1f)
                    }
                    currentOnValueChange(
                        valueRange.start + (valueRange.endInclusive - valueRange.start) * fraction
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dampedDragAnimation.press()
                    var claimed = false
                    var acc = Offset.Zero
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                // 点按（没走出 slop）：M3 滑杆是「点哪到哪」，
                                // 此前轻点没有任何反应，只能拖着走
                                if (!claimed) emit(change.position.x)
                                break
                            }
                            if (!claimed) {
                                acc += change.positionChangeIgnoreConsumed()
                                val dx = abs(acc.x)
                                val dy = abs(acc.y)
                                if (dx >= slop && dx > dy) {
                                    claimed = true
                                } else if (dy >= slop && dy > dx) {
                                    break
                                }
                            }
                            if (claimed) {
                                change.consume()
                                emit(change.position.x)
                            }
                        }
                    } finally {
                        dampedDragAnimation.release()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth

        // 滑块视觉直接由 value 参数驱动（值变化 → 重组 → spring 动画跟随）。
        // 不走 DampedDragAnimation 的值通道：snapshotFlow 捕获的是旧组合的
        // value lambda，参数更新后不会重新求值，导致"值变了滑块不动"。
        val targetProgress = run {
            val v = value().coerceIn(valueRange.start, valueRange.endInclusive)
            (v - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        }
        val animatedProgress by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = 1000f,
                visibilityThreshold = visibilityThreshold
            ),
            label = "liquidSliderProgress"
        )

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(trackColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * animatedProgress).fastRoundToInt()
                        layout(width, placeable.height) {
                            // RTL 下填充条要从**右端**向左生长，与拇指的移动方向一致；
                            // 否则填充条永远从左生长，与拇指背向而行，读数完全错位。
                            if (isLtr) placeable.place(0, 0) else placeable.place(constraints.maxWidth - width, 0)
                        }
                    }
            )
        }

        Box(
            Modifier
                // 布局级 offset 定位（graphicsLayer.translationX 不参与命中测试）；
                // 手势统一由外层整条轨道区域处理，滑块本体只负责视觉。
                .offset {
                    val w = 40f.dp.toPx()
                    val tx = (-w / 2f + trackWidth * animatedProgress)
                        .fastCoerceIn(-w / 4f, trackWidth - w * 3f / 4f)
                    IntOffset((if (isLtr) tx else -tx).fastRoundToInt(), 0)
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, thumbBackdrop),
                    shape = { RoundedCornerShape(percent = 50) },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        // 按压越深折射越强；**静止时也保留一个基础折射量**：
                        // 早先静止直接跳过 lens（省着色器开销），结果拇指在常态下
                        // 是一块平的半透明胶囊，没有液态玻璃该有的边缘弯折。
                        // 视觉优先：给静止态 35% 的折射带宽/强度，按下时叠加到满。
                        val lensScale = 0.35f + 0.65f * progress
                        lens(
                            10f.dp.toPx() * lensScale,
                            14f.dp.toPx() * lensScale,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        // 静止态也留一点边缘高光：纯 0 时拇指没有玻璃轮廓，
                        // 和周围卡片不在同一个材质语言里。按下时再压到满强度。
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = 0.28f + 0.72f * progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    surfaceBrush: Brush? = null,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope
        )
    }
    val interactive = isInteractive && enabled

    Row(
        modifier
            // 禁用态必须有视觉反馈：此前只拦点击、外观不变，空表单里的「接入」
            // 看起来和可点的一样亮，点了没反应像卡死
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(percent = 50) },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = if (interactive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceBrush != null) {
                        drawRect(surfaceBrush)
                    } else if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                interactionSource = null,
                indication = if (interactive) null else LocalIndication.current,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick
            )
            .then(
                if (interactive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(48f.dp)
            .padding(horizontal = 16f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
