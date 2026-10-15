package com.example.securevault.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object SecurityManager {
    private const val PREFS_NAME = "securevault_prefs"
    private const val KEY_MASTER_HASH = "master_hash"
    private const val KEY_SALT = "master_salt"
    private const val KEY_BIO_CIPHER = "bio_cipher"
    private const val KEY_BIO_IV = "bio_iv"
    private const val KEY_BIO_DECLINED = "bio_declined"

    // EncryptedSharedPreferences 创建很慢（要访问 Keystore），只创建一次
    @Volatile private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { prefs = it }
        }

    fun hasMasterPassword(context: Context): Boolean = getPrefs(context).contains(KEY_MASTER_HASH)

    /** 耗时操作（PBKDF2），请在后台线程调用 */
    fun setMasterPassword(context: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = CryptoUtils.hashPassword(password, salt)
        getPrefs(context).edit()
            .putString(KEY_MASTER_HASH, hash)
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
    }

    /** 耗时操作（PBKDF2），请在后台线程调用 */
    fun verifyMasterPassword(context: Context, password: String): Boolean {
        val p = getPrefs(context)
        val storedHash = p.getString(KEY_MASTER_HASH, null) ?: return false
        val saltB64 = p.getString(KEY_SALT, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        return CryptoUtils.hashPassword(password, salt) == storedHash
    }

    // ---- 指纹解锁：保存被 Keystore 指纹密钥加密后的主密码 ----
    fun saveBiometricData(context: Context, cipherText: ByteArray, iv: ByteArray) {
        getPrefs(context).edit()
            .putString(KEY_BIO_CIPHER, Base64.encodeToString(cipherText, Base64.NO_WRAP))
            .putString(KEY_BIO_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putBoolean(KEY_BIO_DECLINED, false)
            .apply()
    }

    fun getBiometricData(context: Context): Pair<ByteArray, ByteArray>? {
        val p = getPrefs(context)
        val c = p.getString(KEY_BIO_CIPHER, null) ?: return null
        val iv = p.getString(KEY_BIO_IV, null) ?: return null
        return Base64.decode(c, Base64.NO_WRAP) to Base64.decode(iv, Base64.NO_WRAP)
    }

    fun isBiometricEnabled(context: Context) = getBiometricData(context) != null

    fun clearBiometricData(context: Context) {
        getPrefs(context).edit().remove(KEY_BIO_CIPHER).remove(KEY_BIO_IV).apply()
    }

    fun isBiometricDeclined(context: Context) = getPrefs(context).getBoolean(KEY_BIO_DECLINED, false)

    fun setBiometricDeclined(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_BIO_DECLINED, true).apply()
    }
}