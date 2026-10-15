package com.example.securevault.data

import android.content.Context
import com.example.securevault.util.CryptoUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 简单的本地文件存储（JSON）。
 * 生产环境建议换成 Room + SQLCipher。
 * 这里为了二次开发友好和减少依赖，先用文件。
 */
class AccountRepository(private val context: Context) {
    private val file = File(context.filesDir, "accounts.json")
    private var masterPassword: String? = null

    fun setMasterPassword(password: String) {
        masterPassword = password
    }

    fun isUnlocked() = masterPassword != null

    fun getAll(): List<Account> {
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            parseAccounts(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAll(accounts: List<Account>) {
        val json = accountsToJson(accounts)
        // 先写临时文件并读回校验，再原子改名替换；中途被杀掉也不会留下半截文件
        val tmp = File(file.parentFile, "accounts.json.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        org.json.JSONArray(tmp.readText())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("保存账号失败")
        }
    }

    fun add(account: Account) {
        val list = getAll().toMutableList()
        list.add(account)
        saveAll(list)
    }

    fun update(account: Account) {
        val list = getAll().map { if (it.id == account.id) account else it }
        saveAll(list)
    }

    fun delete(id: String) {
        val list = getAll().filter { it.id != id }
        saveAll(list)
    }

    fun getAllTags(): List<String> {
        return getAll().flatMap { it.tags }.distinct().sorted()
    }

    private fun accountsToJson(accounts: List<Account>): String {
        val array = JSONArray()
        accounts.forEach { acc ->
            val obj = JSONObject().apply {
                put("id", acc.id)
                put("title", acc.title)
                put("emailOrUsername", acc.emailOrUsername)
                put("encryptedPassword", acc.encryptedPassword)
                put("notes", acc.notes)
                put("tags", JSONArray(acc.tags))
                put("createdAt", acc.createdAt)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseAccounts(json: String): List<Account> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val tagsArray = obj.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagsArray.length()).map { tagsArray.getString(it) }
            Account(
                id = obj.getString("id"),
                title = obj.getString("title"),
                emailOrUsername = obj.getString("emailOrUsername"),
                encryptedPassword = obj.getString("encryptedPassword"),
                notes = obj.optString("notes", ""),
                tags = tags,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
