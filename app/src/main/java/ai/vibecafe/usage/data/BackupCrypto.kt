package ai.vibecafe.usage.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭据备份加密原语（纯 JVM，可单测）：
 * 内容密钥随机生成做 AES-256-GCM 加密，应用内置主密钥再包裹内容密钥。
 */
internal object BackupCrypto {
    const val GCM_TAG_BITS = 128
    const val IV_LEN = 12
    const val KEY_LEN_BITS = 256

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** AES-256-GCM 加密，返回 (iv, ciphertext+tag)。 */
    fun encrypt(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv to cipher.doFinal(plaintext)
    }

    /** 解密；密钥不符或密文被篡改时抛 AEADBadTagException。 */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
