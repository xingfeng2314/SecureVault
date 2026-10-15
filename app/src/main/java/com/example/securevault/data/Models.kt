package com.example.securevault.data

import java.util.UUID

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val title: String,           // 显示名称，如 "Gmail" 或 "守望先锋"
    val emailOrUsername: String,
    val encryptedPassword: String, // AES 加密后的密码
    val notes: String = "",
    val tags: List<String> = emptyList(), // 多标签，如 ["游戏", "Blizzard"]
    val createdAt: Long = System.currentTimeMillis()
)

data class Tag(
    val name: String,
    val colorHex: String = "#64FFDA"
)
