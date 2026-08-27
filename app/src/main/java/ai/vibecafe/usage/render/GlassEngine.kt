package ai.vibecafe.usage.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext

/**
 * 自研玻璃渲染引擎（AGSL / Android Graphics Shading Language）。
 *
 * Android 应用无法替换系统渲染管线（HWUI/Skia），本引擎在管线之上叠加一层
 * 程序化 GPU 着色：流光带 + 光源方向镜面高光 + 色散微闪，让玻璃背景拥有
 * 随设备倾角实时变化的光照——不采样任何 backdrop（彻底避免 RenderNode 循环），
 * API < 33 自动降级为无叠加（不崩溃）。
 */
object GlassEngine {

    /** AGSL 源码：双层错速流光带（细窄带）+ 光源镜面衰减 + RGB 通道微色散。 */
    val AURORA_AGSL = """
        uniform float2 uSize;
        uniform float uTime;
        uniform float2 uLight;       // -1..1，光源方向（重力感应驱动）
        uniform half uStrength;      // 总强度 0..1
        uniform half4 uColorA;
        uniform half4 uColorB;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / uSize;
            // 双层错速流光带（对角走向，细窄带避免大面积灰蒙）
            float b1 = sin((uv.x + uv.y * 0.55) * 6.2831 + uTime * 0.42);
            float b2 = sin((uv.x * 0.85 - uv.y) * 4.2 - uTime * 0.27);
            float sheen = smoothstep(0.88, 1.0, b1) * 0.6 + smoothstep(0.92, 1.0, b2) * 0.4;
            // 光源方向镜面高光：光斑中心随倾角移动
            float2 toLight = uv - (float2(0.5, 0.38) + uLight * 0.28);
            float spec = exp(-dot(toLight, toLight) * 14.0);
            // 色散微闪：三通道相位略错开
            float ca = 0.5 + 0.5 * sin(uTime * 0.9 + uv.x * 3.0);
            half3 rgb = mix(uColorA.rgb, uColorB.rgb, half(uv.x + 0.15 * ca));
            rgb += half3(0.04 * ca, 0.02, 0.05 * (1.0 - ca)) * half(sheen);
            float a = (sheen * 0.22 + spec * 0.40) * uStrength;
            return half4(rgb, half(a));
        }
    """.trimIndent()

    /** API 33+ 返回 Aurora 着色器实例，否则 null（调用方跳过叠加层）。 */
    fun newAuroraShader(): android.graphics.RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching { android.graphics.RuntimeShader(AURORA_AGSL) }.getOrNull()
    }
}

/**
 * 光源方向：优先取加速度计倾角（拿起手机光斑会流动），
 * 无传感器 / 数值无效时退化为缓慢圆周漂移（基于时钟的确定性动画）。
 */
@Composable
fun rememberLightDirection(): State<Offset> {
    val context = LocalContext.current
    val light = remember { mutableStateOf(Offset(0f, 0f)) }

    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var hasReading = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // 重力向量 → 倾角：屏幕平放时 (0,0,9.8)，竖起时 (0,9.8,0)
                val gx = event.values.getOrNull(0) ?: return
                val gy = event.values.getOrNull(1) ?: return
                val norm = kotlin.math.hypot(gx, gy)
                if (norm < 0.5f) return   // 平放无倾角：保留默认光位
                hasReading = true
                val k = 2.2f              // 灵敏度：光斑随倾角明显移动
                light.value = Offset(
                    (gx / norm * k).coerceIn(-1f, 1f),
                    (-gy / norm * k).coerceIn(-1f, 1f)
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = sm != null && sensor != null &&
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        // 模拟器/无传感器兜底：慢速圆周漂移（基于时钟，无陈旧捕获问题）
        val poller = if (!registered) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val drift = object : Runnable {
                override fun run() {
                    if (!hasReading) {
                        val t = System.nanoTime() / 1_000_000_000f
                        light.value = Offset(kotlin.math.sin(t * 0.45f), kotlin.math.cos(t * 0.33f))
                    }
                    handler.postDelayed(this, 33)
                }
            }
            handler.post(drift)
            handler
        } else null
        onDispose {
            sm?.unregisterListener(listener)
            poller?.removeCallbacksAndMessages(null)
        }
    }
    return light
}
