package ai.vibecafe.usage.core

import android.content.Context

/**
 * 持久化存储 VibeCafe API Key。
 *
 * 独立成一个 prefs 文件（而不是和其它设置混在 `vibe_usage` 里）：
 * 云备份规则按**文件**排除，`vibe_usage` 里还有主题、液态玻璃参数等外观设置，
 * 整份排除会把它们一起丢掉（换机后主题与玻璃样式归零）。
 * 单独成文件后，备份规则可以只排除这一个文件。
 *
 * 迁移：老版本把 Key 存在 `vibe_usage` 的 `api_key` 键里。首次读取时若新位置为空、
 * 旧位置有值，就搬一次并删掉旧键（不让凭据继续留在会被备份的 prefs 里）。
 */
object ApiKeyStore {
    /** 凭据专用 prefs：备份规则按文件排除这一个。 */
    private const val PREFS_NAME = "vibe_credentials"
    private const val KEY_API_KEY = "api_key"

    /** 旧版本的位置（只读一次，迁移后清空）。 */
    private const val LEGACY_PREFS_NAME = "vibe_usage"
    private const val LEGACY_KEY_API_KEY = "api_key"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_API_KEY, null)
        if (stored != null) return stored
        return migrateLegacy(context)
    }

    fun save(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
        // 新位置写成功后，清掉旧位置（否则凭据仍留在会被云备份带走的 prefs 里）
        clearLegacy(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_API_KEY)
            .apply()
        clearLegacy(context)
    }

    /** 旧版本 → 新位置的凭据搬迁；没有旧值时返回空串。 */
    private fun migrateLegacy(context: Context): String {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val old = legacy.getString(LEGACY_KEY_API_KEY, null)
        if (old.isNullOrEmpty()) return ""
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, old)
            .apply()
        clearLegacy(context)
        return old
    }

    private fun clearLegacy(context: Context) {
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_KEY_API_KEY)
            .apply()
    }
}
