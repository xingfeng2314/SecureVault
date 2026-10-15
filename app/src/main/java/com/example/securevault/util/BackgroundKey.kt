package com.example.securevault.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 只在开启自动备份时存在：数据密钥再用 Keystore 里一把不需要指纹的密钥包一份，
 * 让后台 WorkManager 在 App 锁着时也能打包加密备份。关闭自动备份时删除。
 * 这份副本绑定本机，换手机无效；恢复永远只靠主密码。
 */
object BackgroundKey {
    private const val ALIAS = "sv_background_backup"
    private fun file(context: Context) = File(context.filesDir, "dek_bg.bin")

    fun exists(context: Context) = file(context).exists()

    fun save(context: Context, dek: SecretKeySpec) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(dek.encoded)
        file(context).writeBytes(cipher.iv + ct)
    }

    fun load(context: Context): SecretKeySpec? = runCatching {
        val data = file(context).readBytes()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        SecretKeySpec(cipher.doFinal(data, 12, data.size - 12), "AES")
    }.getOrNull()

    fun delete(context: Context) {
        file(context).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}