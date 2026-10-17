/**
 * 职责：通行密钥流程的库访问层——透明 Activity（系统拉起、App 主 UI 不在场）读写 vault.bin
 *       的唯一通道。解锁照 AutofillSelfHeal 先例：biometric CryptoObject 解出 DEK（锁定态
 *       也能走）；读取=整库 load + 按 rpId/credentialId 过滤；写入=整库 load → 改 →
 *       vaultStore.save 原子回写（先例：AppAutofillService 的保存链路）。
 * 架构位置：security/ 数据访问层；调用方是 PasskeyCreateActivity（写：私钥入账号段）与
 *           PasskeyGetActivity（读：私钥出，内存签名）。
 * 安全边界：DEK 只在两个 Activity 的签名/写库作用域内存活，用完即 fill(0)；明文账号
 *           不出作用域（Get 只取目标 passkey 字段，Create 只改目标账号一行）；不重开
 *           App UI 会话、不灌 AutofillBridge（service 上下文零 UI 态驻留）。
 * Python 类比：≈ 一个只暴露两个函数的数据访问模块——read_passkey(rpId, credentialId)
 *           与 save_passkey(accountId, passkey)；DEK 是局部变量，函数返回前清零。
 */
package com.copy.account.security

import android.content.Context
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountPasskey

/** 通行密钥的库读写（无状态对象集，每次调用独立解库——Activity 短生命周期天然限界）。 */
internal object PasskeyVaultAccess {

    /** 数据类：登录断言所需的最小私钥材料（不携出账号其余字段）。 */
    data class PasskeyMaterial(
        val credentialId: String,
        val rpId: String,
        val userName: String,
        val userHandle: String,
        val privateKeyPkcs8: String
    )

    /**
     * 读：解出 DEK → 整库载入 → 按 credentialId 找 passkey（跨账号扫描，出卡方已按
     * rpId 过滤过，这里精确主键）→ 只携出签名字段。找不到返回 null（调用方放弃）。
     */
    fun loadPasskey(context: Context, dek: ByteArray, credentialId: String): PasskeyMaterial? {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return null
        val account = state.accounts.firstOrNull { acc ->
            acc.passkeys.any { it.credentialId == credentialId }
        } ?: return null
        val passkey = account.passkeys.first { it.credentialId == credentialId }
        return PasskeyMaterial(
            credentialId = passkey.credentialId,
            rpId = passkey.rpId,
            userName = passkey.userName,
            userHandle = passkey.userHandle,
            privateKeyPkcs8 = passkey.privateKeyPkcs8
        )
    }

    /**
     * 读：按 rpId 列出全部凭据（出卡前 Service 端「锁定态出卡」用——Service 无 DEK，
     * 此函数实际只在 Get Activity 复核时用；保留对称性）。
     */
    fun loadPasskeysByRpId(context: Context, dek: ByteArray, rpId: String): List<PasskeyMaterial> {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return emptyList()
        return state.accounts.asSequence()
            .flatMap { it.passkeys.asSequence() }
            .filter { it.rpId == rpId }
            .map {
                PasskeyMaterial(
                    credentialId = it.credentialId,
                    rpId = it.rpId,
                    userName = it.userName,
                    userHandle = it.userHandle,
                    privateKeyPkcs8 = it.privateKeyPkcs8
                )
            }
            .toList()
    }

    /**
     * 写：解出 DEK → 整库载入 → 目标账号追加/替换 passkey → 原子回写。
     * 归属账号选择：优先精确匹配 accountId；未指定时按 url 域后缀匹配 rpId 的账号，
     * 再退「名称或用户名含 rpId 主域」的模糊匹配；全空则挂到库内第一个账号（库空 =
     * 放弃，通行密钥必须有账号容器——系统流程里用户不可能无库注册）。
     * 返回归属账号 id（registry 索引用）；失败返回 null。
     */
    fun savePasskey(
        context: Context,
        dek: ByteArray,
        rpId: String,
        userName: String,
        userHandle: String,
        credentialId: String,
        privateKeyPkcs8: String,
        publicKeyXY: String,
        publicKeyDer: String,
        preferAccountId: String? = null
    ): String? {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return null
        val target = selectAccount(state.accounts, rpId, userName, preferAccountId) ?: return null
        val passkey = AccountPasskey(
            credentialId = credentialId,
            rpId = rpId,
            userName = userName,
            userHandle = userHandle,
            privateKeyPkcs8 = privateKeyPkcs8,
            publicKeyXY = publicKeyXY,
            publicKeyDer = publicKeyDer,
            signCount = 0,
            createdAt = System.currentTimeMillis()
        )
        // 同 credentialId 替换（防重注册堆积），否则追加
        val merged = if (target.passkeys.any { it.credentialId == credentialId }) {
            target.passkeys.map { if (it.credentialId == credentialId) passkey else it }
        } else {
            target.passkeys + passkey
        }
        val nextState = state.copy(
            accounts = state.accounts.map { if (it.id == target.id) target.copy(passkeys = merged) else it }
        )
        store.save(nextState, dek)
        return target.id
    }

    /** 删：账号段内移除 passkey（编辑页删除调用；vault 无变化时不动盘）。 */
    fun deletePasskey(context: Context, dek: ByteArray, credentialId: String): Boolean {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return false
        var changed = false
        val accounts = state.accounts.map { acc ->
            if (acc.passkeys.none { it.credentialId == credentialId }) {
                acc
            } else {
                changed = true
                acc.copy(passkeys = acc.passkeys.filterNot { it.credentialId == credentialId })
            }
        }
        if (!changed) return false
        store.save(state.copy(accounts = accounts), dek)
        return true
    }

    /**
     * 归属账号选择：preferAccountId 精确 → url 域后缀匹配 → 用户名/名称含主域 → 首账号。
     * rpId 形如 "github.com"；匹配大小写不敏感（Account.url 存原文，域名大小写等价）。
     */
    private fun selectAccount(
        accounts: List<Account>,
        rpId: String,
        userName: String,
        preferAccountId: String?
    ): Account? {
        if (accounts.isEmpty()) return null
        preferAccountId?.let { prefer ->
            accounts.firstOrNull { it.id == prefer }?.let { return it }
        }
        val lowerRp = rpId.lowercase()
        accounts.firstOrNull { acc ->
            acc.url.isNotBlank() && domainMatches(acc.url, lowerRp)
        }?.let { return it }
        val mainDomain = lowerRp.substringBefore('.')
        accounts.firstOrNull { acc ->
            (acc.name.contains(lowerRp, ignoreCase = true) ||
                acc.username.contains(lowerRp, ignoreCase = true) ||
                (userName.isNotBlank() && userName.equals(acc.username, ignoreCase = true)))
        }?.let { return it }
        return accounts.first()
    }

    /** url 含域后缀匹配：host 相等或 rpId 是 host 的子域尾巴（"github.com" ↔ "api.github.com"）。 */
    private fun domainMatches(url: String, lowerRp: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: url.lowercase()
        return host == lowerRp || host.endsWith(".$lowerRp")
    }
}
