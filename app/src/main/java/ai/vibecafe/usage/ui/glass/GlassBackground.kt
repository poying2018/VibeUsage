package ai.vibecafe.usage.ui.glass

import ai.vibecafe.usage.ui.theme.Glass
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File

/** 页面背景 backdrop：所有玻璃层都从这一层取样。 */
@Composable
fun rememberPageBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 流动的彩色背景 —— 对应 Web 原型的 `.stage`。
 * 玻璃需要「有东西可折射」，纯色背景会让液态玻璃完全看不出效果，所以这层必须存在。
 *
 * [imagePath] 非空时，用该图片做 cover 铺底（用户自定义背景），并在其上方叠加一层
 * 半透明白纱 + 边缘提亮晕罩，保证文字可读、玻璃仍有可折射的内容。
 */
@Composable
fun GlassBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    imagePath: String? = null
) {
    val imageBitmap = rememberDecodedImage(imagePath)
    val transition = rememberInfiniteTransition(label = "orbs")

    val p1 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb1"
    )
    val p2 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(16_000, easing = LinearEasing), RepeatMode.Reverse,
            StartOffset(7_000, StartOffsetType.FastForward)
        ),
        label = "orb2"
    )
    val p3 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(16_000, easing = LinearEasing), RepeatMode.Reverse,
            StartOffset(12_000, StartOffsetType.FastForward)
        ),
        label = "orb3"
    )
    val p4 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(16_000, easing = LinearEasing), RepeatMode.Reverse,
            StartOffset(3_000, StartOffsetType.FastForward)
        ),
        label = "orb4"
    )

    Box(modifier.fillMaxSize().layerBackdrop(backdrop)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (imageBitmap != null) {
                // 自定义背景：图片铺底 + 半透明白纱保证对比度，玻璃折射更明显
                drawImageCover(imageBitmap)
                drawRect(Color.White.copy(alpha = 0.30f))
                // 边缘提亮晕罩（比默认弱，避免压掉照片）
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.30f to Color.Transparent,
                            1f to Color.White.copy(alpha = 0.32f)
                        ),
                        center = Offset(w * 0.5f, h * 0.35f),
                        radius = maxOf(w, h) * 0.78f
                    )
                )
            } else {
                drawRect(Glass.Page)

                // 四层径向色雾
                softRadial(Glass.WashPink, Offset(w * 0.80f, h * -0.12f), maxOf(w, h) * 0.90f, 0.44f)
                softRadial(Glass.WashViolet, Offset(w * 0.10f, h * 0.06f), maxOf(w, h) * 0.85f, 0.42f)
                softRadial(Glass.WashMint, Offset(w * 0.55f, h * 1.15f), maxOf(w, h) * 0.90f, 0.44f)
                softRadial(Glass.WashCream, Offset(w * 0.18f, h * 0.92f), maxOf(w, h) * 0.68f, 0.40f)

                // 漂浮光斑（multiply 混合，模拟 CSS 的 mix-blend-mode:multiply + blur(60px)）
                orb(Glass.OrbPink, Offset(w * 0.15f, h * -0.02f), w * 0.48f, p1)
                orb(Glass.OrbBlue, Offset(w * 0.92f, h * 0.22f), w * 0.42f, p2)
                orb(Glass.OrbTeal, Offset(w * 0.44f, h * 0.94f), w * 0.40f, p3)
                orb(Glass.OrbAmber, Offset(w * 0.72f, h * 0.86f), w * 0.34f, p4)

                // .stage::after —— 中心透明、四周提亮的白色晕罩
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.26f to Color.Transparent,
                            1f to Color.White.copy(alpha = 0.5f)
                        ),
                        center = Offset(w * 0.5f, h * 0.35f),
                        radius = maxOf(w, h) * 0.78f
                    )
                )
            }
        }
    }
}

/**
 * 解码自定义背景图并缓存为 ImageBitmap。
 * 在 IO 线程按 inSampleSize 降采样（最长边 ≤ 1280px），避免大图占用过多内存。
 */
@Composable
private fun rememberDecodedImage(path: String?): ImageBitmap? {
    return produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeImage(path) }
    }.value
}

private fun decodeImage(path: String?): ImageBitmap? {
    if (path == null) return null
    val file = File(path)
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val bigger = maxOf(bounds.outWidth, bounds.outHeight)
        while (bigger / (sample * 2) >= 1280) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/** 以 cover 方式把图片铺满画布（等比缩放、居中裁剪）。 */
private fun DrawScope.drawImageCover(bitmap: ImageBitmap) {
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    if (imgW <= 0f || imgH <= 0f) return
    val scale = maxOf(size.width / imgW, size.height / imgH)
    val dw = (imgW * scale).roundToInt()
    val dh = (imgH * scale).roundToInt()
    val dx = ((size.width - dw) / 2f).roundToInt()
    val dy = ((size.height - dh) / 2f).roundToInt()
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(dx, dy),
        dstSize = IntSize(dw, dh)
    )
}

/** 大范围柔和色雾：中心实色，[fadeStop] 之后渐隐。 */
private fun DrawScope.softRadial(
    color: Color,
    center: Offset,
    radius: Float,
    fadeStop: Float
) {
    if (radius <= 0f) return
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color,
                fadeStop to color.copy(alpha = 0.55f),
                1f to Color.Transparent
            ),
            center = center,
            radius = radius
        )
    )
}

/**
 * 单个漂浮光斑。[progress] 0→1 对应 CSS `drift` 关键帧：
 * translate3d(5vw, 6vh) + scale(1 → 1.22)。
 */
private fun DrawScope.orb(
    color: Color,
    center: Offset,
    diameter: Float,
    progress: Float
) {
    val w = size.width
    val h = size.height
    val scale = 1f + 0.22f * progress
    val r = diameter / 2f * scale
    if (r <= 0f) return
    val c = Offset(center.x + w * 0.05f * progress, center.y + h * 0.06f * progress)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = 0.55f),
                0.45f to color.copy(alpha = 0.42f),
                1f to Color.Transparent
            ),
            center = c,
            radius = r
        ),
        radius = r,
        center = c,
        blendMode = BlendMode.Multiply
    )
}

/** 便捷：把整块背景铺满并作为内容容器。 */
@Composable
fun GlassScaffold(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    imagePath: String? = null,
    content: @Composable () -> Unit
) {
    Box(modifier.fillMaxSize()) {
        GlassBackground(backdrop, imagePath = imagePath)
        content()
    }
}
