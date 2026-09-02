package ai.vibecafe.usage.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import ai.vibecafe.usage.ui.theme.isDark
import androidx.compose.material3.Text
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private val Pill = RoundedCornerShape(percent = 50)
private val EaseOutQuint = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 液态玻璃分段控件 —— 已统一为酷安底部导航栏同款配方
 * （Kyant0/AndroidLiquidGlass Catalog · LiquidBottomTabs）：
 *
 *  - 胶囊玻璃壳：vibrancy + blur(8) + lens(24,24)，按压整体微膨胀（+16dp/宽度）；
 *  - 隐藏的 Accent 色内容层（alpha=0，tint 主题色）写入独立 backdrop，
 *    滑块盖住的分段文字以 Accent 色从玻璃中折射透出（酷安图标发光弯曲同源）；
 *  - 滑块为「纯折射」玻璃：无磨砂不挡文字，按压时折射强度拉满 + 色散
 *    （chromaticAberration）+ 投影 + 内阴影，速度产生液体拉伸惯性；
 *  - 拖拽越出两端时整条壳 4dp EaseOut 位移回弹，松手 Q 弹吸附最近分段。
 *
 *  保留的原有行为：
 *  - selectedIndex 越界（-1 = 自定义范围档）时滑块淡出隐藏；
 *  - 点击任意分段直接跳转（Q 弹位移）；按住滑块可拖拽刮擦；
 *  - 分段文案按段宽自动缩字（0.72~1.0）。
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
    height: Dp = 64.dp
) {
    val p = LocalGlassPalette.current
    val count = items.size
    if (count == 0) return
    // 跟随应用内主题（见 LiquidBottomTabs 注释）
    val isLightTheme = !p.isDark
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = p.Accent
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / count
        }

        // 测量每段文案真实宽度（按段宽自动缩字，保证文字不挤不溢出）
        val textMeasurer = rememberTextMeasurer()
        val textWidthsPx = remember(items) {
            items.map { label ->
                textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = GlassText.SegmentActive
                ).size.width.toFloat()
            }
        }
        val fitScales = remember(textWidthsPx, tabWidth) {
            val minPad = with(density) { 6.dp.toPx() }
            textWidthsPx.map { w ->
                val avail = tabWidth - minPad
                if (w > avail && w > 0f) (avail / w).coerceIn(0.72f, 1f) else 1f
            }
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember {
            mutableIntStateOf(selectedIndex.coerceIn(0, count - 1))
        }

        // 滑块按压放大：酷安为 78/56（64dp 壳 + 14dp 隆起）；小尺寸控件按比例缩放并封顶
        val pressedScale = minOf(
            (height + 14.dp) / (height - 8.dp),
            78f / 56f
        )

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = pressedScale,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (count - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        // 外部选择同步（ViewModel 驱动）；-1（自定义档）只隐藏滑块，不动滑块位置
        LaunchedEffect(Unit) {
            snapshotFlow { selectedIndex }
                .collectLatest { idx ->
                    if (idx in 0 until count && idx != currentIndex) currentIndex = idx
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { idx ->
                    dampedDragAnimation.animateToValue(idx.toFloat())
                    onSelect(idx)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // ---------- 1. 胶囊玻璃壳（同底部导航栏：vibrancy + blur8 + lens24）----------
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Pill },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .fillMaxSize()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab
                        ) { currentIndex = index },
                    contentAlignment = Alignment.Center
                ) {
                    val fit = fitScales[index]
                    Text(
                        text = label,
                        style = GlassText.SegmentActive.copy(
                            fontSize = GlassText.SegmentActive.fontSize * fit
                        ),
                        color = contentColor,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            val s = lerp(1f, 1.15f, dampedDragAnimation.pressProgress)
                            scaleX = s
                            scaleY = s
                        }
                    )
                }
            }
        }

        // ---------- 2. 隐藏的 Accent 内容层（写入 tabsBackdrop 供滑块折射）----------
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Pill },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(
                            24f.dp.toPx() * progress,
                            24f.dp.toPx() * progress
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(height - 8.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer { colorFilter = ColorFilter.tint(accentColor) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    val fit = fitScales[index]
                    Text(
                        text = label,
                        style = GlassText.SegmentActive.copy(
                            fontSize = GlassText.SegmentActive.fontSize * fit
                        ),
                        color = contentColor,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ---------- 3. 滑块（纯折射玻璃 + 按压色散，同底部导航栏）----------
        // selectedIndex 越界（-1 = 自定义范围档）时滑块淡出隐藏
        val hasSelection = selectedIndex in 0 until count
        val pillAlpha by animateFloatAsState(
            targetValue = if (hasSelection) 1f else 0f,
            animationSpec = tween(220, easing = EaseOutQuint),
            label = "pillAlpha"
        )
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    alpha = pillAlpha
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Pill },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // 静止时 progress=0，lens(0,0) 为恒等变换 —— 跳过整条 RenderEffect
                        if (progress > 0f) lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    shadow = {
                        Shadow(alpha = dampedDragAnimation.pressProgress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        // 拖拽速度带来的液体拉伸惯性
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(height - 8.dp)
                .fillMaxWidth(1f / count)
        )
    }
}
