package com.example.securevault.util

import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Google Drive 自动备份。只申请 drive.file 权限：App 只能看到自己上传的文件。
 * Android 的 OAuth 客户端靠“包名 + 签名 SHA-1”识别，代码里不需要写客户端 ID。
 */
object DriveBackup {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val KEEP = 3   // 轮换保留最近 3 份，防止坏数据把好备份全覆盖
    private const val WORK = "drive_auto_backup"
    private const val PREF = "drive_backup"

    fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    // ---------- 状态（给设置页和首页提示用） ----------
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun lastSuccess(c: Context) = prefs(c).getLong("last_ok", 0L)
    fun lastError(c: Context): String? = prefs(c).getString("last_error", null)
    fun needsReauth(c: Context) = prefs(c).getBoolean("needs_reauth", false)
    private fun recordOk(c: Context) = prefs(c).edit().putLong("last_ok", System.currentTimeMillis())
        .remove("last_error").putBoolean("needs_reauth", false).apply()
    private fun recordError(c: Context, msg: String, reauth: Boolean = false) =
        prefs(c).edit().putString("last_error", msg).putBoolean("needs_reauth", reauth).apply()
    fun clearReauth(c: Context) = prefs(c).edit().putBoolean("needs_reauth", false).remove("last_error").apply()

    fun enable(c: Context, dek: javax.crypto.spec.SecretKeySpec) {
        BackgroundKey.save(c, dek)
        prefs(c).edit().putBoolean("enabled", true).apply()
        val req = PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun disable(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        BackgroundKey.delete(c)
        prefs(c).edit().putBoolean("enabled", false).apply()
    }

    fun backupNow(c: Context) {
        val req = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, req)
    }

    // ---------- 一次完整的备份 + 上传（在后台线程执行） ----------
    fun runOnce(c: Context) {
        val dek = BackgroundKey.load(c) ?: throw DriveException("后台密钥丢失，请在设置里关掉再打开自动备份", reauth = false)
        val result: AuthorizationResult = try {
            Tasks.await(Identity.getAuthorizationClient(c).authorize(authRequest()), 60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw DriveException("连接 Google 失败：${e.message ?: "网络不通"}")
        }
        val token = result.accessToken
        if (result.hasResolution() || token == null) throw DriveException("Google 授权已失效，请打开 App 重新登录", reauth = true)

        val tmp = File(c.cacheDir, "drive_upload.svbak")
        try {
            tmp.outputStream().use { BackupManager.exportWithDataKey(c, dek, it) }
            val (existing, name) = pickTarget(token)
            upload(token, tmp, existing, name)
        } finally {
            tmp.delete()
        }
    }

    internal fun runAndRecord(c: Context): Boolean = try {
        runOnce(c); recordOk(c); true
    } catch (e: DriveException) {
        recordError(c, e.message ?: "备份失败", e.reauth); false
    } catch (e: Exception) {
        recordError(c, "备份失败：${e.message}"); false
    }

    /** 不满 3 份就新建下一个编号，满了就覆盖最旧的那份 */
    private fun pickTarget(token: String): Pair<String?, String> {
        val q = java.net.URLEncoder.encode("name contains 'SecureVault_auto_' and trashed = false", "UTF-8")
        val conn = open("https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive&orderBy=modifiedTime&fields=files(id,name,modifiedTime)", "GET", token)
        val files = JSONObject(readOrThrow(conn)).optJSONArray("files")
        val names = (0 until (files?.length() ?: 0)).map { files!!.getJSONObject(it).getString("name") }.toSet()
        for (i in 1..KEEP) {
            val n = "SecureVault_auto_$i.svbak"
            if (n !in names) return null to n
        }
        val oldest = files!!.getJSONObject(0)   // orderBy=modifiedTime 升序，第一个最旧
        return oldest.getString("id") to oldest.getString("name")
    }

    /** 可续传上传：先拿上传地址，再把文件流式 PUT 上去，大文件也不占内存 */
    private fun upload(token: String, file: File, existingId: String?, name: String) {
        val (url, method) = if (existingId == null)
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable" to "POST"
        else "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=resumable" to "PATCH"
        val init = open(url, method, token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
        }
        val meta = JSONObject().apply { if (existingId == null) put("name", name) }.toString()
        init.outputStream.use { it.write(meta.toByteArray()) }
        if (init.responseCode !in 200..299) readOrThrow(init)
        val location = init.getHeaderField("Location") ?: throw DriveException("Drive 没有返回上传地址")
        init.disconnect()

        val put = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 30_000; readTimeout = 120_000
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        file.inputStream().use { input -> put.outputStream.use { input.copyTo(it) } }
        readOrThrow(put)
    }

    // HttpURLConnection 不支持 PATCH，用 POST + X-HTTP-Method-Override
    private fun open(url: String, method: String, token: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 30_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun readOrThrow(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return when {
            code in 200..299 -> text
            code == 401 -> throw DriveException("Google 授权已失效，请打开 App 重新登录", reauth = true)
            code == 403 && text.contains("accessNotConfigured") -> throw DriveException("Drive API 没有启用，请到 Google Cloud 控制台启用")
            code == 403 && text.contains("storageQuotaExceeded") -> throw DriveException("Google Drive 空间已满")
            else -> throw IOException("Drive 返回 $code")
        }
    }
}

class DriveException(message: String, val reauth: Boolean = false) : Exception(message)

class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!DriveBackup.isEnabled(applicationContext)) return Result.success()
        val ok = DriveBackup.runAndRecord(applicationContext)
        return when {
            ok -> Result.success()
            DriveBackup.needsReauth(applicationContext) -> Result.failure()   // 要用户处理，不白白重试
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}