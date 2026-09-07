package ai.vibecafe.usage.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接入凭据导出/导入（换机备份用）。
 *
 * v2 加密格式：凭据 JSON 用每次导出随机生成的内容密钥做 AES-256-GCM 加密，
 * 内容密钥再用应用内置主密钥包裹后一并写入文件。主密钥不写入备份、不绑定设备，
 * 任何安装了 VibeUsage 的设备都能解密恢复；拿到备份文件的人没有应用则读不出凭据。
 * 备份带 key_id，将来轮换主密钥时旧备份可提示升级或走多密钥解密。
 * v1（明文 JSON）仍可导入，兼容旧备份。
 */
object CredentialBackup {

    private const val MAGIC = "vibeusage-credential-backup"
    private const val V1 = 1
    private const val V2 = 2
    internal const val KEY_ID = 1

    private val gson = Gson()

    /** prefs 文件名 → 待导出键集合（null = 全部；范围内键值均为字符串）。 */
    private val SCOPE = mapOf(
        // 扩展供应商凭据（sk-/refresh token/账号密码等），全部导出
        "quota_extra" to null,
        // 反重力（Google AI Pro）：refresh_token/tier/email
        "ag_panel" to null,
        // 主应用：只导 API Key，theme_mode/glass_* 是外观设置不属于凭据
        "vibe_usage" to setOf("api_key")
    )

    /** 生成加密备份 JSON；没有任何可导出的凭据时返回 null。 */
    fun export(context: Context): String? {
        val files = collect(context)
        if (files.values.all { it.isEmpty() }) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return buildEnvelope(stamp, files, AppKey.key)
    }

    /**
     * 导入并写回 prefs，返回恢复的条目数；文件格式不对或无法解密抛 IllegalArgumentException。
     * 只写备份里存在的键，本机其他键（包括各家外观设置）不动。
     */
    fun import(context: Context, text: String): Int {
        val obj = gson.fromJson(text, JsonObject::class.java)
            ?: throw IllegalArgumentException("文件内容为空")
        if (obj.get("magic")?.takeIf { it.isJsonPrimitive }?.asString != MAGIC) {
            throw IllegalArgumentException("不是 VibeUsage 凭据备份文件")
        }
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: V1
        if (version <= V1) return restore(context, obj)
        val keyId = obj.get("key_id")?.takeIf { it.isJsonPrimitive }?.asInt ?: KEY_ID
        if (keyId != KEY_ID) {
            throw IllegalArgumentException("该备份使用了更新版本的加密密钥，请先升级应用")
        }
        val plaintext = openEnvelope(obj, AppKey.key)
        val v1 = gson.fromJson(String(plaintext, Charsets.UTF_8), JsonObject::class.java)
            ?: throw IllegalArgumentException("备份数据已损坏")
        return restore(context, v1)
    }

    // ---- 加密信封（纯逻辑，供单测；主密钥以参数注入） ----

    internal fun buildEnvelope(
        exportedAt: String,
        files: Map<String, Map<String, String>>,
        masterKey: ByteArray
    ): String {
        val contentKey = BackupCrypto.randomBytes(BackupCrypto.KEY_LEN_BITS / 8)
        val v1Json = gson.toJson(linkedMapOf("magic" to MAGIC, "version" to V1, "prefs" to files))
        val root = linkedMapOf<String, Any?>(
            "magic" to MAGIC,
            "version" to V2,
            "exported_at" to exportedAt,
            "cipher" to "AES-256-GCM",
            "key_id" to KEY_ID,
            "key" to sealed(BackupCrypto.encrypt(masterKey, contentKey)),
            "payload" to sealed(BackupCrypto.encrypt(contentKey, v1Json.toByteArray(Charsets.UTF_8)))
        )
        return gson.toJson(root)
    }

    internal fun openEnvelope(obj: JsonObject, masterKey: ByteArray): ByteArray {
        val wrapped = sealedOf(obj, "key")
            ?: throw IllegalArgumentException("备份文件已损坏")
        val payload = sealedOf(obj, "payload")
            ?: throw IllegalArgumentException("备份文件已损坏")
        val contentKey = try {
            BackupCrypto.decrypt(masterKey, wrapped.first, wrapped.second)
        } catch (_: Exception) {
            throw IllegalArgumentException("备份无法解密（可能已损坏或不来自 VibeUsage）")
        }
        return try {
            BackupCrypto.decrypt(contentKey, payload.first, payload.second)
        } catch (_: Exception) {
            throw IllegalArgumentException("备份数据已损坏")
        }
    }

    private fun sealed(box: Pair<ByteArray, ByteArray>): JsonObject = JsonObject().apply {
        add("iv", JsonPrimitive(BackupCrypto.b64(box.first)))
        add("ct", JsonPrimitive(BackupCrypto.b64(box.second)))
    }

    private fun sealedOf(obj: JsonObject, name: String): Pair<ByteArray, ByteArray>? {
        val box = obj.getAsJsonObject(name) ?: return null
        val iv = box.get("iv")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val ct = box.get("ct")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return runCatching { BackupCrypto.unb64(iv) to BackupCrypto.unb64(ct) }.getOrNull()
    }

    // ---- prefs 收集与恢复 ----

    private fun collect(context: Context): Map<String, Map<String, String>> =
        SCOPE.mapValues { (name, keys) ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            (if (keys == null) all else all.filterKeys { it in keys })
                .filterValues { it is String }
                .mapValues { it.value as String }
        }.filterValues { it.isNotEmpty() }

    private fun restore(context: Context, v1: JsonObject): Int {
        val files = v1.getAsJsonObject("prefs")
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

/**
 * 应用内置主密钥：分片十六进制材料运行时拼接后做 SHA-256 派生，
 * 避免成品密钥以完整常量出现在字节码里。所有安装共享同一密钥，
 * 换机/重装后凭据备份依然可由 VibeUsage 解密恢复。
 * 注意：这三个分片是长期承诺，发布后不可改动，否则旧备份将无法解密；
 * 轮换密钥需新增分片并递增 KEY_ID 做多密钥兼容。
 */
private object AppKey {
    private const val PART1 = "dbb23797ba4e231fb5c7e97cafa299c1579188db9f6356f4"
    private const val PART2 = "d40aa52e44f05be0db28e31183f06938d25a81096502bd63"
    private const val PART3 = "326c83cf3a5d27b78c0d45f096a4d2a4"

    val key: ByteArray by lazy {
        val material = hex(PART1) + hex(PART2) + hex(PART3)
        java.security.MessageDigest.getInstance("SHA-256").digest(material)
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
    }
}
