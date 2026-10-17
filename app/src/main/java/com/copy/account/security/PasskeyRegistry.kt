/**
 * 职责：通行密钥设备本地注册表——本机已注册凭据的元数据清单（credentialId/rpId/userHandle/
 *       accountId 关联/signCount），纯沙箱内 JSON 文件（filesDir/passkey_registry.json）。
 *       刻意不进密码库（vault.bin）与 .acc 备份：密钥私钥随账号段进库（Account.passkeys），
 *       本表只是出卡索引缓存——vault 数据是其唯一权威源（导入/恢复后 rebuildFromVault 重建）；
 *       锁定态出卡的 Service 无 DEK 读不了 vault，靠本表明文查询。
 * 架构位置：PasskeyCreateActivity 写入、PasskeyProviderService 查询出卡、AccountDetailScreen
 *           弱关联展示（accountId 失效不出卡）、编辑页删除时同步删 vault 账号段密钥。
 * Python 类比：≈ 一个手写的 JSON 持久化列表 + 按 credentialId 的主键索引；
 *           SecureRandom 32 字节 ≈ secrets.token_bytes(32)，碰撞概率宇宙级忽略。
 */
package com.copy.account.security

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** 单条通行密钥注册记录。 */
@Serializable
internal data class PasskeyRegistryEntry(
    /** 网站凭据编号：SecureRandom 32 字节（本 App 生成，非网站下发），Base64URL 编码存储。 */
    val credentialId: String,
    /** 依赖方域名（rpId），如 "github.com"。 */
    val rpId: String,
    /** 网站用户标识（user.id），Base64URL。 */
    val userHandle: String,
    /** 用户名（user.name，如邮箱）：系统出卡显示用；旧条目可为空（显示退化用 rpId）。 */
    val userName: String = "",
    /** 弱关联的库内账号 id（显示用；账号删除后本条不出卡，服务器侧不受影响）。 */
    val accountId: String,
    /** 签名计数器：每次断言签名 +1（单设备独占，正常递增）。 */
    val signCount: Long = 0,
    /** 绑定时间（epoch 毫秒）。 */
    val createdAt: Long
)

/** 注册表读写（文件级单例由调用方持有 context 即可，无状态对象方法集）。 */
internal class PasskeyRegistry(context: Context) {

    private val file: File = File(context.filesDir, "passkey_registry.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 全部条目（文件缺失/损坏返回空表——注册表无敏感数据，损坏即重来，不阻塞登录）。 */
    fun load(): List<PasskeyRegistryEntry> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(ListSerializer(PasskeyRegistryEntry.serializer()), file.readText())
    }.getOrDefault(emptyList())

    /** 原子写入（临时文件 + rename，照 vault.bin 的落盘纪律）。 */
    private fun save(entries: List<PasskeyRegistryEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(PasskeyRegistryEntry.serializer()), entries))
        if (!tmp.renameTo(file)) {
            // rename 跨文件系统失败兜底：直接写（注册表非金融级数据，可接受）
            file.writeText(json.encodeToString(ListSerializer(PasskeyRegistryEntry.serializer()), entries))
            tmp.delete()
        }
    }

    /** 新增（注册完成时调用；credentialId 主键防重复）。 */
    fun add(entry: PasskeyRegistryEntry) {
        save(load().filterNot { it.credentialId == entry.credentialId } + entry)
    }

    /** 按 rpId 列出（登录出卡时按网站过滤）。 */
    fun byRpId(rpId: String): List<PasskeyRegistryEntry> = load().filter { it.rpId == rpId }

    /** 按 accountId 列出（账号详情/编辑页展示与删除）。 */
    fun byAccountId(accountId: String): List<PasskeyRegistryEntry> = load().filter { it.accountId == accountId }

    /** 按 credentialId 精确查（签名前定位 + signCount 更新）。 */
    fun find(credentialId: String): PasskeyRegistryEntry? = load().firstOrNull { it.credentialId == credentialId }

    /** 更新 signCount（断言签名后调用）。 */
    fun updateSignCount(credentialId: String, signCount: Long) {
        val entries = load()
        val updated = entries.firstOrNull { it.credentialId == credentialId } ?: return
        save(entries.filterNot { it.credentialId == credentialId } + updated.copy(signCount = signCount))
    }

    /** 删除单条（编辑页删除通行密钥时调用；vault 账号段密钥由调用方同步删）。 */
    fun remove(credentialId: String) {
        save(load().filterNot { it.credentialId == credentialId })
    }

    /** 删除账号的全部关联（账号删除时调用；孤儿条目清理）。 */
    fun removeAllForAccount(accountId: String) {
        save(load().filterNot { it.accountId == accountId })
    }

    /**
     * 从 vault 账号数据整表重建（导入 .acc/账号本子后调用）：registry 不进备份文件，
     * vault 的 Account.passkeys 是唯一权威源（含全部索引字段：credentialId/rpId/
     * userName/userHandle/signCount，accountId 即所在账号 id）。整表替换语义——
     * 导入以文件为准，旧表杂条一并清掉。
     */
    fun rebuildFromVault(accounts: List<com.copy.account.data.model.Account>) {
        save(accounts.flatMap { acc ->
            acc.passkeys.map { pk ->
                PasskeyRegistryEntry(
                    credentialId = pk.credentialId,
                    rpId = pk.rpId,
                    userHandle = pk.userHandle,
                    userName = pk.userName,
                    accountId = acc.id,
                    signCount = pk.signCount,
                    createdAt = pk.createdAt
                )
            }
        })
    }

    companion object {
        private val random = SecureRandom()

        /** 生成 32 字节 credentialId 并编码为 Base64URL（注册流程第一步）。 */
        fun newCredentialId(): String = base64Url(ByteArray(32).also { random.nextBytes(it) })

        /** 生成 32 字节 userHandle 的备用值（网站未提供 user.id 时的本地兜底）。 */
        fun newUserHandle(): String = base64Url(ByteArray(32).also { random.nextBytes(it) })
    }
}
