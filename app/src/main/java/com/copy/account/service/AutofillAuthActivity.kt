/**
 * 职责：自动填充的认证回填页——用户在系统填充气泡里点了「需认证的数据集」后由系统拉起，
 *       验证后回包。三种模式：
 *   Mode A（解锁态逐账号认证）：extras 带 account_id、无 SELF_HEAL → 弹 BiometricPrompt，
 *       通过后优先单段解密磁盘库取最新明文（快照兜底），本地重建已回填 Dataset 回系统；
 *   Mode B（锁定态·指纹）：extras 带 SELF_HEAL=true + account_id → 验证挂 CryptoObject
 *       （Keystore 每次使用必认证的密钥），通过后 AutofillSelfHeal.unlockAccount 用
 *       loadAccount **只解密被点选账号的密文段**（vault.bin v2 分段格式），瞬时构建
 *       已回填数据集回包——不灌桥、不解整库、DEK 用完即清，零明文驻留；
 *   Mode C（锁定态·主密码）：SELF_HEAL=true 且设备未配置生物识别 → 显示密码输入小卡，
 *       校验通过后走与 Mode B 相同的单段解密回包路径（unlockAccountWithPassword）。
 *       刻意不给「已配置生物识别」的设备密码降级入口，避免验证强度倒退。
 *       验证取消/失败/解封失败一律 RESULT_CANCELED，系统放弃该数据集。
 * 架构位置：service/ 系统入口组件（manifest 注册为透明浮层：noHistory + excludeFromRecents）。
 *           请求端契约见 AppAutofillService 的 KDoc 与 AutofillMatcher/AutofillSelfHeal
 *           的常量（account_id / username_ids / phone_ids / email_ids / password_ids /
 *           SELF_HEAL / TARGET_PACKAGE / TARGET_WEB_DOMAIN；两端按同名常量读取，改动须
 *           两端同步）；数据集/匹配构建统一走共享的 AutofillMatcher。
 * 与 PasswordManager 参考实现的差异：
 *   1. 取数：PM 按 Long id 挂起查 Room → 本实现同进程直读桥快照（Mode A）或瞬时解库
 *      （Mode B/C），无 IO 线程池；Mode B/C 的明文只存在于回包构建作用域。
 *   2. phone_ids/email_ids 的取值简化：PM 从账号自定义字段里挑电话/邮箱；本应用的
 *      AutofillAccount 没有自定义字段，用户名即唯一规范值，故用户名/手机/邮箱三类框
 *      一律填 acc.username（为空则跳过该框），密码框填 acc.password。
 *   3. 生物识别组合回退内联：BIOMETRIC_STRONG|DEVICE_CREDENTIAL 组合在部分 API<30 机型
 *      会让 BiometricPrompt 直接抛 IllegalArgumentException → 退回仅 BIOMETRIC_STRONG。
 *      Mode B 的回退分支同样携带 CryptoObject（否则每次使用必认证的密钥不会放行解密）。
 * Python 类比：≈ 一个被外部进程回调的 webhook 处理页——onCreate 读参数、验证后 setResult
 *           回包、finish 即返回；刻意不留实例状态（恢复实例直接取消，避免重复弹验证框）。
 */
package com.copy.account.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.Gravity
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.AutofillBridge
import com.copy.account.security.SecureVaultStore
import com.copy.account.runtime.toAutofillAccount
import javax.crypto.Cipher

class AutofillAuthActivity : FragmentActivity() {

    /** 锁定态解封用的 vault 存储实例：取包装密文、初始化解密 cipher、密码校验。 */
    private val vaultStore by lazy { SecureVaultStore(this) }

