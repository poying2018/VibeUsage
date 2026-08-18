package ai.vibecafe.usage.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 当前组合的液态玻璃调色板：亮/暗随主题切换，绘制层与文字层统一从这里取色。 */
val LocalGlassPalette = staticCompositionLocalOf { LightGlassPalette }

/**
 * 液态玻璃主题根：提供 [LocalGlassPalette] + MaterialTheme（排版/基础色），
 * 并同步系统栏图标明暗（亮色主题用深色图标，暗色主题用浅色图标）。
 *
 * @param darkTheme true 使用 [DarkGlassPalette]，false 使用 [LightGlassPalette]
 */
@Composable
fun GlassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkGlassPalette else LightGlassPalette

    // 系统栏图标明暗跟随主题（enableEdgeToEdge 下由 insets controller 控制）
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            onDispose {}
        }
    }

    CompositionLocalProvider(LocalGlassPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) {
                darkColorScheme(
                    primary = palette.Accent,
                    onPrimary = palette.InkStrong,
                    background = palette.Page,
                    surface = palette.Page,
                    onBackground = palette.InkHi,
                    onSurface = palette.InkHi
                )
            } else {
                lightColorScheme(
                    primary = palette.Accent,
                    onPrimary = palette.InkStrong,
                    background = palette.Page,
                    surface = palette.Page,
                    onBackground = palette.InkHi,
                    onSurface = palette.InkHi
                )
            },
            typography = HanSansTypography,
            content = content
        )
    }
}
