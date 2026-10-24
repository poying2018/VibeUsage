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
 * v3 加密格式（当前）：凭据 JSON 的内容密钥由**用户口令**经 PBKDF2-HMAC-SHA256
 * 派生（随机盐 + 12 万次迭代），再做 AES-256-GCM 加密；盐和迭代次数写进文件。
 * 备份文件离开设备就解不开 —— 口令只存在于用户脑子里，文件里没有。
 * v2 格式（应用内置主密钥包裹内容密钥）仍可导入：老备份里没有口令，
 * 用内置密钥解密即可。v1（明文 JSON）同样可导入。
 */
object CredentialBackup {

    private const val MAGIC = "vibeusage-credential-backup"
    private const val V1 = 1
    private const val V2 = 2
    private const val V3 = 3
    internal const val KEY_ID = 1

    /** 口令最短长度。低于这个长度就等于没加密（纯暴力可破）。 */
    const val MIN_PASSPHRASE_LEN = 6

    private val gson = Gson()

    /** prefs 文件名 → 待导出键集合（null = 全部；范围内键值均为字符串）。 */
    private val SCOPE = mapOf(
        // 扩展供应商凭据（sk-/refresh token/账号密码等），全部导出
        "quota_extra" to null,
        // 反重力（Google AI Pro）：refresh_token/tier/email
        "ag_panel" to null,
        // 主应用的 API Key：已独立成 vibe_credentials（凭据专用文件，
        // 云备份按文件排除它，theme_mode/glass_* 留在 vibe_usage 照常备份）
        "vibe_credentials" to setOf("api_key")
    )

    /**
     * 生成加密备份 JSON；没有任何可导出的凭据时返回 null。
     *
     * @param passphrase 用户口令；为空则用应用内置主密钥（v2 旧格式）。
     *   传了口令就写 v3 格式，文件自带口令派生的加密强度。
     */
    fun export(context: Context, passphrase: String? = null): String? {
        val files = collect(context)
        if (files.values.all { it.isEmpty() }) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return if (passphrase.isNullOrEmpty()) {
            buildEnvelope(stamp, files, AppKey.key)
        } else {
            buildPassphraseEnvelope(stamp, files, passphrase)
        }
    }

    /**
     * 导入并写回 prefs，返回恢复的条目数；文件格式不对或无法解密抛 IllegalArgumentException。
     * 只写备份里存在的键，本机其他键（包括各家外观设置）不动。
     *
     * @param passphrase 用户口令；v3 备份必须给对，v1/v2 旧备份不需要。
     */
    fun import(context: Context, text: String, passphrase: String? = null): Int {
        val obj = gson.fromJson(text, JsonObject::class.java)
            ?: throw IllegalArgumentException("文件内容为空")
        if (obj.get("magic")?.takeIf { it.isJsonPrimitive }?.asString != MAGIC) {
            throw IllegalArgumentException("不是 VibeUsage 凭据备份文件")
        }
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: V1
        if (version <= V1) return restore(context, obj)
        if (version == V3) {
            if (passphrase.isNullOrEmpty()) {
                throw IllegalArgumentException("该备份由口令加密，请输入口令")
            }
            val plaintext = openPassphraseEnvelope(obj, passphrase)
            val v1 = gson.fromJson(String(plaintext, Charsets.UTF_8), JsonObject::class.java)
                ?: throw IllegalArgumentException("备份数据已损坏")
            return restore(context, v1)
        }
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

    /**
     * 这个备份是不是口令加密的（v3）。
     *
     * 导入流程要先读文件才知道要不要问口令 —— 老备份（v1/v2）不该被问。
     */
    fun needsPassphrase(text: String): Boolean = runCatching {
        val obj = gson.fromJson(text, JsonObject::class.java) ?: return false
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: V1
        version >= V3
    }.getOrDefault(false)

    // ---- v3：口令派生密钥的信封 ----

    /**
     * 用口令派生的密钥加密，凭据 JSON 直接加密（不需要再包裹内容密钥：
     * 口令派生的密钥本身就是内容密钥）。
     *
     * 盐和迭代次数写进文件 —— 同一个口令在不同备份里用不同盐，
     * 拿到两个备份的人不能靠一张彩虹表一起撞。
     */
    internal fun buildPassphraseEnvelope(
        exportedAt: String,
        files: Map<String, Map<String, String>>,
        passphrase: String
    ): String {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_LEN)
        val key = BackupCrypto.deriveKey(passphrase.toCharArray(), salt)
        val v1Json = gson.toJson(linkedMapOf("magic" to MAGIC, "version" to V1, "prefs" to files))
        val root = linkedMapOf<String, Any?>(
            "magic" to MAGIC,
            "version" to V3,
            "exported_at" to exportedAt,
            "cipher" to "AES-256-GCM",
            "kdf" to "PBKDF2-HMAC-SHA256",
            "iterations" to BackupCrypto.KDF_ITERATIONS,
            "salt" to BackupCrypto.b64(salt),
            "payload" to sealed(BackupCrypto.encrypt(key, v1Json.toByteArray(Charsets.UTF_8)))
        )
        return gson.toJson(root)
    }

