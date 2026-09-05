package ai.vibecafe.usage.data

import android.content.Context
import com.google.gson.JsonObject

/**
 * 接入凭据导出/导入（换机备份用）：把各凭据 prefs 打成一个 JSON 文本。
 * 明文存储全部密钥，导出文件务必妥善保管；导入时逐键写回原 prefs。
 * 范围只含凭据类键（外观/主题等设置不导，导入不覆盖个性化配置）。
 */
object CredentialBackup {

    private const val MAGIC = "vibeusage-credential-backup"
    private const val VERSION = 1

    private val gson = com.google.gson.Gson()

    /** prefs 文件名 → 待导出键集合（null = 全部；范围内键值均为字符串）。 */
    private val SCOPE = mapOf(
        // 扩展供应商凭据（sk-/refresh token/账号密码等），全部导出
        "quota_extra" to null,
        // 反重力（Google AI Pro）：refresh_token/tier/email
        "ag_panel" to null,
        // 主应用：只导 API Key，theme_mode/glass_* 是外观设置不属于凭据
        "vibe_usage" to setOf("api_key")
    )

    /** 生成备份 JSON；没有任何可导出的凭据时返回 null。 */
    fun export(context: Context): String? {
        val files = SCOPE.mapValues { (name, keys) ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            (if (keys == null) all else all.filterKeys { it in keys })
                .filterValues { it is String }
                .mapValues { it.value as String }
        }.filterValues { it.isNotEmpty() }
        if (files.values.all { it.isEmpty() }) return null
        return gson.toJson(
            linkedMapOf(
                "magic" to MAGIC,
                "version" to VERSION,
                "exported_at" to java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.US
                ).format(java.util.Date()),
                "prefs" to files
            )
        )
    }

    /**
     * 导入并写回 prefs，返回恢复的条目数；文件格式不对抛 IllegalArgumentException。
     * 只写备份里存在的键，本机其他键（包括各家外观设置）不动。
     */
    fun import(context: Context, text: String): Int {
        val obj = gson.fromJson(text, JsonObject::class.java)
            ?: throw IllegalArgumentException("文件内容为空")
        if (obj.get("magic")?.takeIf { it.isJsonPrimitive }?.asString != MAGIC) {
            throw IllegalArgumentException("不是 VibeUsage 凭据备份文件")
        }
        val files = obj.getAsJsonObject("prefs")
            ?: throw IllegalArgumentException("备份里没有凭据数据")
        var restored = 0
        for ((name, keysEl) in files.entrySet()) {
            val keys = keysEl as? JsonObject ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for ((key, v) in keys.entrySet()) {
                if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                    editor.putString(key, v.asString)
                    restored++
                }
            }
            editor.apply()
        }
        return restored
    }
}
