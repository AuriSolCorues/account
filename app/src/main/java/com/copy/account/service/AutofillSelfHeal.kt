/**
 * 职责：桥为空时的「自愈填充」通道——冷启动/进程死亡后 AutofillBridge 处于非活跃态，
 *       而用户正停在别的应用的登录页上。本对象只承载两端共享的契约常量与自愈动作：
 *       用 Keystore 里「每次使用必须认证」的生物识别密钥解出 DEK 包装（Wrapped DEK），
 *       解密 vault.bin 后把快照与 DEK 副本灌回桥，让填充流程在原地继续。
 * 架构位置：service/ 内部共享工具（internal object）；唯一写桥方是 AutofillBridge.enable
 *           （与 UnlockSession 解锁路径同一入口），唯一取包装密文方是
 *           SecureVaultStore.biometricCiphertext / beginBiometricDecrypt。
 * 安全边界：不改变 VaultStore 的密钥参数（validity -1 每次使用必认证原样保留——自愈
 *           路径的每一次解密都必须先过 CryptoObject 生物识别，没有任何绕过）；DEK 在
 *           本对象里只是路过：enable() 内部 copyOf 出桥自己持有的副本，调用方的本地
 *           副本在 enable 成功返回后立即 fill(0)（自愈场景下本地副本再无其他所有者，
 *           不像 UnlockSession 还要留它当会话 dataKey）；任何失败路径都先清零再返回。
 * Python 类比：≈ 一对模块级常量加一个恢复函数；状态全部寄存在桥与 VaultStore 里。
 */
package com.copy.account.service

import com.copy.account.runtime.AutofillBridge
import com.copy.account.security.SecureVaultStore
import javax.crypto.Cipher

internal object AutofillSelfHeal {

    /** Boolean extra：自愈模式标记（认证 Intent 携带，Activity 端据此切换到 Mode B）。 */
    const val EXTRA_SELF_HEAL = "com.copy.account.autofill.SELF_HEAL"

    /** String extra：自愈目标包名（占位数据集构建时的结构 activityComponent 包名）。 */
    const val EXTRA_TARGET_PACKAGE = "com.copy.account.autofill.TARGET_PACKAGE"

    /** String extra：自愈目标 web 域名（清洗后的值；非网页场景为空串）。 */
    const val EXTRA_TARGET_WEB_DOMAIN = "com.copy.account.autofill.TARGET_WEB_DOMAIN"

    /**
     * 认证成功后的自愈动作：beginBiometricDecrypt 出的 cipher 解开包装 DEK →
     * store.load 解密库文件 → AutofillBridge.enable 灌桥。返回成功与否。
     * 成功后桥持有自己的 DEK 副本，调用方传入的本地副本由本函数清零；
     * 失败路径（无包装密文/解密失败/解库失败）同样保证本地副本清零后再返回。
     */
    fun heal(cipher: Cipher, store: SecureVaultStore): Boolean {
        val dek = runCatching { cipher.doFinal(store.biometricCiphertext() ?: return false) }
            .getOrNull() ?: return false
        val state = store.load(dek) ?: run {
            dek.fill(0)
            return false
        }
        AutofillBridge.enable(AutofillBridge.toAutofillAccounts(state.accounts), dek)
        // 桥在 enable 里已 copyOf 自持副本；自愈场景本地副本再无其他所有者，立即擦除。
        dek.fill(0)
        return true
    }
}
