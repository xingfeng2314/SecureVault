package com.example.securevault.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * 复制敏感内容：打上“敏感”标记（Android 13 起系统弹窗不预览内容，
 * 部分输入法也不会记进剪贴板历史），并在 30 秒后自动清空。
 */
object ClipboardHelper {
    private const val LABEL = "SecureVault"
    const val CLEAR_DELAY_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun copySensitive(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)

        pending?.let { handler.removeCallbacks(it) }
        val appContext = context.applicationContext
        pending = Runnable { clearIfOurs(appContext) }.also { handler.postDelayed(it, CLEAR_DELAY_MS) }
    }

    /** 只清掉我们自己复制的内容；读不到标签时（应用在后台）也清掉，宁可多清 */
    private fun clearIfOurs(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = try { cm.primaryClipDescription?.label?.toString() } catch (e: Exception) { null }
        if (label == null || label == LABEL) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        pending = null
    }

    /** 上锁时调用：立刻清空 */
    fun clearNow(context: Context) {
        if (pending != null) {
            pending?.let { handler.removeCallbacks(it) }
            clearIfOurs(context.applicationContext)
        }
    }
}