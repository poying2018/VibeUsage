package ai.vibecafe.usage.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 液态玻璃全局样式（catalog 完整配方：磨砂基底 + lens 折射 + 高光投影）。
 *
 * 磨砂基底来自 kyant catalog 的 LockScreen 演示；自 v2.12 玻璃面补齐
 * lens/Highlight/Shadow 后，默认值改为「增强」方向：亮度归零、对比度 1.0
 * （不再压平背景）、白叠加降到 0.15，让背景色透过玻璃保持鲜亮。
 * 所有玻璃面（glassCard / glassRow / glassTile）统一从这里取参，
 * 设置页的「液态玻璃」卡片修改后全局实时生效。
 *
 * 与 ThemeStore / ApiKeyStore / BackgroundStore 共用同一份 SharedPreferences（vibe_usage）。
 */
object GlassStyleStore {

    /** 磨砂基底参数。 */
    data class GlassStyle(
        val brightness: Float = 0f,       // 亮度 -0.5..0.5
        val contrast: Float = 1f,         // 对比度 0.5..1.5（1 = 不改变背景对比度）
        val saturation: Float = 1.5f,     // 饱和度 0..2（= vibrancy）
        val whiteOverlay: Float = 0.15f,  // 白色叠加 0..0.5（backdrop 上的磨砂白）
        val blurCardDp: Float = 8f        // 卡片模糊 0..32dp；行/磁贴按比例缩小
    ) {
        val blurRowDp: Float get() = (blurCardDp * 0.7f).coerceAtLeast(4f)
        val blurTileDp: Float get() = (blurCardDp * 0.5f).coerceAtLeast(2f)
    }

    val Default = GlassStyle()

    private const val KEY_BRIGHTNESS = "glass_brightness"
    private const val KEY_CONTRAST = "glass_contrast"
    private const val KEY_SATURATION = "glass_saturation"
    private const val KEY_WHITE = "glass_white_overlay"
    private const val KEY_BLUR = "glass_blur_card"

    /**
     * 参数钳制：prefs 里的值可能来自旧版本或被外部改写，越界/NaN 会一路传进
     * ColorMatrix / RuntimeShader —— contrast=0 让画面全黑、NaN 让 blur 变未定义。
     * 所有入口（读 prefs 与写入）都过这里，保证渲染层拿到的永远是合法值。
     */
    private fun sanitize(v: GlassStyle): GlassStyle = GlassStyle(
        brightness = v.brightness.safeRange(-0.5f, 0.5f, 0f),
        contrast = v.contrast.safeRange(0.5f, 1.5f, 1f),
        saturation = v.saturation.safeRange(0f, 2f, 1.5f),
        whiteOverlay = v.whiteOverlay.safeRange(0f, 0.5f, 0.15f),
        blurCardDp = v.blurCardDp.safeRange(0f, 32f, 8f)
    )

    /** NaN/Infinity 一律回落到默认值，其余钳到区间内。 */
    private fun Float.safeRange(min: Float, max: Float, fallback: Float): Float =
        if (isNaN() || isInfinite()) fallback else coerceIn(min, max)

    @Volatile
    private var initialized = false

    /** 快照态：任何玻璃 composable 读取后，样式变化即触发重组。 */
    var style by mutableStateOf(Default)
        private set

    /** App 启动时调用一次；重复调用无副作用。 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.getSharedPreferences("vibe_usage", Context.MODE_PRIVATE)
            style = sanitize(
                GlassStyle(
                    brightness = prefs.getFloat(KEY_BRIGHTNESS, Default.brightness),
                    contrast = prefs.getFloat(KEY_CONTRAST, Default.contrast),
                    saturation = prefs.getFloat(KEY_SATURATION, Default.saturation),
                    whiteOverlay = prefs.getFloat(KEY_WHITE, Default.whiteOverlay),
                    blurCardDp = prefs.getFloat(KEY_BLUR, Default.blurCardDp)
                )
            )
            initialized = true
        }
    }

    /** 更新样式并持久化（滑杆拖动时高频调用，apply() 异步写盘）。 */
    fun set(context: Context, value: GlassStyle) {
        val safe = sanitize(value)
        style = safe
        context.getSharedPreferences("vibe_usage", Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_BRIGHTNESS, safe.brightness)
            .putFloat(KEY_CONTRAST, safe.contrast)
            .putFloat(KEY_SATURATION, safe.saturation)
            .putFloat(KEY_WHITE, safe.whiteOverlay)
            .putFloat(KEY_BLUR, safe.blurCardDp)
            .apply()
    }
}
