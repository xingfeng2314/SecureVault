package com.example.securevault.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String,               // bank_card, id_card, driver_license, other
    val title: String,              // 显示名称，如“招商银行储蓄卡”
    val number: String = "",        // 卡号 / 证件号（加密后存储）
    val expiry: String = "",        // 有效期
    val cvv: String = "",           // CVV（加密）
    val notes: String = "",
    val encryptedImages: String = "", // 加密后的图片路径列表，逗号分隔
    val createdAt: Long = System.currentTimeMillis()
)

enum class DocumentType(val label: String) {
    BANK_CARD("银行卡"),
    ID_CARD("身份证"),
    DRIVER_LICENSE("驾驶证"),
    OTHER("其他证件")
}
