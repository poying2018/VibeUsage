package ai.vibecafe.usage.core

import android.content.Context

/**
 * 持久化存储 VibeCafe API Key。
 * 原登录界面的 SharedPreferences 逻辑被抽离为独立核心工具，
 * 供界面重构后的登录/鉴权流程复用。
 */
object ApiKeyStore {
    private const val PREFS_NAME = "vibe_usage"
    private const val KEY_API_KEY = "api_key"

    fun get(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
    }

    fun save(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_API_KEY)
            .apply()
    }
}
