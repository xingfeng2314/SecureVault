package com.example.securevault.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 指纹解锁：Keystore 里生成一把必须指纹验证才能用的 AES 密钥，
 * 用它加密主密码保存；验证通过后通过 CryptoObject 解开。
 * 指纹只负责“开锁”，不直接充当密钥。新录入指纹后密钥自动失效。
 */
object KeystoreBiometricHelper {
    private const val KEY_ALIAS = "securevault_biometric_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isBiometricAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        try { keyStore().deleteEntry(KEY_ALIAS) } catch (_: Exception) {}
    }

    /** 启用指纹：验证指纹后把主密码加密保存 */
    fun enable(activity: FragmentActivity, masterPassword: String, onResult: (Boolean, String?) -> Unit) {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        } catch (e: Exception) {
            onResult(false, e.message); return
        }
        prompt(activity, cipher, "启用指纹解锁", "验证指纹后，下次可以直接用指纹解锁", onResult) { c ->
            val encrypted = c.doFinal(masterPassword.toByteArray(Charsets.UTF_8))
            SecurityManager.saveBiometricData(activity, encrypted, c.iv)
            onResult(true, null)
        }
    }

    /** 指纹解锁：成功后回调解出的主密码 */
    fun unlock(activity: FragmentActivity, onPassword: (String) -> Unit, onError: (String?) -> Unit) {
        val (encrypted, iv) = SecurityManager.getBiometricData(activity) ?: run { onError(null); return }
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            SecurityManager.clearBiometricData(activity)
            onError("检测到指纹有变动，请用主密码解锁后重新启用指纹")
            return
        } catch (e: Exception) {
            onError(e.message); return
        }
        prompt(activity, cipher, "解锁 SecureVault", "验证指纹以解锁", { _, msg -> onError(msg) }) { c ->
            onPassword(String(c.doFinal(encrypted), Charsets.UTF_8))
        }
    }

    private fun prompt(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        onFail: (Boolean, String?) -> Unit,
        onCipher: (Cipher) -> Unit
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher ?: run { onFail(false, "验证失败"); return }
                try { onCipher(c) } catch (e: Exception) { onFail(false, e.message) }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onFail(false, if (cancelled) null else errString.toString())
            }
            // onAuthenticationFailed：单次没认出来，系统弹窗会让用户重试，这里不用处理
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("使用主密码")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}