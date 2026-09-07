package ai.vibecafe.usage.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
