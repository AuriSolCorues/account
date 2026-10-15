/**
 * 职责：锁定态填充的解封动作——AutofillBridge 非活跃（锁定/冷启动/进程死亡后重启）时，
 *       用户在填充气泡点选账号卡，本对象在 AutofillAuthActivity 验证通过后执行：
 *       解出 DEK → **只解密被点选账号的密文段**（vault.bin v2 分段格式，loadAccount）
 *       → 返回该账号明文供构建回包。全程不重开桥、不解密其他账号（零额外暴露、
 *       零明文驻留：DEK 用完即清，明文只存在于回包构建作用域）。
 *       索引为空（全新安装/手动关窗）时服务端不再出占位卡——锁定态填充必须先解锁
 *       一次建立公开索引，故本对象只有「按 id 单段解密」一条路径（指纹/密码两种授权）。
 * 架构位置：service/ 内部共享工具（internal object）；读包装密文方是
 *           SecureVaultStore.biometricCiphertext / beginBiometricDecrypt。
 * 安全边界：不改变 VaultStore 的密钥参数（validity -1 每次使用必认证原样保留——指纹路径
 *           的每一次解密都必须先过 CryptoObject 生物识别）；DEK 与 backupKey 在回包
 *           构建完成后立即 fill(0)，账号明文只存在于回包 Dataset 的构建作用域内；
 *           任何失败路径都先清零再返回。
 * Python 类比：≈ 一对模块级常量加一个恢复函数；状态全部寄存在 VaultStore 里。
 */
package com.copy.account.service

import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.toAutofillAccount
import com.copy.account.security.SecureVaultStore
import javax.crypto.Cipher

internal object AutofillSelfHeal {

    /** Boolean extra：锁定态填充标记（认证 Intent 携带，Activity 端据此切换模式）。 */
    const val EXTRA_SELF_HEAL = "com.copy.account.autofill.SELF_HEAL"

    /**
     * 指纹路径：CryptoObject 授权的 cipher 解开包装 DEK → loadAccount 只解被点选账号
     * 的密文段（其余账号全程密文）→ 返回明文行。**不灌桥、不解整库**。
     * DEK 与 backupKey 在本函数内清零；找不到账号（已删除/索引过期）返回 null。
     */
    fun unlockAccount(cipher: Cipher, store: SecureVaultStore, accountId: String): AutofillAccount? {
        val dek = runCatching { cipher.doFinal(store.biometricCiphertext() ?: return null) }
            .getOrNull() ?: return null
        try {
            return store.loadAccount(dek, accountId)?.toAutofillAccount()
        } finally {
            dek.fill(0)
        }
    }

    /**
     * 密码路径：主密码经 store 校验解出 DEK → 同 [unlockAccount] 只解被点选账号段。
     * PBKDF2 校验失败/IO 失败返回 null；DEK 与 backupKey（store 校验通过即会产生，
     * 锁定态填充用不到它，取 DEK 后立即清零）在本函数内清零。
     * （store.unlockWithPassword 的两个抛异常分支——首装建库与旧版迁移——在锁定态
     * 不可能发生：能锁定说明库已存在且已迁移过；仍用 runCatching 兜底。）
     */
    fun unlockAccountWithPassword(store: SecureVaultStore, password: String, accountId: String): AutofillAccount? {
        return runCatching {
            val unlocked = store.unlockWithPassword(password) ?: return null
            val dek = unlocked.dataKey
            // backupKey 是导出用密钥材料，锁定态填充用不到；unwrap 成功即清零，
            // 不留到 GC（此前版本漏清此字段，已修）。
            unlocked.backupKey.clear()
            try {
                return store.loadAccount(dek, accountId)?.toAutofillAccount()
            } finally {
                dek.fill(0)
            }
        }.getOrNull()
    }
}
