package ai.vibecafe.usage.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos

/**
 * 自研玻璃渲染引擎（AGSL / Android Graphics Shading Language）。
 */
object GlassEngine {

    val AURORA_AGSL = """
        uniform float2 uSize;
        uniform float uTime;
        uniform float2 uLight;       // -1..1，光源方向（重力感应驱动）
        uniform half uStrength;      // 总强度 0..1
        uniform half4 uColorA;
        uniform half4 uColorB;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / uSize;
            float b1 = sin((uv.x + uv.y * 0.55) * 6.2831 + uTime * 0.42);
            float b2 = sin((uv.x * 0.85 - uv.y) * 4.2 - uTime * 0.27);
            float sheen = smoothstep(0.88, 1.0, b1) * 0.6 + smoothstep(0.92, 1.0, b2) * 0.4;
            float2 toLight = uv - (float2(0.5, 0.38) + uLight * 0.28);
            float spec = exp(-dot(toLight, toLight) * 14.0);
            float ca = 0.5 + 0.5 * sin(uTime * 0.9 + uv.x * 3.0);
            half3 rgb = mix(uColorA.rgb, uColorB.rgb, half(uv.x + 0.15 * ca));
            rgb += half3(0.04 * ca, 0.02, 0.05 * (1.0 - ca)) * half(sheen);
            float a = (sheen * 0.22 + spec * 0.40) * uStrength;
            return half4(rgb, half(a));
        }
    """.trimIndent()

    fun newAuroraShader(): android.graphics.RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching { android.graphics.RuntimeShader(AURORA_AGSL) }.getOrNull()
    }
}

/**
 * 光源方向及其关联的活动状态。
 * [isActive] == false 意味着设备绝对静止超过一定时间（或处于省电模式），可以安全挂起渲染管线降频。
 */
data class LightState(
    val direction: Offset = Offset.Zero,
    val isActive: Boolean = true
)

@Composable
fun rememberLightState(): State<LightState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(LightState()) }

    DisposableEffect(context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isPowerSaveMode == true) {
            // 省电模式下，强制宣告非活跃且光静止，触发全量挂起
            state.value = LightState(Offset.Zero, false)
            return@DisposableEffect onDispose {}
        }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        var hasReading = false
        var lastGx = 0f
        var lastGy = 0f
        var lastActiveTime = System.nanoTime()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values.getOrNull(0) ?: return
                val gy = event.values.getOrNull(1) ?: return
                
                // 完全静止判定（变化低于 0.05 m/s² 视为未活动）
                val changed = abs(gx - lastGx) > 0.05f || abs(gy - lastGy) > 0.05f
                val now = System.nanoTime()
                if (changed) {
                    lastActiveTime = now
                    lastGx = gx
                    lastGy = gy
                }
                
                // 持续静止超过 2.5 秒，则发出 inactive 信号使画布休眠
                val isActive = (now - lastActiveTime) < 2_500_000_000L
                val norm = hypot(gx, gy)
                
                if (norm < 0.5f) {
                    state.value = state.value.copy(isActive = isActive)
                    return
                }
                
                hasReading = true
                val k = 2.2f
                val nextOffset = Offset(
                    (gx / norm * k).coerceIn(-1f, 1f),
                    (-gy / norm * k).coerceIn(-1f, 1f)
                )
                
                // 只在状态变化或位置变化时更新
                if (state.value.isActive != isActive || state.value.direction != nextOffset) {
                    state.value = LightState(nextOffset, isActive)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = sm != null && sensor != null &&
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            
        val poller = if (!registered) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val drift = object : Runnable {
                override fun run() {
                    if (!hasReading) {
                        val t = System.nanoTime() / 1_000_000_000f
                        state.value = LightState(Offset(sin(t * 0.45f), cos(t * 0.33f)), true)
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
    return state
}
