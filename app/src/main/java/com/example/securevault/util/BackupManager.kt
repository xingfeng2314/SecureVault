package com.example.securevault.util

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * 加密备份（.svbak），参考 Aegis 的做法。
 * 文件 = [4 字节文件头长度][明文 JSON 文件头][分块密文]
 * - 文件头：magic、version、KDF 及参数、salt、创建时间、App 版本。
 * - 备份密钥 = PBKDF2(主密码, 每次新生成的 salt)。
 * - 内容 = zip(accounts.json + files/vault/)，按 1MB 分块 AES-256-GCM 加密，
 *   每块 AAD = SHA-256(文件头) + 块序号 + 是否最后一块，改动文件头或任意字节都会解密失败。
 * - Keystore 密钥、指纹数据、主密码哈希都不进备份（换手机就不能用）。
 */
object BackupManager {
    private const val MAGIC = "SVBK"
    private const val VERSION = 1
    private const val VERSION_AUTO = 2
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 210_000
    private const val CHUNK = 1024 * 1024
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ACCOUNTS = "accounts.json"
    private const val VAULT = "vault"

    // ------------------------- 导出 -------------------------
    fun export(context: Context, masterPassword: String, output: OutputStream) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val header = JSONObject().apply {
            put("magic", MAGIC); put("version", VERSION)
            put("kdf", KDF); put("iterations", ITERATIONS)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("createdAt", System.currentTimeMillis())
            put("appVersion", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
        }.toString().toByteArray(Charsets.UTF_8)

        val out = DataOutputStream(BufferedOutputStream(output))
        out.writeInt(header.size)
        out.write(header)
        val enc = EncryptStream(out, deriveKey(masterPassword, salt, ITERATIONS), sha256(header))
        ZipOutputStream(enc).use { zip ->
            val files = context.filesDir
            File(files, ACCOUNTS).takeIf { it.exists() }?.let { addFile(zip, it, ACCOUNTS) }
            File(files, VAULT).takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.endsWith(".tmp")) addFile(zip, f, "$VAULT/${f.name}")
            }
        }
    }

    /**
     * 第 2 版：后台用，不需要主密码。内容用数据密钥加密，文件头带上 dek.enc（已被主密码包好），
     * 所以换手机恢复时仍然只要主密码。
     */
    fun exportWithDataKey(context: Context, dek: SecretKeySpec, output: OutputStream) {
        val files = context.filesDir
        val wrapped = File(files, "$VAULT/dek.enc").readText()
        val header = JSONObject().apply {
            put("magic", MAGIC); put("version", VERSION_AUTO)
            put("wrappedKey", wrapped)
            put("createdAt", System.currentTimeMillis())
            put("appVersion", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
        }.toString().toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(BufferedOutputStream(output))
        out.writeInt(header.size)
        out.write(header)
        ZipOutputStream(EncryptStream(out, dek, sha256(header))).use { zip ->
            File(files, ACCOUNTS).takeIf { it.exists() }?.let { addFile(zip, it, ACCOUNTS) }
            File(files, VAULT).takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.endsWith(".tmp")) addFile(zip, f, "$VAULT/${f.name}")
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    // ------------------------- 恢复（覆盖） -------------------------
    /** 成功后数据已替换，主密码变为备份时的主密码，指纹需重新启用 */
    fun restore(context: Context, password: String, input: InputStream) {
        val files = context.filesDir
        val tmp = File(files, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val din = DataInputStream(BufferedInputStream(input))
            val headerLen = try { din.readInt() } catch (e: Exception) { throw BackupException("不是 SecureVault 备份文件") }
            if (headerLen !in 10..64 * 1024) throw BackupException("不是 SecureVault 备份文件")
            val header = ByteArray(headerLen).also { din.readFully(it) }
            val json = try { JSONObject(String(header, Charsets.UTF_8)) } catch (e: Exception) { throw BackupException("不是 SecureVault 备份文件") }
            if (json.optString("magic") != MAGIC) throw BackupException("不是 SecureVault 备份文件")
            val version = json.optInt("version")
            if (version > VERSION_AUTO) throw BackupException("备份来自更新版本的 App，请先升级")
            val key = if (version >= VERSION_AUTO) {
                // 第 2 版（自动备份）：文件头里带着用主密码包好的数据密钥
                try {
                    SecretKeySpec(Base64.decode(CryptoUtils.decrypt(json.getString("wrappedKey"), password), Base64.NO_WRAP), "AES")
                } catch (e: Exception) { throw BackupException("密码错误或文件已损坏") }
            } else {
                if (json.optString("kdf") != KDF) throw BackupException("不支持的密钥算法")
                val salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP)
                deriveKey(password, salt, json.getInt("iterations"))
            }

            // 1. 解密并解压到临时目录
            val decrypting = DecryptStream(din, key, sha256(header))
            ZipInputStream(decrypting).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(tmp, entry.name)
                    // 防止 ../ 路径穿越
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw BackupException("文件已损坏")
                    if (!entry.isDirectory) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
                // 读到最后一块为止，确认文件没有被截断
                val sink = ByteArray(8192)
                while (decrypting.read(sink, 0, sink.size) >= 0) { }
                if (!decrypting.isComplete) throw BackupException("文件不完整或已损坏")
            }

            // 2. 校验内容
            File(tmp, ACCOUNTS).takeIf { it.exists() }?.let { JSONArray(it.readText()) }
            File(tmp, "$VAULT/dek.enc").takeIf { it.exists() }?.let {
                try { CryptoUtils.decrypt(it.readText(), password) } catch (e: Exception) { throw BackupException("备份内容校验失败") }
            }

            // 3. 替换：先把现有数据改名留底，失败就回退
            val oldAccounts = File(files, "$ACCOUNTS.old"); val oldVault = File(files, "$VAULT.old")
            oldAccounts.delete(); oldVault.deleteRecursively()
            val curAccounts = File(files, ACCOUNTS); val curVault = File(files, VAULT)
            if (curAccounts.exists()) curAccounts.renameTo(oldAccounts)
            if (curVault.exists()) curVault.renameTo(oldVault)
            try {
                File(tmp, ACCOUNTS).takeIf { it.exists() }?.let { if (!it.renameTo(curAccounts)) throw IOException("move accounts") }
                File(tmp, VAULT).takeIf { it.exists() }?.let { if (!it.renameTo(curVault)) throw IOException("move vault") }
            } catch (e: Exception) {
                curAccounts.delete(); curVault.deleteRecursively()
                oldAccounts.renameTo(curAccounts); oldVault.renameTo(curVault)
                throw BackupException("恢复失败，原有数据已还原")
            }
            oldAccounts.delete(); oldVault.deleteRecursively()

            // 4. 主密码改成备份时的密码；指纹密钥换了数据就失效，需要重新启用
            SecurityManager.setMasterPassword(context, password)
            SecurityManager.clearBiometricData(context)
            File(files, "dek_bg.bin").delete()   // 旧数据密钥的后台副本作废，下次解锁重建
        } catch (e: BackupException) {
            throw e
        } catch (e: AEADBadTagException) {
            throw BackupException("密码错误或文件已损坏")
        } catch (e: EOFException) {
            throw BackupException("文件不完整或已损坏")
        } catch (e: Exception) {
            throw BackupException("恢复失败：${e.message}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------- 工具 -------------------------
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun aad(headerHash: ByteArray, index: Long, last: Boolean) =
        ByteBuffer.allocate(headerHash.size + 9).put(headerHash).putLong(index).put(if (last) 1 else 0).array()

    /** 写入端：攒满 1MB 加密一块，close 时写最后一块 */
    private class EncryptStream(
        private val out: DataOutputStream,
        private val key: SecretKeySpec,
        private val headerHash: ByteArray
    ) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var index = 0L
        private val random = SecureRandom()
        private var closed = false

        override fun write(b: Int) { if (n == CHUNK) flushChunk(false); buf[n++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                if (n == CHUNK) flushChunk(false)
                val c = minOf(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, c); n += c; o += c; l -= c
            }
        }

        private fun flushChunk(last: Boolean) {
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(headerHash, index, last))
            }
            val ct = cipher.doFinal(buf, 0, n)
            out.write(iv); out.writeInt(ct.size); out.write(ct)
            index++; n = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            flushChunk(true)
            out.flush()
            out.close()
        }
    }

    /** 读取端：一块一块解密，必须读到“最后一块”才算完整 */
    private class DecryptStream(
        private val input: DataInputStream,
        private val key: SecretKeySpec,
        private val headerHash: ByteArray
    ) : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var done = false
        val isComplete get() = done

        private fun nextChunk(): Boolean {
            if (done) return false
            val iv = ByteArray(IV_LEN).also { input.readFully(it) }
            val len = input.readInt()
            if (len !in TAG_BITS / 8..CHUNK + TAG_BITS / 8) throw BackupException("文件已损坏")
            val ct = ByteArray(len).also { input.readFully(it) }
            val plain = tryDecrypt(iv, ct, false) ?: tryDecrypt(iv, ct, true)?.also { done = true }
                ?: if (index == 0L) throw AEADBadTagException() else throw BackupException("文件已损坏或被篡改")
            buf = plain; pos = 0; index++
            return true
        }

        private fun tryDecrypt(iv: ByteArray, ct: ByteArray, last: Boolean): ByteArray? = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(headerHash, index, last))
            }.doFinal(ct)
        } catch (e: AEADBadTagException) { null }

        override fun read(): Int {
            while (pos >= buf.size) if (!nextChunk()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) if (!nextChunk()) return -1
            val c = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, c); pos += c
            return c
        }
    }
}