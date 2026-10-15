package com.example.securevault.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pwned Passwords 泄露检查（免费，无需 API Key）。
 * 只把密码 SHA-1 的前 5 位发给服务器，比对在本地完成，密码本身不会离开手机。
 * 文档：https://haveibeenpwned.com/API/v3#PwnedPasswords
 */
object HibpClient {
    suspend fun checkPassword(password: String): PwnedResult = withContext(Dispatchers.IO) {
        try {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it) }
            val prefix = sha1.substring(0, 5)
            val suffix = sha1.substring(5)

            val connection = URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Add-Padding", "true")
            connection.setRequestProperty("User-Agent", "SecureVault-Android")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode != 200) {
                return@withContext PwnedResult.Error("HTTP ${connection.responseCode}")
            }
            val count = connection.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split(":")
                    if (parts.size == 2 && parts[0].equals(suffix, ignoreCase = true)) parts[1].toIntOrNull() else null
                }.firstOrNull()
            } ?: 0
            // 次数为 0 的是 Add-Padding 混进来的假条目
            if (count > 0) PwnedResult.Pwned(count) else PwnedResult.Clean
        } catch (e: Exception) {
            PwnedResult.Error(e.message ?: "网络错误")
        }
    }
}

sealed class PwnedResult {
    object Clean : PwnedResult()
    data class Pwned(val count: Int) : PwnedResult()
    data class Error(val message: String) : PwnedResult()
}