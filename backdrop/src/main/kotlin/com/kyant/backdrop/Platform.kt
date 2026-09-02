package com.kyant.backdrop

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * RenderEffect（blur/colorFilter 链）需要 API 31+。
 * 低版本调用 effects 时会静默跳过，玻璃自动降级为普通半透明层。
 */
@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * RuntimeShader（AGSL 折射/色散 lens 与 highlight 着色器）需要 API 33+。
 */
@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
