package com.example.securevault.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val emailOrUsername: String,
    val encryptedPassword: String,
    val notes: String = "",
    val tags: String = "", // 逗号分隔存储
    val createdAt: Long = System.currentTimeMillis()
)

fun AccountEntity.toAccount(): Account = Account(
    id = id,
    title = title,
    emailOrUsername = emailOrUsername,
    encryptedPassword = encryptedPassword,
    notes = notes,
    tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
    createdAt = createdAt
)

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    title = title,
    emailOrUsername = emailOrUsername,
    encryptedPassword = encryptedPassword,
    notes = notes,
    tags = tags.joinToString(","),
    createdAt = createdAt
)
