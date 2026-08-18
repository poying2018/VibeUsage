package ai.vibecafe.usage.ui.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * 入场动画：淡入 + 上滑。
 * 列表/卡片按 index 传入递增 [delayMs] 即可形成逐条浮现的节奏。
 */
@Composable
fun Modifier.fadeSlideIn(delayMs: Int = 0): Modifier {
    val transition = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        transition.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val v = transition.value
        alpha = v
        translationY = (1f - v) * 22f
    }
}

/**
 * 数字滚动：值变化时从旧值平滑滚动到新值。
 * [format] 接收当前动画值（Float），用于按需格式化金额 / token 数。
 */
@Composable
fun AnimatedCounter(
    value: Float,
    format: (Float) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    animationSpec: AnimationSpec<Float> = tween(900, easing = FastOutSlowInEasing)
) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(value) {
        animated.animateTo(value, animationSpec)
    }
    Text(
        text = format(animated.value),
        style = style,
        color = color,
        modifier = modifier
    )
}
