package ai.vibecafe.usage.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 液态玻璃滑块的阻尼拖拽 / 按压动画控制器。
 *
 * 改编自 Kyant0/AndroidLiquidGlass catalog 里的 DampedDragAnimation（该类只存在于示例工程，
 * 未随 io.github.kyant0:backdrop 的 AAR 发布，因此在本项目内重新实现）。
 *
 * 与上游的差异（对齐 Web 原型 index.html 的最终交互）：
 * 1. 新增 [springTo]：点击其它分段时只做 Q 弹位移，**不**触发按压放大；
 *    上游的 animateToValue 会顺带 press()/release()，那是官方 bottom tabs 的风格。
 * 2. [release] 立即回弹，不等位移动画落位（Web 版 pointerup 时 .pressing 立刻移除）。
 * 3. 拖拽手势由调用方自己实现（需要「只有按在当前滑块上才能拖」的命中判定）。
 */
@Stable
class GlassDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    private val visibilityThreshold: Float = 0.001f,
    private val initialScale: Float = 1f,
    private val pressedScale: Float = 1.18f
) {

    /** 跟手位移：临界阻尼，拖拽时不过冲。 */
    private val followSpec = spring(1f, 1000f, visibilityThreshold)

    /** 吸附 / 点击位移：对应 CSS cubic-bezier(.34,1.56,.64,1)，约 8% 过冲。 */
    private val snapSpec = spring(0.62f, 400f, visibilityThreshold)

    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    /** 当前位置（单位：分段索引，可为小数）。 */
    val value: Float get() = valueAnimation.value

    /** 动画终点。 */
    val targetValue: Float get() = valueAnimation.targetValue

    /** 0 = 静止，1 = 完全按下。折射强度 / 高光 / 阴影都跟它插值。 */
    val pressProgress: Float get() = pressProgressAnimation.value

    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value

    /** 归一化速度，用于拖拽时的拉伸形变。 */
    val velocity: Float get() = velocityAnimation.value

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYSpec) }
            launch { velocityAnimation.animateTo(0f, velocitySpec) }
        }
    }

    /** 拖拽跟手。 */
    fun updateValue(target: Float) {
        val clamped = target.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.animateTo(clamped, followSpec) { trackVelocity() }
        }
    }

    /** 吸附 / 点击切换：Q 弹位移，不改变按压状态。 */
    fun springTo(target: Float) {
        val clamped = target.coerceIn(valueRange)
        animationScope.launch {
            mutatorMutex.mutate {
                valueAnimation.animateTo(clamped, snapSpec)
            }
        }
    }

    /** 尺寸变化时无动画重定位。 */
    suspend fun snapTo(target: Float) {
        valueAnimation.snapTo(target.coerceIn(valueRange))
    }

    private fun trackVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val span = valueRange.endInclusive - valueRange.start
        if (span <= 0f) return
        val normalized = velocityTracker.calculateVelocity().x / span
        animationScope.launch { velocityAnimation.animateTo(normalized, velocitySpec) }
    }
}

@Composable
fun rememberGlassDragAnimation(
    initialValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    pressedScale: Float = 1.18f
): GlassDragAnimation {
    val scope = rememberCoroutineScope()
    return remember(scope, valueRange, pressedScale) {
        GlassDragAnimation(
            animationScope = scope,
            initialValue = initialValue,
            valueRange = valueRange,
            pressedScale = pressedScale
        )
    }
}
