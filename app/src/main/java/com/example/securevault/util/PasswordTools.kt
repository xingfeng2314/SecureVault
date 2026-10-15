package com.example.securevault.util

import java.security.SecureRandom

object PasswordTools {
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"     // 去掉 l
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"      // 去掉 I、O
    private const val DIGITS = "23456789"                      // 去掉 0、1
    private const val SYMBOLS = "!@#$%^&*-_=+?"
    private val random = SecureRandom()

    fun generate(length: Int, upper: Boolean, digits: Boolean, symbols: Boolean): String {
        val sets = buildList {
            add(LOWER); if (upper) add(UPPER); if (digits) add(DIGITS); if (symbols) add(SYMBOLS)
        }
        val all = sets.joinToString("")
        // 每类至少一个，剩下随机，最后打乱
        val chars = sets.map { it[random.nextInt(it.length)] }.toMutableList()
        while (chars.size < length) chars += all[random.nextInt(all.length)]
        for (i in chars.indices.reversed()) { val j = random.nextInt(i + 1); val t = chars[i]; chars[i] = chars[j]; chars[j] = t }
        return chars.joinToString("")
    }

    enum class Strength(val label: String, val level: Int) { EMPTY("", 0), WEAK("弱", 1), FAIR("一般", 2), GOOD("强", 3), STRONG("很强", 4) }

    /** 简单估算：字符种类 × 长度，再对常见弱模式扣分 */
    fun strength(pwd: String): Strength {
        if (pwd.isEmpty()) return Strength.EMPTY
        var pool = 0
        if (pwd.any { it.isLowerCase() }) pool += 26
        if (pwd.any { it.isUpperCase() }) pool += 26
        if (pwd.any { it.isDigit() }) pool += 10
        if (pwd.any { !it.isLetterOrDigit() }) pool += 20
        var bits = pwd.length * (Math.log(pool.coerceAtLeast(1).toDouble()) / Math.log(2.0))
        val lower = pwd.lowercase()
        if (pwd.toSet().size <= 2) bits = minOf(bits, 10.0)
        if (COMMON.any { lower.contains(it) }) bits -= 20
        if (Regex("(012|123|234|345|456|567|678|789|abc|qwe|asd)").containsMatchIn(lower)) bits -= 10
        return when {
            bits < 40 -> Strength.WEAK
            bits < 60 -> Strength.FAIR
            bits < 80 -> Strength.GOOD
            else -> Strength.STRONG
        }
    }

    private val COMMON = listOf("password", "qwerty", "123456", "111111", "abc123", "iloveyou", "admin", "woaini", "666666", "888888")
}