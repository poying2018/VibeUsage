package ai.vibecafe.usage.ui.glass

import ai.vibecafe.usage.render.GlassEngine
import android.annotation.SuppressLint
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
@SuppressLint("NewApi")
@Composable
fun GlassBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    imagePath: String? = null, auroraColorA: Color? = null, auroraColorB: Color? = null
) {
    val p = LocalGlassPalette.current
    val imageBitmap = rememberDecodedImage(imagePath)
    

    // ---- 自研渲染引擎（AGSL 极光层）：API 33+ 可用，绘制异常自动永久降级 ----
    val engineShader = remember { GlassEngine.newAuroraShader() }
    val engineFailed = remember { mutableStateOf(false) }
    // 普通动画时钟：极光只做时间流动，不绑定任何设备传感器
    val timeSec = produceState(0f) {
        var start = -1L
        while (true) {
            withFrameNanos { f ->
                if (start < 0) start = f
                value = (f - start) / 1_000_000_000f
            }
        }
    }
    val orbsTimeSec = timeSec

    // 手动映射循环插值：周期 T，往返
    fun orbBounce(phase: Float, period: Float): Float {
        val mod = (orbsTimeSec.value + phase) % (period * 2)
        return if (mod < period) mod / period else 2f - (mod / period)
    }

    val p1 = orbBounce(0f, 24f)
    val p2 = orbBounce(7f, 16f)
    val p3 = orbBounce(12f, 16f)
    val p4 = orbBounce(3f, 16f)

    Box(modifier.fillMaxSize().layerBackdrop(backdrop)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (imageBitmap != null) {
                // 自定义背景：图片铺底 + 半透明纱罩保证对比度，玻璃折射更明显
                drawImageCover(imageBitmap)
                drawRect(p.VeilImage)
                // 边缘晕罩（比默认弱，避免压掉照片）
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.30f to Color.Transparent,
                            1f to p.VeilEdge
                        ),
                        center = Offset(w * 0.5f, h * 0.35f),
                        radius = maxOf(w, h) * 0.78f
                    )
                )
            } else {
                drawRect(p.Page)

                // 四层径向色雾
                softRadial(p.WashPink, Offset(w * 0.80f, h * -0.12f), maxOf(w, h) * 0.90f, 0.44f)
                softRadial(p.WashViolet, Offset(w * 0.10f, h * 0.06f), maxOf(w, h) * 0.85f, 0.42f)
                softRadial(p.WashMint, Offset(w * 0.55f, h * 1.15f), maxOf(w, h) * 0.90f, 0.44f)
                softRadial(p.WashCream, Offset(w * 0.18f, h * 0.92f), maxOf(w, h) * 0.68f, 0.40f)

                // 漂浮光斑（亮色 multiply 混合 / 暗色 plus 自发光，模拟 CSS mix-blend-mode + blur）
                orb(p.OrbPink, Offset(w * 0.15f, h * -0.02f), w * 0.48f, p1, p.OrbBlend)
                orb(p.OrbBlue, Offset(w * 0.92f, h * 0.22f), w * 0.42f, p2, p.OrbBlend)
                orb(p.OrbTeal, Offset(w * 0.44f, h * 0.94f), w * 0.40f, p3, p.OrbBlend)
                orb(p.OrbAmber, Offset(w * 0.72f, h * 0.86f), w * 0.34f, p4, p.OrbBlend)

                // .stage::after —— 中心透明、四周渐变的晕罩（亮色提亮 / 暗色压暗）
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.26f to Color.Transparent,
                            1f to p.VeilEdge
                        ),
                        center = Offset(w * 0.5f, h * 0.35f),
                        radius = maxOf(w, h) * 0.78f
                    )
                )
            }

            // ---- 自研渲染引擎叠加层：程序化极光 + 光源镜面高光（无传感器时缓慢漂移）----
            val shader = engineShader
            if (shader != null && !engineFailed.value) {
                runCatching {
                    shader.setFloatUniform("uSize", w, h)
                    shader.setFloatUniform("uTime", timeSec.value)
                    // 自定义背景图上减弱极光，保持照片可读；暗色（自发光）比亮色稍强
                    val dark = p.OrbBlend == BlendMode.Plus
                    val strength = when {
                        imageBitmap != null -> 0.40f
                        dark -> 0.75f
                        else -> 0.45f
                    }
                    shader.setFloatUniform("uStrength", strength)
                    shader.setFloatUniform("uColorA",
                        (auroraColorA ?: p.OrbTeal).red, (auroraColorA ?: p.OrbTeal).green, (auroraColorA ?: p.OrbTeal).blue, 1f)
                    shader.setFloatUniform("uColorB",
                        (auroraColorB ?: p.OrbBlue).red, (auroraColorB ?: p.OrbBlue).green, (auroraColorB ?: p.OrbBlue).blue, 1f)
                    drawRect(
                        brush = androidx.compose.ui.graphics.ShaderBrush(shader),
                        blendMode = p.OrbBlend
                    )
                }.onFailure { engineFailed.value = true }
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
 * [blend] 亮色主题用 Multiply（叠色），暗色主题用 Plus（自发光）。
 */
private fun DrawScope.orb(
    color: Color,
    center: Offset,
    diameter: Float,
    progress: Float,
    blend: BlendMode
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
        blendMode = blend
    )
}

/** 便捷：把整块背景铺满并作为内容容器。 */
@Composable
fun GlassScaffold(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    imagePath: String? = null, auroraColorA: Color? = null, auroraColorB: Color? = null,
    content: @Composable () -> Unit
) {
    Box(modifier.fillMaxSize()) {
        GlassBackground(backdrop, imagePath = imagePath)
        content()
    }
}
