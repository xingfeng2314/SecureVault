package com.example.securevault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.securevault.util.CryptoUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class VaultKind { PHOTO, DOCUMENT }

data class VaultItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val size: Long,
    val kind: VaultKind,
    val tags: List<String> = emptyList(),
    val version: Int = 2,           // 1 = 整体加密（旧），2 = 分块加密
    val createdAt: Long = System.currentTimeMillis()
) {
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
}

/**
 * 照片和文件保险箱。
 * - 解锁时用主密码解开一把随机生成的 256 位数据密钥（只做一次 PBKDF2）。
 * - 每个文件用数据密钥做 AES-256-GCM 加密，每个文件单独随机 IV，存在应用私有目录 files/vault/。
 * - 文件名、类型等目录信息也整体加密存在 index.enc 里。
 * 以后做备份时，把 files/vault/ 整个目录打包即可。
 */
class VaultStore private constructor(private val context: Context, private val key: SecretKeySpec) {
    private val dir = File(context.filesDir, VAULT_DIR).apply { mkdirs() }
    private val indexFile = File(dir, "index.enc")
    private val random = SecureRandom()

    // ---------- 目录 ----------
    @Synchronized
    fun list(): List<VaultItem> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = String(decrypt(indexFile.readBytes()), Charsets.UTF_8)
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VaultItem(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    mime = o.getString("mime"),
                    size = o.optLong("size"),
                    kind = VaultKind.valueOf(o.getString("kind")),
                    tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList(),
                    version = o.optInt("v", 1),
                    createdAt = o.optLong("createdAt")
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveIndex(items: List<VaultItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("mime", it.mime)
                put("size", it.size); put("kind", it.kind.name); put("createdAt", it.createdAt)
                put("tags", JSONArray(it.tags)); put("v", it.version)
            })
        }
        val tmp = File(dir, "index.enc.tmp")
        tmp.writeBytes(encrypt(arr.toString().toByteArray(Charsets.UTF_8)))
        tmp.renameTo(indexFile)
    }

    // ---------- 导入 / 读取 / 删除 ----------
    /** 耗时操作，请在后台线程调用 */
    fun import(uri: Uri, kind: VaultKind): VaultItem {
        val resolver = context.contentResolver
        var name = "file"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val item = VaultItem(name = name, mime = mime, size = size, kind = kind)

        val out = File(dir, "${item.id}.bin")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).buffered().use { fos -> encryptChunked(input, fos) }
        } ?: error("无法读取所选文件")

        saveIndex(list() + item)
        return item
    }

    /** 解密到内存，只用于照片（缩略图和查看） */
    fun readBytes(item: VaultItem): ByteArray =
        ByteArrayOutputStream().also { decryptTo(item, it) }.toByteArray()

    /** 边读边解密，大文件也不会把整个文件读进内存 */
    fun decryptTo(item: VaultItem, out: OutputStream) {
        val f = File(dir, "${item.id}.bin")
        if (item.version == 1) {
            out.write(decrypt(f.readBytes()))
        } else {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { input -> decryptChunked(input, out) }
        }
        out.flush()
    }

    fun exportTo(item: VaultItem, out: OutputStream) = decryptTo(item, out)

    fun updateTags(id: String, tags: List<String>) {
        saveIndex(list().map { if (it.id == id) it.copy(tags = tags.distinct()) else it })
    }

    /** 只改目录里的名字，文件本身不用重新加密 */
    fun rename(id: String, newName: String) {
        saveIndex(list().map { if (it.id == id) it.copy(name = newName) else it })
    }

    fun setTags(ids: Collection<String>, tags: List<String>) {
        saveIndex(list().map { if (it.id in ids) it.copy(tags = tags.distinct()) else it })
    }

    /** PdfRenderer 需要一个可随机读取的文件，只能临时解密到应用私有缓存目录，看完就删 */
    fun decryptToTemp(item: VaultItem): File {
        val tmpDir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        return File(tmpDir, item.id).apply { FileOutputStream(this).buffered().use { decryptTo(item, it) } }
    }

    fun delete(item: VaultItem) {
        File(dir, "${item.id}.bin").delete()
        saveIndex(list().filter { it.id != item.id })
    }

    // ---------- 分块加密格式（v2） ----------
    // 每块：[IV 12 字节][密文长度 4 字节][密文+GCM 标签]。明文每块最多 1MB，每块单独随机 IV。
    // 附加数据(AAD) = 块序号 + 是否最后一块，防止块被调换顺序或截断。
    private fun aad(index: Long, last: Boolean) =
        ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    private fun encryptChunked(input: InputStream, output: OutputStream) {
        val out = DataOutputStream(output)
        val buf = ByteArray(CHUNK_SIZE)
        var index = 0L
        while (true) {
            var n = 0
            while (n < CHUNK_SIZE) {
                val r = input.read(buf, n, CHUNK_SIZE - n)
                if (r < 0) break
                n += r
            }
            val last = n < CHUNK_SIZE
            val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(index, last))
            }
            val ct = cipher.doFinal(buf, 0, n)
            out.write(iv); out.writeInt(ct.size); out.write(ct)
            index++
            if (last) break
        }
        out.flush()
    }

    private fun decryptChunked(input: DataInputStream, out: OutputStream) {
        var index = 0L
        while (true) {
            val iv = ByteArray(IV_LENGTH).also { input.readFully(it) }
            val len = input.readInt()
            require(len in TAG_BITS / 8..CHUNK_SIZE + TAG_BITS / 8) { "文件已损坏" }
            val ct = ByteArray(len).also { input.readFully(it) }
            // 先按“不是最后一块”验证，失败再按“最后一块”验证
            val plain = tryDecryptChunk(iv, ct, index, false)
                ?: tryDecryptChunk(iv, ct, index, true)?.also { out.write(it); return }
                ?: error("文件已损坏或被篡改")
            out.write(plain)
            index++
        }
    }

    private fun tryDecryptChunk(iv: ByteArray, ct: ByteArray, index: Long, last: Boolean): ByteArray? = try {
        Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(index, last))
        }.doFinal(ct)
    } catch (e: javax.crypto.AEADBadTagException) { null }

    // ---------- 小数据加解密（目录、旧格式） ----------
    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LENGTH)
        val cipher = Cipher.getInstance(ALGORITHM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH)
    }

    companion object {
        private const val VAULT_DIR = "vault"
        private const val TEMP_DIR = "vault_view"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val CHUNK_SIZE = 1024 * 1024

        /** 解锁后调用一次（耗时，后台线程）：取出或新建数据密钥 */
        fun open(context: Context, masterPassword: String): VaultStore {
            val dekFile = File(File(context.filesDir, VAULT_DIR).apply { mkdirs() }, "dek.enc")
            val dek = if (dekFile.exists()) {
                Base64.decode(CryptoUtils.decrypt(dekFile.readText(), masterPassword), Base64.NO_WRAP)
            } else {
                ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                    dekFile.writeText(CryptoUtils.encryptWithPassword(Base64.encodeToString(it, Base64.NO_WRAP), masterPassword))
                }
            }
            val key = SecretKeySpec(dek, "AES")
            CryptoUtils.dataKey = key   // 账号密码也用这把数据密钥
            // 开着自动备份时，刷新后台用的那份副本（恢复备份后数据密钥会变）
            if (com.example.securevault.util.DriveBackup.isEnabled(context)) runCatching { com.example.securevault.util.BackgroundKey.save(context, key) }
            return VaultStore(context.applicationContext, key)
        }

        /** 删除所有临时解密文件（离开预览页、上锁时调用） */
        fun clearTemp(context: Context) {
            File(context.cacheDir, TEMP_DIR).deleteRecursively()
        }
    }
}