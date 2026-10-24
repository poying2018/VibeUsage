package ai.vibecafe.usage.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭据备份加密原语（纯 JVM，可单测）：
 * 内容密钥随机生成做 AES-256-GCM 加密；v3 起内容密钥由**用户口令**经 PBKDF2 派生，
 * 备份文件离开设备也解不开。
 */
internal object BackupCrypto {
    const val GCM_TAG_BITS = 128
    const val IV_LEN = 12
    const val KEY_LEN_BITS = 256

    /** PBKDF2 盐长度（字节）。 */
    const val SALT_LEN = 16

    /**
     * PBKDF2 迭代次数。
     *
     * OWASP 对 PBKDF2-HMAC-SHA256 的建议是 600,000 次；这里取 120,000 是
     * 手机上导出/导入各一次、可以接受的折中（600k 在中低端机上要好几秒）。
     */
    const val KDF_ITERATIONS = 120_000

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /**
     * 口令 → 256 位密钥（PBKDF2-HMAC-SHA256）。
     *
     * 口令强度由用户决定，PBKDF2 的迭代次数负责把「弱口令被暴力破解」的成本
     * 拉到不可接受 —— 备份文件是明面上可被拷贝走的，没有这一层就等于没加密。
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = KDF_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LEN_BITS)
        try {
            return SecretKeyFactory.getInstance(KDF_ALGORITHM)
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

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
