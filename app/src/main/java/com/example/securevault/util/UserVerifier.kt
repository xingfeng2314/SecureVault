package com.example.securevault.util

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 查看/复制密码前的二次验证。
 * 验证通过后 60 秒内不再重复询问，免得连续复制几条要按好几次指纹。
 * 指纹不可用或用户选“使用主密码”时走 onFallback，由界面弹主密码输入框。
 */
object UserVerifier {
    private const val GRACE_MS = 60_000L
    private var lastOk = 0L

    private const val PREF = "ui_hints"
    private const val KEY = "verify_before_view"
    fun isEnabled(context: android.content.Context) =
        context.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(context: android.content.Context, on: Boolean) =
        context.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    fun markVerified() { lastOk = SystemClock.elapsedRealtime() }
    fun reset() { lastOk = 0L }

    fun verify(activity: FragmentActivity, reason: String, onOk: () -> Unit, onFallback: () -> Unit) {
        if (lastOk != 0L && SystemClock.elapsedRealtime() - lastOk < GRACE_MS) { onOk(); return }
        if (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            onFallback(); return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    markVerified(); onOk()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_LOCKOUT ||
                        code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) onFallback()
                    // 用户取消：什么都不做
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("验证身份")
                .setSubtitle(reason)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("使用主密码")
                .build()
        )
    }
}