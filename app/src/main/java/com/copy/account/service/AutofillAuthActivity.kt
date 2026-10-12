/**
 * 职责：自动填充的认证回填页——用户在系统填充气泡里点了「需认证的数据集」后由系统拉起，
 *       弹 BiometricPrompt（指纹/锁屏凭据）验证后回包。两种模式：
 *   Mode A（普通逐账号认证）：extras 带 account_id → 验证后经 AutofillBridge.findAccount
 *       从解锁快照取回该账号明文，本地重建已回填的 Dataset 回给系统；
 *   Mode B（自愈填充，见 AutofillSelfHeal）：extras 带 EXTRA_SELF_HEAL=true（无 account_id）
 *       → 验证改用 CryptoObject（Keystore 每次使用必认证的密钥授权解密），通过后执行
 *       AutofillSelfHeal.heal 解出 DEK 灌活桥，再按目标包名/域名对桥快照跑服务端同款
 *       匹配（AutofillMatcher），把全部命中账号构建成「已回填、不再挂认证」的数据集
 *       一次性回一个完整 FillResponse。设计取舍：本次回包不再逐账号认证——用户刚通过
 *       CryptoObject 验证打开了窗口，这就是他点占位卡片要的那一次填充；此后的填充请求
 *       走服务端正常路径的逐账号认证门，逐次验证的产品要求不受削弱。
 *       验证取消/失败/自愈失败一律 RESULT_CANCELED，系统放弃该数据集。
 * 架构位置：service/ 系统入口组件（manifest 注册为透明浮层：noHistory + excludeFromRecents）。
 *           请求端契约见 AppAutofillService 的 KDoc 与 AutofillMatcher/AutofillSelfHeal
 *           的常量（Mode A：account_id / username_ids / phone_ids / email_ids /
 *           password_ids；Mode B 追加 SELF_HEAL=true / TARGET_PACKAGE /
 *           TARGET_WEB_DOMAIN，节点 id 列表复用 Mode A 键名；两端按同名常量读取，
 *           改动须两端同步）；数据集/匹配构建统一走共享的 AutofillMatcher。
 * 与 PasswordManager 参考实现的差异：
 *   1. 取数：PM 按 Long id 挂起查 Room → 本实现同进程直读 AutofillBridge 快照，无 IO 线程；
 *      桥可能已被清（锁定/关开关），查不到时 Toast 提示并取消；Mode B 则先自愈灌桥再取。
 *   2. phone_ids/email_ids 的取值简化：PM 从账号自定义字段里挑电话/邮箱（phoneValueOf/
 *      emailValueOf）；本应用的 AutofillAccount 没有自定义字段，用户名即唯一规范值，
 *      故用户名/手机/邮箱三类框一律填 acc.username（为空则跳过该框），密码框填 acc.password。
 *   3. 生物识别组合回退内联（原 util/BiometricAuthHelper.authenticate 的逻辑搬入）：
 *      BIOMETRIC_STRONG|DEVICE_CREDENTIAL 组合在部分 API<30 机型会让 BiometricPrompt 直接抛
 *      IllegalArgumentException → 退回仅 BIOMETRIC_STRONG，再失败则取消。Mode B 的回退
 *      分支同样携带 CryptoObject（否则每次使用必认证的密钥不会放行解密）。
 * Python 类比：≈ 一个被外部进程回调的 webhook 处理页——onCreate 读参数、验证后 setResult
 *           回包、finish 即返回；刻意不留实例状态（恢复实例直接取消，避免重复弹验证框）。
 */
package com.copy.account.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.AutofillBridge
import com.copy.account.security.SecureVaultStore
import javax.crypto.Cipher

class AutofillAuthActivity : FragmentActivity() {

    /** 只在自愈模式里用的 vault 存储实例：取包装密文与初始化解密 cipher。 */
    private val vaultStore by lazy { SecureVaultStore(this) }

