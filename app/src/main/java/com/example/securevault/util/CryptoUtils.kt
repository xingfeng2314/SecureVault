package com.example.securevault.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_LENGTH = 256
    private const val ITERATION_COUNT = 210000   // 提高到更安全的迭代次数
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    /**
     * 使用 PBKDF2 对主密码进行哈希（仅用于验证，不用于加密密钥）。
     * 迭代次数较高，减慢暴力破解。
     */
    fun hashPassword(password: String, salt: ByteArray): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * 加密：每次生成独立随机 salt + IV，密文格式 = salt(16) + iv(12) + ciphertext+tag
     * GCM 自带认证，解密失败会抛异常。
     */
    /** 解锁后由 VaultStore 设置的数据密钥（只在内存里）。有它时账号密码用 v2 格式：一次 AES，无需 PBKDF2 */
    @Volatile var dataKey: SecretKeySpec? = null
    private const val V2 = "v2:"

    fun isV2(encrypted: String) = encrypted.startsWith(V2)

    fun encrypt(plainText: String, masterPassword: String): String {
        val dk = dataKey ?: return encryptWithPassword(plainText, masterPassword)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, dk, GCMParameterSpec(TAG_LENGTH, iv))
        return V2 + Base64.encodeToString(iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 旧格式（每条单独 PBKDF2），只用来包数据密钥 dek.enc */
    fun encryptWithPassword(plainText: String, masterPassword: String): String {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(masterPassword, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = salt + iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedBase64: String, masterPassword: String): String {
        if (encryptedBase64.startsWith(V2)) {
            val dk = dataKey ?: error("保险箱未解锁")
            val data = Base64.decode(encryptedBase64.substring(V2.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, dk, GCMParameterSpec(TAG_LENGTH, data, 0, IV_LENGTH))
            return String(cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH), Charsets.UTF_8)
        }
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        require(combined.size > SALT_LENGTH + IV_LENGTH) { "密文格式错误" }

        val salt = combined.copyOfRange(0, SALT_LENGTH)
        val iv = combined.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = combined.copyOfRange(SALT_LENGTH + IV_LENGTH, combined.size)

        val key = deriveKey(masterPassword, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    // 每条密文的 salt 不同，所以按 (主密码, salt) 缓存推导出的密钥；只在内存里，上锁时清空
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, SecretKeySpec>()

    fun clearKeyCache() { keyCache.clear(); dataKey = null }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8) + salt)
        val cacheKey = Base64.encodeToString(id, Base64.NO_WRAP)
        return keyCache.getOrPut(cacheKey) { deriveKeySlow(password, salt) }
    }

    private fun deriveKeySlow(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}