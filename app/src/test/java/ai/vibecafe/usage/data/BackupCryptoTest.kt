package ai.vibecafe.usage.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class BackupCryptoTest {

    private val gson = Gson()
    private val files = mapOf(
        "quota_extra" to mapOf("codex:tokens" to "{\"access_token\":\"sk-test\"}"),
        "vibe_usage" to mapOf("api_key" to "vk-123")
    )

    private fun randomKey() = ByteArray(BackupCrypto.KEY_LEN_BITS / 8).also { SecureRandom().nextBytes(it) }

    @Test
    fun `aes gcm roundtrip and tamper detection`() {
        val key = randomKey()
        val (iv, ct) = BackupCrypto.encrypt(key, "凭据数据".toByteArray(Charsets.UTF_8))
        assertEquals("凭据数据", String(BackupCrypto.decrypt(key, iv, ct), Charsets.UTF_8))
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(key, iv, ct) }
    }

    @Test
    fun `envelope roundtrip and no plaintext leak`() {
        val masterKey = randomKey()
        val json = CredentialBackup.buildEnvelope("2026-09-07 12:00", files, masterKey)
        assertFalse(json.contains("sk-test"))
        assertFalse(json.contains("vk-123"))
        val obj = gson.fromJson(json, JsonObject::class.java)
        assertEquals(2, obj.get("version").asInt)
        assertEquals(CredentialBackup.KEY_ID, obj.get("key_id").asInt)
        val restored = String(CredentialBackup.openEnvelope(obj, masterKey), Charsets.UTF_8)
        assertTrue(restored.contains("sk-test"))
        assertTrue(restored.contains("vk-123"))
    }

    @Test
    fun `envelope rejects foreign key`() {
        val json = CredentialBackup.buildEnvelope("t", files, randomKey())
        val obj = gson.fromJson(json, JsonObject::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackup.openEnvelope(obj, randomKey())
        }
    }

    @Test
    fun `envelope rejects tampered payload`() {
        val masterKey = randomKey()
        val obj = gson.fromJson(CredentialBackup.buildEnvelope("t", files, masterKey), JsonObject::class.java)
        val payload = obj.getAsJsonObject("payload")
        val ct = BackupCrypto.unb64(payload.get("ct").asString)
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        payload.addProperty("ct", BackupCrypto.b64(ct))
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackup.openEnvelope(obj, masterKey)
        }
    }

    @Test
    fun `legacy backup with api key under vibe_usage is still restorable`() {
        // 旧版本的 API Key 存在 vibe_usage 里，现在凭据已独立成 vibe_credentials。
        // 恢复旧备份时那一项要被迁到新位置，否则用户恢复后 Key 直接消失（备份等于失效）。
        val legacy = JsonObject().apply {
            add("prefs", JsonObject().apply {
                add("vibe_usage", JsonObject().apply {
                    addProperty("api_key", "vk-legacy")
                    addProperty("theme_mode", "DARK")   // 外观设置，不属于凭据
                })
            })
        }
        val plan = CredentialBackup.planRestore(legacy.getAsJsonObject("prefs"))
        assertEquals("旧备份里的 api_key 应被恢复", 1, plan.restored)
        assertEquals("vk-legacy", plan.accepted["vibe_credentials"]?.get("api_key"))
        // 外观设置不该被当成凭据写进 vibe_credentials
        assertNull(plan.accepted["vibe_credentials"]?.get("theme_mode"))
    }

    @Test
    fun `entries outside the whitelist are rejected`() {
        val hostile = JsonObject().apply {
            add("quota_extra", JsonObject().apply { addProperty("mimo_key", "sk-x") })
            add("evil_prefs", JsonObject().apply { addProperty("api_key", "stolen") })
            add("vibe_credentials", JsonObject().apply { addProperty("not_api_key", "x") })
        }
        val plan = CredentialBackup.planRestore(hostile)
        // 只有白名单内的键被接受
        assertEquals(1, plan.restored)
        assertEquals("sk-x", plan.accepted["quota_extra"]?.get("mimo_key"))
        // 越界文件整体被拒
        assertNull(plan.accepted["evil_prefs"])
        // 白名单文件里的越界键也被拒
        assertNull(plan.accepted["vibe_credentials"]?.get("not_api_key"))
    }

    // ---- v3：口令派生密钥 ----

    @Test
    fun `derived key is deterministic for the same passphrase and salt`() {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_LEN)
        val a = BackupCrypto.deriveKey("hunter22".toCharArray(), salt)
        val b = BackupCrypto.deriveKey("hunter22".toCharArray(), salt)
        assertTrue(a.contentEquals(b))
        assertEquals(BackupCrypto.KEY_LEN_BITS / 8, a.size)
    }

    @Test
    fun `derived key differs for a different salt or passphrase`() {
        val salt1 = BackupCrypto.randomBytes(BackupCrypto.SALT_LEN)
        val salt2 = BackupCrypto.randomBytes(BackupCrypto.SALT_LEN)
        val k1 = BackupCrypto.deriveKey("hunter22".toCharArray(), salt1)
        val k2 = BackupCrypto.deriveKey("hunter22".toCharArray(), salt2)
        val k3 = BackupCrypto.deriveKey("hunter23".toCharArray(), salt1)
        assertFalse(k1.contentEquals(k2))
        assertFalse(k1.contentEquals(k3))
    }

    @Test
    fun `passphrase envelope roundtrip and no plaintext leak`() {
        val json = CredentialBackup.buildPassphraseEnvelope("2026-10-24 09:00", files, "hunter22")
        assertFalse(json.contains("sk-test"))
        assertFalse(json.contains("vk-123"))
        val obj = gson.fromJson(json, JsonObject::class.java)
        assertEquals(3, obj.get("version").asInt)
        assertTrue(CredentialBackup.needsPassphrase(json))
        val plaintext = CredentialBackup.openPassphraseEnvelope(obj, "hunter22")
        val inner = gson.fromJson(String(plaintext, Charsets.UTF_8), JsonObject::class.java)
        assertEquals(
            "{\"access_token\":\"sk-test\"}",
            inner.getAsJsonObject("prefs").getAsJsonObject("quota_extra")
                .get("codex:tokens").asString
        )
    }

    @Test
    fun `wrong passphrase cannot decrypt`() {
        val json = CredentialBackup.buildPassphraseEnvelope("2026-10-24 09:00", files, "hunter22")
        val obj = gson.fromJson(json, JsonObject::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackup.openPassphraseEnvelope(obj, "hunter23")
        }
    }

    @Test
    fun `legacy envelopes do not require a passphrase`() {
        val json = CredentialBackup.buildEnvelope("2026-09-07 12:00", files, randomKey())
        assertFalse(CredentialBackup.needsPassphrase(json))
        // v1 明文备份同样不需要口令
        assertFalse(CredentialBackup.needsPassphrase("""{"magic":"vibeusage-credential-backup","version":1}"""))
    }

    @Test
    fun `each export uses a fresh salt`() {
        val a = CredentialBackup.buildPassphraseEnvelope("t", files, "hunter22")
        val b = CredentialBackup.buildPassphraseEnvelope("t", files, "hunter22")
        val saltA = gson.fromJson(a, JsonObject::class.java).get("salt").asString
        val saltB = gson.fromJson(b, JsonObject::class.java).get("salt").asString
        assertFalse(saltA == saltB)
    }
}
