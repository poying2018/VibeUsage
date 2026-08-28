package ai.vibecafe.usage.render

import android.os.Build

/**
 * 自研玻璃渲染引擎（AGSL / Android Graphics Shading Language）。
 */
object GlassEngine {

    val AURORA_AGSL = """
        uniform float2 uSize;
        uniform float uTime;
        uniform half uStrength;      // 总强度 0..1
        uniform half4 uColorA;
        uniform half4 uColorB;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / uSize;
            float b1 = sin((uv.x + uv.y * 0.55) * 6.2831 + uTime * 0.42);
            float b2 = sin((uv.x * 0.85 - uv.y) * 4.2 - uTime * 0.27);
            float sheen = smoothstep(0.88, 1.0, b1) * 0.6 + smoothstep(0.92, 1.0, b2) * 0.4;
            float2 toLight = uv - float2(0.5, 0.38);
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
