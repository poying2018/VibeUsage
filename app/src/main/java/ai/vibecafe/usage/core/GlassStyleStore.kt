package ai.vibecafe.usage.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 液态玻璃全局样式（LockScreen 配方）。
 *
 * 配方来自 kyant catalog 的 LockScreen 演示：
 * drawPlainBackdrop + colorControls(亮度/对比度/饱和度) + blur + 半透明白叠加。
 * 所有玻璃面（glassCard / glassRow / glassTile）统一从这里取参，
 * 设置页的「液态玻璃」卡片修改后全局实时生效。
 *
 * 与 ThemeStore / ApiKeyStore / BackgroundStore 共用同一份 SharedPreferences（vibe_usage）。
 */
object GlassStyleStore {

    /** LockScreen 配方参数。 */
    data class GlassStyle(
        val brightness: Float = -0.10f,   // 亮度 -0.5..0.5
        val contrast: Float = 0.75f,      // 对比度 0.5..1.5
        val saturation: Float = 1.5f,     // 饱和度 0..2
        val whiteOverlay: Float = 0.25f,  // 白色叠加 0..0.5（backdrop 上的磨砂白）
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
            style = GlassStyle(
                brightness = prefs.getFloat(KEY_BRIGHTNESS, Default.brightness),
                contrast = prefs.getFloat(KEY_CONTRAST, Default.contrast),
                saturation = prefs.getFloat(KEY_SATURATION, Default.saturation),
                whiteOverlay = prefs.getFloat(KEY_WHITE, Default.whiteOverlay),
                blurCardDp = prefs.getFloat(KEY_BLUR, Default.blurCardDp)
            )
            initialized = true
        }
    }

    /** 更新样式并持久化（滑杆拖动时高频调用，apply() 异步写盘）。 */
    fun set(context: Context, value: GlassStyle) {
        style = value
        context.getSharedPreferences("vibe_usage", Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_BRIGHTNESS, value.brightness)
            .putFloat(KEY_CONTRAST, value.contrast)
            .putFloat(KEY_SATURATION, value.saturation)
            .putFloat(KEY_WHITE, value.whiteOverlay)
            .putFloat(KEY_BLUR, value.blurCardDp)
            .apply()
    }
}