    // extras 快照：onCreate 一次读齐，认证回调里只读这些本地字段，不再碰 intent。
    private var selfHeal = false
    private var targetPackage: String = ""
    private var targetWebDomain: String = ""
    private var accountId: String = ""
    private var usernameIds: List<AutofillId> = emptyList()
    private var phoneIds: List<AutofillId> = emptyList()
    private var emailIds: List<AutofillId> = emptyList()
    private var passwordIds: List<AutofillId> = emptyList()
    /** 防重入：decorView.post 与系统快速重拉起时只允许弹一次验证框。 */
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selfHeal = intent.getBooleanExtra(AutofillSelfHeal.EXTRA_SELF_HEAL, false)
        targetPackage = intent.getStringExtra(AutofillSelfHeal.EXTRA_TARGET_PACKAGE).orEmpty()
        targetWebDomain = intent.getStringExtra(AutofillSelfHeal.EXTRA_TARGET_WEB_DOMAIN).orEmpty()
        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        usernameIds = readIds(EXTRA_USERNAME_IDS)
        phoneIds = readIds(EXTRA_PHONE_IDS)
        emailIds = readIds(EXTRA_EMAIL_IDS)
        passwordIds = readIds(EXTRA_PASSWORD_IDS)
        val hasTargets = usernameIds.isNotEmpty() || passwordIds.isNotEmpty() ||
                phoneIds.isNotEmpty() || emailIds.isNotEmpty()
        // Mode A 缺账号 id、Mode B 缺自愈标记（或两端连一个可填目标都没有）：
        // 系统侧传参异常，回取消让气泡静默消失。
        val hasAccountRef = accountId.isNotBlank()
        val valid = if (selfHeal) hasTargets else hasAccountRef && hasTargets
        if (!valid) {
            finishCancel()
            return
        }
        if (savedInstanceState != null) {
            // 已恢复的实例：noHistory 下仍可能被系统重建，直接取消，避免二次弹验证。
            finishCancel()
            return
        }
        // 等 decorView 挂好再弹 BiometricPrompt（透明主题下过早弹出会被吞掉）。
        window.decorView.post {
            if (!authenticating && !isFinishing) startAuthentication()
        }
    }

    private fun readIds(key: String): List<AutofillId> =
        IntentCompat.getParcelableArrayListExtra(intent, key, AutofillId::class.java).orEmpty()

    /** 取自愈解密 cipher：无包装密文或 Keystore 初始化失败都返回 null。 */
    private fun beginSelfHealCipher(): Cipher? = try {
        vaultStore.beginBiometricDecrypt()
    } catch (_: Exception) {
        null
    }

    /** 弹验证框；组合认证器不被机型支持时逐级回退，最终失败走取消。Mode B 必须带
     *  CryptoObject：解 DEK 的 Keystore 密钥是「每次使用必认证」，不挂加密对象的
     *  普通验证不会放行后续 doFinal。 */
    private fun startAuthentication() {
        authenticating = true
        val cipher = if (selfHeal) beginSelfHealCipher() else null
        if (selfHeal && cipher == null) {
            // 包装 DEK 已不在（从未配置/已被禁用生物识别）：自愈无从发起。
            Toast.makeText(this, "无法解封数据，请先打开账号本子解锁一次", Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    when {
                        selfHeal -> selfHealAndReply(result.cryptoObject?.cipher)
                        else -> loadAccountAndReply()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                    finishCancel()
                }
            }
        )
        // 首选「强生物识别或锁屏凭据」；部分 API<30 机型不接受该组合（构造即抛），退回仅强生物识别。
        val combined = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (selfHeal) "解锁账号本子" else "解锁自动填充")
            .setSubtitle(if (selfHeal) "验证后填充匹配的账号" else "验证指纹或锁屏密码以填充账号")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        // 仅强生物识别的回退分支没有锁屏凭据可用，需要显式给一个否定按钮。
        val strongOnly = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (selfHeal) "解锁账号本子" else "解锁自动填充")
            .setSubtitle(if (selfHeal) "验证后填充匹配的账号" else "验证指纹以填充账号")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("取消")
            .build()
        try {
            if (selfHeal) {
                prompt.authenticate(combined, BiometricPrompt.CryptoObject(cipher!!))
            } else {
                prompt.authenticate(combined)
            }
        } catch (_: IllegalArgumentException) {
            try {
                if (selfHeal) {
                    prompt.authenticate(strongOnly, BiometricPrompt.CryptoObject(cipher!!))
                } else {
                    prompt.authenticate(strongOnly)
                }
            } catch (_: Exception) {
                authenticating = false
                finishCancel()
            }
        } catch (_: Exception) {
            authenticating = false
            finishCancel()
        }
    }

    /**
     * Mode A（单卡）：认证通过——从桥快照取账号明文并本地重建 Dataset 回给系统。
     * 快照被清（锁定/关开关）或回填值非法 → 取消；不落地任何明文副本，用完即弃。
     */
    private fun loadAccountAndReply() {
        val acc = AutofillBridge.findAccount(accountId)
        if (acc == null) {
            Toast.makeText(this, "填充会话已失效，请先解锁本应用", Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        val dataset = runCatching { buildFilledDatasetFor(acc) }.getOrElse {
            finishCancel()
            return
        }
        replySingleDataset(dataset)
    }

    /**
     * Mode B：认证通过——先自愈灌桥（heal 失败 → Toast + 取消），再按目标包名/域名
     * 对桥快照跑与服务端同款的匹配（web 域名优先、包名兜底，上限 AutofillMatcher
     * 的 MAX_DATASETS=10），把全部命中账号构建成已回填数据集平铺一次性回一个完整
     * FillResponse（CryptoObject 刚验证通过，不再二次验证）。
     * cipher 为空（cryptoObject 丢失）等异常一律按自愈失败处理。
     */
    private fun selfHealAndReply(cipher: Cipher?) {
        if (cipher == null || !AutofillSelfHeal.heal(cipher, vaultStore)) {
            Toast.makeText(this, "无法解封数据，请先打开账号本子解锁一次", Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        // 灌桥成功：此刻 snapshot 必然非活跃转活跃，取一次本地副本做匹配。
        val snapshot = AutofillBridge.snapshot
        var matched = emptyList<AutofillAccount>()
        if (targetWebDomain.isNotBlank()) {
            matched = AutofillMatcher.queryWebAccounts(snapshot, targetWebDomain)
        }
        if (matched.isEmpty() && targetPackage.isNotBlank()) {
            matched = AutofillMatcher.queryPackageAccounts(this, snapshot, targetPackage)
        }
        val response = runCatching {
            val datasets = AutofillMatcher.buildDatasets(matched) { acc -> buildFilledDatasetFor(acc) }
            if (datasets.isEmpty()) return@runCatching null
            val builder = FillResponse.Builder()
            for (dataset in datasets) {
                builder.addDataset(dataset)
            }
            builder.build()
        }.getOrNull()
        if (response == null) {
            finishCancel()
            return
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        finish()
    }

    /** 单数据集应答：填充结果经 EXTRA_AUTHENTICATION_RESULT 回给系统并结束本页。 */
    private fun replySingleDataset(dataset: Dataset) {
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
    }

    /**
     * 已回填数据集（两模式共用）：展示卡与服务端 buildDataset 同一张 RemoteViews 布局
     * （item_autofill_card：单行标题=名称），构建走共享的 AutofillMatcher
     * （取值差异见类头差异清单第 2 条）。
     * 关于 client_state：契约里预留了透传位，但 Dataset.Builder.setClientState 是 API 33+
     * 且本服务端当前不携带，故不读取不回传；两端引入时须同步升级此处理。
     */
    private fun buildFilledDatasetFor(acc: AutofillAccount): Dataset {
        val presentation = AutofillMatcher.buildPresentation(
            this,
            acc.name.ifBlank { acc.username }
        )
        return AutofillMatcher.buildFilledDataset(
            presentation,
            acc,
            usernameIds,
            phoneIds,
            emailIds,
            passwordIds
        )
    }

    private fun finishCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        // extras 键名：与 AppAutofillService 端同名常量一一对应，两端契约。
        // 自愈模式的三个键见 AutofillSelfHeal 常量（SELF_HEAL / TARGET_PACKAGE /
        // TARGET_WEB_DOMAIN），节点 id 列表与本处的 Mode A 键名复用。
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_USERNAME_IDS = "username_ids"
        private const val EXTRA_PHONE_IDS = "phone_ids"
        private const val EXTRA_EMAIL_IDS = "email_ids"
        private const val EXTRA_PASSWORD_IDS = "password_ids"
    }
}
