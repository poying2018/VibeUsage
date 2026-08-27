package ai.vibecafe.usage.core

import android.content.Context

/** 外观模式：跟随系统 / 强制浅色 / 强制深色 / AMOLED 纯黑。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    AMOLED("纯黑")
}

/**
 * 外观模式持久化。
 * 与 ApiKeyStore / BackgroundStore 共用同一份 SharedPreferences（vibe_usage）。
 */
object ThemeStore {
    private const val PREFS_NAME = "vibe_usage"
    private const val KEY_THEME = "theme_mode"

    fun get(context: Context): ThemeMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}
