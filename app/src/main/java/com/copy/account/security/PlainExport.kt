/**
 * 职责：明文导出——把密码库原样序列化成自描述 JSON，一个字节都不加密，供迁移、打印、离线查阅。
 *       与 AccCodec 的唯一区别就是那层加密：AccCodec 用主密码派生的 KEK 包 AES-GCM，这里没有 KEK。
 * 架构位置：AccountApp 的 onExportPlaintext 回调调用；产出的字节交给 data/backup 双轨写盘。
 *           复用 data/model 的 vaultJson 与 PersistedVault，故本文件不碰 javax.crypto。
 * Python 类比：≈ json.dumps(vault, ensure_ascii=False)，外面再包一层带 format/warning 的信封。
 * 安全边界：产物含全部密码、TOTP 密钥与隐藏自定义字段，任何人拿到即可登录所有账号。
 *           文件名以 account-plain- 开头、JSON 内嵌 warning 字段，两处标记便于日后辨认并及时删除。
 *           本文件不设门禁——由 AccountApp 决定谁可以调用（当前是「已解锁即可」）。
 */
package com.copy.account.security

import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.vaultJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 写进每份明文导出里的固定警告语；随文件走，脱离本应用后也能看出这份东西是什么。 */
internal const val PLAIN_EXPORT_WARNING =
    "本文件为明文，含全部密码与两步验证密钥，无加密保护，请勿分享或上传。"

/**
 * 明文导出的信封。刻意在 vault 外再包一层：format 标识种类、warning 自我说明。
 * 半年后在存储里偶然翻到这份文件时，一眼就能认出它不是加密备份——裸 PersistedVault 做不到这点。
 *
 * 声明为 internal 而非 private：单测要反序列化它来断言 format/warning/往返一致性，
 * 做成 private 就只能退回字符串 contains 断言，那样断言会随字段改名一起失效。
 */
@Serializable
internal data class PlainExportDocument(
    val format: String = "account-plaintext-export",
    val formatVersion: Int = 1,
    val exportedAt: String,
    val accountCount: Int,
    val groupCount: Int,
    val warning: String = PLAIN_EXPORT_WARNING,
    val vault: PersistedVault
)

/**
 * 生成明文 JSON 字节。
 * @param vault 密码库全量内容，含密码、TOTP 密钥与隐藏自定义字段
 * @param exportedAt 导出时间字符串，由调用方传入以保持本函数可单测（无 Android 依赖、无隐式时钟）
 */
internal fun exportPlainJson(vault: PersistedVault, exportedAt: String): ByteArray = vaultJson.encodeToString(
    PlainExportDocument.serializer(),
    PlainExportDocument(
        exportedAt = exportedAt,
        accountCount = vault.accounts.size,
        groupCount = vault.groups.size,
        vault = vault
    )
).toByteArray(Charsets.UTF_8)