    internal fun openPassphraseEnvelope(obj: JsonObject, passphrase: String): ByteArray {
        val payload = sealedOf(obj, "payload")
            ?: throw IllegalArgumentException("备份文件已损坏")
        val saltB64 = obj.get("salt")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalArgumentException("备份文件已损坏（缺少盐）")
        val iterations = obj.get("iterations")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: BackupCrypto.KDF_ITERATIONS
        val salt = try {
            BackupCrypto.unb64(saltB64)
        } catch (_: Exception) {
            throw IllegalArgumentException("备份文件已损坏（盐无效）")
        }
        val key = BackupCrypto.deriveKey(passphrase.toCharArray(), salt, iterations)
        return try {
            BackupCrypto.decrypt(key, payload.first, payload.second)
        } catch (_: Exception) {
            // 口令错、文件被改、密钥参数不合法 —— 对用户都是同一件事：解不开
            throw IllegalArgumentException("口令不正确，或备份文件已损坏")
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
        val plan = planRestore(files)
        if (plan.restored == 0) {
            // 全部条目都被拒绝（或备份里根本没有可恢复的凭据）：不静默成功
            throw IllegalArgumentException(
                if (plan.rejected > 0) "备份里的凭据条目全部不在允许范围内（已跳过 ${plan.rejected} 项）"
                else "备份里没有可恢复的凭据"
            )
        }
        // 先全部收集校验、再统一写入，避免写一半抛异常（部分导入）
        for ((name, values) in plan.accepted) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for ((key, v) in values) {
                editor.putString(key, v)
            }
            editor.apply()
        }
        return plan.restored
    }

    /** 恢复计划：要写进哪些 prefs 的哪些键，以及被拒绝的条目数。 */
    internal data class RestorePlan(
        val accepted: Map<String, Map<String, String>>,
        val restored: Int,
        val rejected: Int
    )

    /**
     * 纯逻辑：把备份里的 prefs 条目按白名单筛选成「要写什么」。
     * 不碰 Context，便于单测覆盖越界条目与旧格式迁移。
     */
    internal fun planRestore(files: JsonObject): RestorePlan {
        val accepted = LinkedHashMap<String, MutableMap<String, String>>()
        var restored = 0
        var rejected = 0
        for ((name, keysEl) in files.entrySet()) {
            // 兼容旧备份：API Key 以前存在 vibe_usage 里，现在已独立成 vibe_credentials。
            // 旧备份里那一项要被迁到新位置，而不是当成越界条目丢掉
            // （否则用户恢复旧备份后 Key 消失，等于备份失效）。
            if (name == "vibe_usage") {
                val legacyApiKey = (keysEl as? JsonObject)?.get("api_key")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (!legacyApiKey.isNullOrEmpty()) {
                    accepted.getOrPut("vibe_credentials") { LinkedHashMap() }["api_key"] = legacyApiKey
                    restored++
                }
                // vibe_usage 里剩下的都是外观设置（theme_mode/glass_*），不属于凭据，跳过
                rejected += (keysEl as? JsonObject)?.size() ?: 1
                continue
            }
            if (!SCOPE.containsKey(name)) {
                // 不在导出白名单里的 prefs 文件：整份跳过（防止备份写入任意 prefs）
                rejected += (keysEl as? JsonObject)?.size() ?: 1
                continue
            }
            val scopeKeys = SCOPE[name] // null = 该文件允许全部键
            val keys = keysEl as? JsonObject
            if (keys == null) {
                rejected++
                continue
            }
            for ((key, v) in keys.entrySet()) {
                // vibe_credentials 只允许 SCOPE 里列出的键；其余文件（keys == null）保持全部键语义
                if (scopeKeys != null && key !in scopeKeys) {
                    rejected++
                    continue
                }
                // 非字符串值不恢复（与旧行为一致，不计入白名单拒绝数）
                if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                    accepted.getOrPut(name) { LinkedHashMap() }[key] = v.asString
                    restored++
                }
            }
        }
        return RestorePlan(accepted, restored, rejected)
    }
}

/**
 * 应用内置主密钥：分片十六进制材料运行时拼接后做 SHA-256 派生，
 * 避免成品密钥以完整常量出现在字节码里。
 *
 * **只用于解开 v2 旧备份**：v2 的内容密钥由这个固定密钥包裹，密钥在 APK 里，
 * 所以 v2 备份其实是「换个应用就能读」的。当前导出的 v3 备份用用户口令派生密钥，
 * 与本密钥无关。
 *
 * 这三个分片是长期承诺，发布后不可改动，否则 v2 旧备份将无法解密；
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
