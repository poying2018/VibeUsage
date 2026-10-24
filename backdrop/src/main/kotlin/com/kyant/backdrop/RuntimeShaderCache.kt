package com.kyant.backdrop

import androidx.annotation.RequiresApi
import org.intellij.lang.annotations.Language

sealed interface RuntimeShaderCache {

    /**
     * 构造 RuntimeShader（需要 API 33）。
     * 所有调用点都在 [isRuntimeShaderSupported] 守卫之后，lint 的 NewApi 在这里是误报。
     */
    @RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
    fun obtainRuntimeShader(key: String, @Language("AGSL") string: String): RuntimeShader
}

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {

    private val runtimeShaders = mutableMapOf<String, RuntimeShader>()

    @RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaders.getOrPut(key) { RuntimeShader(string) }
    }

    fun clear() {
        runtimeShaders.clear()
    }
}