    // extras 快照：onCreate 一次读齐，验证回调里只读这些本地字段，不再碰 intent。
    private var selfHeal = false
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
        accountId = intent.getStringExtra(AutofillMatcher.EXTRA_ACCOUNT_ID).orEmpty()
        usernameIds = readIds(AutofillMatcher.EXTRA_USERNAME_IDS)
        phoneIds = readIds(AutofillMatcher.EXTRA_PHONE_IDS)
        emailIds = readIds(AutofillMatcher.EXTRA_EMAIL_IDS)
        passwordIds = readIds(AutofillMatcher.EXTRA_PASSWORD_IDS)
        val hasTargets = usernameIds.isNotEmpty() || passwordIds.isNotEmpty() ||
                phoneIds.isNotEmpty() || emailIds.isNotEmpty()
        // Mode A 缺账号 id；锁定态（SELF_HEAL）也必须带账号 id——服务端锁定分支只发
        // 账号卡（索引检索 → 选账号 → 单段解密），占位卡已删。传参异常一律取消。
        val hasAccountRef = accountId.isNotBlank()
        val valid = hasAccountRef && hasTargets
        if (!valid) {
            finishCancel()
            return
        }
        if (savedInstanceState != null) {
            // 已恢复的实例：noHistory 下仍可能被系统重建，直接取消，避免二次弹验证。
            finishCancel()
            return
        }
        // 等 decorView 挂好再弹验证（透明主题下过早弹出会被吞掉）。
        window.decorView.post {
            if (!authenticating && !isFinishing) startAuthentication()
        }
    }

    private fun readIds(key: String): List<AutofillId> =
        IntentCompat.getParcelableArrayListExtra(intent, key, AutofillId::class.java).orEmpty()

    /** 取锁定态解密 cipher：无包装密文（未配置生物识别）或 Keystore 初始化失败都返回 null。 */
    private fun beginSelfHealCipher(): Cipher? = try {
        vaultStore.beginBiometricDecrypt()
    } catch (_: Exception) {
        null
    }

    /** 锁定态走指纹还是密码：配置过生物识别（包装密文存在）→ 指纹；否则 → 主密码。 */
    private fun lockedModeUsesBiometric(): Boolean =
        runCatching { vaultStore.biometricCiphertext() }.getOrNull() != null

    /**
     * 分发验证方式：
     *   解锁态（Mode A）→ BiometricPrompt（无 CryptoObject）；
     *   锁定态 + 已配置生物识别（Mode B）→ BiometricPrompt + CryptoObject；
     *   锁定态 + 未配置生物识别（Mode C）→ 密码输入小卡。
     */
    private fun startAuthentication() {
        authenticating = true
        when {
            !selfHeal -> promptBiometric(selfHeal = false, cipher = null)
            lockedModeUsesBiometric() -> {
                val cipher = beginSelfHealCipher()
                if (cipher == null) {
                    Toast.makeText(this, "无法解封数据，请先打开账号本子解锁一次", Toast.LENGTH_SHORT).show()
                    finishCancel()
                    return
                }
                promptBiometric(selfHeal = true, cipher = cipher)
            }
            else -> showPasswordEntry()
        }
    }

    /** Mode C：锁定态无生物识别时的主密码输入小卡（程序化构建，透明浮层内嵌一块卡）。 */
    private fun showPasswordEntry() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202124.toInt())
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply {
            hint = "主密码"
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setSingleLine(true)
        }
        val confirm = Button(this).apply { text = "验证并填充" }
        val cancel = Button(this).apply { text = "取消" }
        container.addView(input)
        container.addView(confirm)
        container.addView(cancel)
        setContentView(container, LinearLayout.LayoutParams(
            (280 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        confirm.setOnClickListener {
            val pwd = input.text.toString()
            if (pwd.isBlank()) return@setOnClickListener
            confirm.isEnabled = false
            Thread {
                val acc = AutofillSelfHeal.unlockAccountWithPassword(vaultStore, pwd, accountId)
                runOnUiThread {
                    if (acc != null) {
                        replyFilled(listOf(acc))
                    } else {
                        Toast.makeText(this, "主密码错误或账号已不存在", Toast.LENGTH_SHORT).show()
                        confirm.isEnabled = true
                        input.setText("")
                    }
                }
            }.start()
        }
        cancel.setOnClickListener { finishCancel() }
    }

    /**
     * 弹 BiometricPrompt；组合认证器不被机型支持时逐级回退，最终失败走取消。
     * 锁定态（selfHeal=true）必须带 CryptoObject：解 DEK 的 Keystore 密钥是「每次使用
     * 必认证」，不挂加密对象的普通验证不会放行后续 doFinal。
     */
    private fun promptBiometric(selfHeal: Boolean, cipher: Cipher?) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    when {
                        this@AutofillAuthActivity.selfHeal && result.cryptoObject != null ->
                            selfHealAndReply(result.cryptoObject?.cipher)
                        this@AutofillAuthActivity.selfHeal -> {
                            // 锁定态丢了 CryptoObject：无法授权解密，按失败处理。
                            Toast.makeText(this@AutofillAuthActivity, "无法解封数据，请重试", Toast.LENGTH_SHORT).show()
                            finishCancel()
                        }
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
            .setSubtitle(if (selfHeal) "验证后填充所选账号" else "验证指纹或锁屏密码以填充账号")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        // 仅强生物识别的回退分支没有锁屏凭据可用，需要显式给一个否定按钮。
        val strongOnly = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (selfHeal) "解锁账号本子" else "解锁自动填充")
            .setSubtitle(if (selfHeal) "验证后填充所选账号" else "验证指纹以填充账号")
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
     * Mode A（单卡，解锁态）：认证通过——优先**单段解密磁盘库**取账号明文（vault.bin 是
     * saveVault 落盘后的最新态，杜绝「改完账号自动填充还是旧值」的快照滞后窗口），
     * 解密失败（历史版本库格式等）再退桥快照；两者皆空 → 取消。
     * dek 用桥内现存 DEK 副本，不落地、用完即弃（loadAccount 内部清零自己的拷贝）。
     */
    private fun loadAccountAndReply() {
        val acc = loadFreshAccount(accountId) ?: AutofillBridge.findAccount(accountId)
        if (acc == null) {
            Toast.makeText(this, "填充会话已失效，请先解锁本应用", Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        replyFilled(listOf(acc))
    }

    /** 磁盘单段解密：桥 DEK 在手（解锁态必非空）时按 id 现解 vault.bin 对应段；任何失败返回 null。 */
    private fun loadFreshAccount(accountId: String): AutofillAccount? {
        val dek = AutofillBridge.dek ?: return null
        return runCatching { vaultStore.loadAccount(dek, accountId)?.toAutofillAccount() }.getOrNull()
    }

    /**
     * Mode B（锁定态·指纹）：认证通过——单段解密被点选账号并回包，**不灌桥、不解整库**。
     * 账号卡必带 account_id（服务端锁定分支只发账号卡，占位卡已删）；找不到账号
     * （已删除/索引过期）→ Toast + 取消，不做全量平铺兜底。
     * cipher 为空（cryptoObject 丢失）等异常一律按解封失败处理。
     */
    private fun selfHealAndReply(cipher: Cipher?) {
        if (cipher == null) {
            Toast.makeText(this, "无法解封数据，请重试", Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        val acc = AutofillSelfHeal.unlockAccount(cipher, vaultStore, accountId)
        if (acc == null) {
            Toast.makeText(this, "账号不存在或已更新，请解锁本应用后重试", Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        replyFilled(listOf(acc))
    }

    /**
     * 已回填数据集应答：单账号回**裸 Dataset**（系统直接填入，无需再点气泡——
     * 账号卡主流程「点一次 → 验证 → 自动填入」的关键）；多账号回 FillResponse
     * 平铺（系统重新弹气泡让用户挑账号，第二次点击不可避免）。
     */
    private fun replyFilled(accounts: List<AutofillAccount>) {
        val datasets = runCatching {
            AutofillMatcher.buildDatasets(accounts) { acc -> buildFilledDatasetFor(acc) }
        }.getOrNull().orEmpty()
        if (datasets.isEmpty()) {
            finishCancel()
            return
        }
        val result = if (datasets.size == 1) {
            datasets.single()
        } else {
            FillResponse.Builder().apply { for (dataset in datasets) addDataset(dataset) }.build()
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result))
        finish()
    }

    /**
     * 已回填数据集（各模式共用）：展示卡与服务端 buildDataset 同一张 RemoteViews 布局
     * （item_autofill_card：单行标题=名称），构建走共享的 AutofillMatcher
     * （取值差异见类头差异清单第 2 条）。
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

    companion object
}
