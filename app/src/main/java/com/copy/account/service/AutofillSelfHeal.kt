/**
 * 职责：锁定态填充的解封动作—— AutofillBridge 非活跃（锁定/冷启动/进程死亡后重启）时，
 *       用户在填充气泡点选账号卡/占位卡，本对象在 AutofillAuthActivity 验证通过后执行：
 *       解出 DEK → 解密 vault.bin → **瞬时**挑出目标账号构建回包数据集，全程不重开桥
 *       （零明文驻留：DEK 用完即清，明文不进任何驻留结构）。
 * 架构位置：service/ 内部共享工具（internal object）；读包装密文方是
 *           SecureVaultStore.biometricCiphertext / beginBiometricDecrypt。
 * 安全边界：不改变 VaultStore 的密钥参数（validity -1 每次使用必认证原样保留——指纹路径
 *           的每一次解密都必须先过 CryptoObject 生物识别）；DEK 在回包构建完成后立即
 *           fill(0)，解出的账号明文只存在于回包 Dataset 的构建作用域内；任何失败路径
 *           都先清零再返回。
 * Python 类比：≈ 一对模块级常量加一个恢复函数；状态全部寄存在 VaultStore 里。
 */
package com.copy.account.service

import com.copy.account.data.model.PersistedVault
import com.copy.account.runtime.AutofillAccount
import com.copy.account.security.SecureVaultStore
import javax.crypto.Cipher

internal object AutofillSelfHeal {

    /** Boolean extra：锁定态填充标记（认证 Intent 携带，Activity 端据此切换模式）。 */
    const val EXTRA_SELF_HEAL = "com.copy.account.autofill.SELF_HEAL"

    /** String extra：锁定态目标包名（占位数据集构建时的结构 activityComponent 包名）。 */
    const val EXTRA_TARGET_PACKAGE = "com.copy.account.autofill.TARGET_PACKAGE"

    /** String extra：锁定态目标 web 域名（清洗后的值；非网页场景为空串）。 */
    const val EXTRA_TARGET_WEB_DOMAIN = "com.copy.account.autofill.TARGET_WEB_DOMAIN"

    /**
     * 指纹路径：CryptoObject 授权的 cipher 解开包装 DEK → store.load 解密整库 →
     * 直接挑出 [accountId] 对应账号（找不到返回 null，调用方取消）。**不灌桥**。
     * 返回的账号明文由调用方构建回包后尽快使用；DEK 在本函数内已清零。
     */
    fun unlockAccount(cipher: Cipher, store: SecureVaultStore, accountId: String): AutofillAccount? {
        val dek = runCatching { cipher.doFinal(store.biometricCiphertext() ?: return null) }
            .getOrNull() ?: return null
        try {
            val state = store.load(dek) ?: return null
            val acc = state.accounts.firstOrNull { it.id == accountId } ?: return null
            return AutofillAccount(
                id = acc.id,
                name = acc.name,
                username = acc.username,
                password = acc.password,
                url = acc.url,
                packageName = acc.packageName
            )
        } finally {
            dek.fill(0)
        }
    }

    /**
     * 密码路径：主密码经 store 校验并解出 DEK → 同 [unlockAccount] 挑账号，不灌桥。
     * PBKDF2 校验失败/IO 失败返回 null；DEK 在本函数内清零。
     * （store.unlockWithPassword 的两个抛异常分支——首装建库与旧版迁移——在锁定态
     * 不可能发生：能锁定说明库已存在且已迁移过；仍用 runCatching 兜底。）
     */
    fun unlockAccountWithPassword(store: SecureVaultStore, password: String, accountId: String): AutofillAccount? {
        return runCatching {
            val unlocked = store.unlockWithPassword(password) ?: return null
            val dek = unlocked.dataKey
            try {
                val state = store.load(dek) ?: return null
                val acc = state.accounts.firstOrNull { it.id == accountId } ?: return null
                AutofillAccount(
                    id = acc.id,
                    name = acc.name,
                    username = acc.username,
                    password = acc.password,
                    url = acc.url,
                    packageName = acc.packageName
                )
            } finally {
                dek.fill(0)
            }
        }.getOrNull()
    }

    /**
     * 占位卡路径（索引空）：验证通过后解整库 → 按目标包名/域名对全量账号跑服务端同款
     * 匹配（泛型选择器直取 Account 字段），返回命中列表供 Activity 构建已回填数据集。
     * **不灌桥**。DEK 用完清零。
     */
    fun unlockAndMatch(
        context: android.content.Context,
        cipher: Cipher?,
        store: SecureVaultStore,
        password: String?,
        targetPackage: String,
        targetWebDomain: String,
        maxResults: Int
    ): List<AutofillAccount> {
        return runCatching {
            val dek = when {
                cipher != null -> runCatching { cipher.doFinal(store.biometricCiphertext() ?: return emptyList()) }.getOrNull()
                    ?: return emptyList()
                !password.isNullOrBlank() -> store.unlockWithPassword(password)?.dataKey ?: return emptyList()
                else -> return emptyList()
            }
            try {
                val state: PersistedVault = store.load(dek) ?: return emptyList()
                var matched = emptyList<com.copy.account.data.model.Account>()
                if (targetWebDomain.isNotBlank()) {
                    matched = AutofillMatcher.queryWebAccounts(state.accounts, targetWebDomain) { it.url }
                }
                if (matched.isEmpty() && targetPackage.isNotBlank()) {
                    matched = AutofillMatcher.queryPackageAccounts(
                        context,
                        state.accounts,
                        targetPackage,
                        packageNameOf = { it.packageName },
                        nameOf = { it.name }
                    )
                }
                matched.take(maxResults).map { acc ->
                    AutofillAccount(
                        id = acc.id,
                        name = acc.name,
                        username = acc.username,
                        password = acc.password,
                        url = acc.url,
                        packageName = acc.packageName
                    )
                }
            } finally {
                dek.fill(0)
            }
        }.getOrDefault(emptyList())
    }
}
