/**
 * 职责：解锁会话域状态容器——「会话 = 数据 + 钥匙」。DEK 生命周期（解锁注入/锁定清零）、
 *       账号与分组数据的内存态、vault 落盘（互斥 + IO）、HOTP 计数推进、生物识别弹窗
 *       （验证解锁 + 启用包装）、切后台/暂停的生命周期钩子，全部收敛于此。
 *       导航与页面级清理（editTemplate）不归本容器——经 onUnlocked/onLocked 回调交给 AccountApp。
 * 架构位置：runtime/ 组装层。AccountApp 经 rememberUnlockSession 创建；IdleLockHost/备份导出
 *           等消费方以 isUnlocked / dataKey 快照为输入，不直接碰密钥本体。
 * Python 类比：≈ Session 对象——login()/logout() 管会话开关，属性挂数据；Compose 的
 *           mutableStateOf 让「读它的人」在 login/logout 时自动刷新。
 */
package com.copy.account.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.copy.account.data.model.Account
import com.copy.account.data.model.withUidFilled
import com.copy.account.data.model.Group
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.security.SecureVaultStore
import com.copy.account.security.VaultUnlock
import com.copy.account.security.isHotp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class UnlockSession(
    private val context: Context,
    private val scope: CoroutineScope,    private val store: SecureVaultStore,
    private val hostActivity: () -> ComponentActivity?,
    /** 现读现用的生物识别开关（生效设置）；实现方须保证每次调用取最新值。 */
    private val biometricEnabled: () -> Boolean,
    /** 现读现用的自动填充开关（生效设置）；桥的灌入/撤除以此为闸。 */
    private val autofillEnabled: () -> Boolean,
    /** 启用/关闭生物识别后回写设置真值。 */
    private val onBiometricEnabledChange: (Boolean) -> Unit,
    /** 现读现用的切后台锁定延迟（秒，0 = 立即）；onAppStop 的 Handler 调度用。 */
    private val backgroundLockSeconds: () -> Int,
    /** 现读现用的 KDF 迭代数目标值（设置项）：密码解锁时透传给 store 触发透明升级。 */
    private val kdfIterations: () -> Int,
    /** 解锁完成（密码/生物识别）：AccountApp 跳 Home。 */
    private val onUnlocked: () -> Unit,
    /** 锁定完成：AccountApp 清 editTemplate 并回解锁页。 */
    private val onLocked: () -> Unit,
) {
    /** 数据加密密钥（DEK）。只在解锁注入、锁定清零；null = 未解锁。 */
    var dataKey by mutableStateOf<ByteArray?>(null)
        private set
    var accounts by mutableStateOf(emptyList<Account>())
    var groups by mutableStateOf(initialGroups)
    var selectedGroupId by mutableStateOf("default")

    /** 每次锁定 +1，作 UnlockScreen 输入框的 resetKey（换 key 整组状态重建）。 */
    var lockGeneration by mutableIntStateOf(0)
        private set
    /** 是否已配置主密码（首装引导 vs 常规解锁的分叉依据）。 */
    var passwordConfigured by mutableStateOf(store.hasMasterPassword())
        private set
    /** 生物识别弹窗是否进行中（防重入；ON_PAUSE 强制复位）。 */
    var biometricPromptActive by mutableStateOf(false)
        private set

    val isUnlocked: Boolean get() = dataKey != null

    init {
        // 归还钩子：扩展页 releaseSession（退后台/被销毁）到达时执行——语义与本体页
        // onAppStop 完全一致：按后台锁定配置调度（0=立即，>0 延迟缓冲，回前台取消）。
        // 曾经硬编码 lockApp() → 扩展退后台无视延迟档位瞬间锁，而本体页守档位，两页
        // 语义分裂；统一收口进 scheduleBackgroundLock()（锁定时本人会话有效，直接引用；
        // 会话销毁后钩子随单例残留引用旧容器——旧容器 lockApp/调度依旧幂等安全，且新
        // 会话会重注册覆盖）。Wiring 收口：宿主不直接触碰 ExtensionPlatformState，
        // 公开/私有两侧以不同实现共存（公开版直通/空实现），保证 merge 零冲突。
        ExtensionWiring.registerReleaseHook { scheduleBackgroundLock() }
    }

    private val vaultWriteMutex = Mutex()

    /** 落盘当前（或指定）库快照；DEK 拷贝用后清零，互斥防并发写坏文件。 */
    suspend fun saveVault(
        nextAccounts: List<Account> = accounts,
        nextGroups: List<Group> = groups,
        nextSelectedGroupId: String = selectedGroupId
    ): Result<Unit> {
        val key = dataKey?.copyOf() ?: return Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
        val snapshot = PersistedVault(accounts = nextAccounts, groups = nextGroups, selectedGroupId = nextSelectedGroupId)
        val result = try {
            vaultWriteMutex.withLock {
                withContext(Dispatchers.IO) { runCatching { store.save(snapshot, key) } }
            }
        } finally {
            key.fill(0)
        }
        // 落盘成功即以本次持久化的账号列表为准刷新自动填充快照与公开索引
        // （桥内部判 active，非活跃是空操作）。
        if (result.isSuccess && autofillEnabled()) {
            AutofillBridge.refresh(context, AutofillBridge.toAutofillAccounts(nextAccounts))
        }
        return result
    }

    fun persistVault() {
        scope.launch { saveVault() }
    }

    /**
     * 删除账号段内的通行密钥（设置页/编辑页删除入口；解锁态 UI 专用）。
     * 内存态与磁盘同时改，经 vaultWriteMutex 防与 saveVault 并发写坏文件；
     * 落盘失败返回 false（内存不回滚——下次解锁自会以磁盘为准，宁缺勿错）。
     */
    suspend fun deletePasskey(credentialId: String): Boolean {
        val key = dataKey?.copyOf() ?: return false
        try {
            var removed = false
            val nextAccounts = accounts.map { acc ->
                if (acc.passkeys.none { it.credentialId == credentialId }) {
                    acc
                } else {
                    removed = true
                    acc.copy(passkeys = acc.passkeys.filterNot { it.credentialId == credentialId })
                }
            }
            if (!removed) return false
            val snapshot = PersistedVault(
                accounts = nextAccounts,
                groups = groups,
                selectedGroupId = selectedGroupId
            )
            val ok = vaultWriteMutex.withLock {
                withContext(Dispatchers.IO) { runCatching { store.save(snapshot, key) }.isSuccess }
            }
            if (ok) accounts = nextAccounts
            return ok
        } finally {
            key.fill(0)
        }
    }

    /**
     * 主密码解锁（含首装建库）：PBKDF2 派生 + 文件读写放后台线程；成功即缓存导出密钥
     * （backupKey 用后即清）并进入解锁态。返回是否成功（UnlockScreen 据此清输入/报错）。
     * targetIterations = 设置里的 KDF 迭代目标值：与库存值不等时解锁链路内透明升级
     * （双向、单次原子提交；本次解锁多付一次派生时间）。生物识别路径不经过这里，天然不触发。
     */
    suspend fun unlockWithPassword(password: String, targetIterations: Int): Boolean {
        val firstRun = !passwordConfigured
        val unlocked = withContext(Dispatchers.Default) {
            if (firstRun) {
                val initialState = PersistedVault(accounts = initialAccounts, groups = initialGroups)
                store.createInitial(password, initialState)
            } else {
                store.unlockWithPassword(password, targetIterations)
            }
        }
        if (unlocked == null) return false
        // 导出密钥只在这里短暂存在：缓存由 DEK 加密，完成后立刻清零。
        withContext(Dispatchers.Default) {
            try {
                store.cacheBackupKey(unlocked.backupKey, unlocked.dataKey)
            } finally {
                unlocked.backupKey.clear()
            }
        }
        if (firstRun) passwordConfigured = true
        finishUnlock(unlocked)
        return true
    }

    /**
     * 锁定：UI 会话清零（DEK/账号内存态/回解锁页），同时撤填充桥（快照+DEK 清零）。
     * 桥只留公开索引（无凭据字段），锁定态账号卡/占位卡经验证瞬时填充，零明文驻留。
     */
    fun lockApp() {
        dataKey?.fill(0)
        dataKey = null
        accounts = emptyList()
        lockGeneration++
        // 锁态同步进平台状态单例（零 IO，ON_STOP 后也即时生效）：扩展下一文件边界查 state 即拒活。
        // Wiring 收口：宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存
        // （公开版直通/空实现），保证 merge 零冲突；300ms 延迟二次广播的 Handler 逻辑在 Wiring 内原样平移。
        ExtensionWiring.onLocked()
        AutofillBridge.onSessionLocked()
        onLocked()
    }

    /** HOTP 复制即 +1：把该账号计数器 +1 并持久化，下次重绘即下一组码。 */
    fun advanceHotp(id: String) {
        accounts = accounts.map { account ->
            if (account.id == id && account.isHotp) {
                account.copy(totpCounter = account.totpCounter + 1)
            } else account
        }
        persistVault()
    }

    /** 主密码/生物识别两条解锁路径的汇合点：装 DEK、灌库、通知、按开关灌填充桥。 */
    private fun completeUnlock(key: ByteArray, state: PersistedVault) {
        dataKey?.fill(0)
        dataKey = key
        // 解锁事实同步进平台状态单例：扩展开工前查 state 拿 locked=false。
        // Wiring 收口：宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存
        // （公开版直通/空实现），保证 merge 零冲突。
        ExtensionWiring.onUnlocked()
        // 旧库账号 uid 为空：读库时惰性补全（仅内存，不立即重写文件；下次保存自然固化）。
        accounts = state.accounts.map { it.withUidFilled() }
        groups = state.groups.ifEmpty { initialGroups }
        selectedGroupId = state.selectedGroupId.ifBlank { "default" }
        onUnlocked()
        if (autofillEnabled()) {
            AutofillBridge.enable(context, AutofillBridge.toAutofillAccounts(accounts), key)
        }
    }

    private fun finishUnlock(unlocked: VaultUnlock) = completeUnlock(unlocked.dataKey, unlocked.state)

    private fun finishBiometricUnlock(key: ByteArray, state: PersistedVault) = completeUnlock(key, state)

    fun authenticateBiometric(onError: () -> Unit = {}) {
        Log.d("UnlockSession", "authenticateBiometric: active=$biometricPromptActive enabled=${biometricEnabled()} avail=${store.biometricAvailable()}")
        if (biometricPromptActive) return
        val host = hostActivity() as? FragmentActivity ?: return onError()
        if (!biometricEnabled() || !store.biometricAvailable()) return onError()
        val cipher = runCatching { store.beginBiometricDecrypt() }.getOrNull() ?: return onError()
        val encryptedDek = store.biometricCiphertext() ?: return onError()
        biometricPromptActive = true
        fun failBiometric() {
            biometricPromptActive = false
            onError()
        }
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val key = runCatching { result.cryptoObject?.cipher?.doFinal(encryptedDek) }.getOrNull()
                val state = key?.let { store.load(it) }
                biometricPromptActive = false
                if (key != null && state != null) finishBiometricUnlock(key, state) else onError()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { failBiometric() }
            override fun onAuthenticationFailed() { }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁账号本子")
                .setSubtitle("使用指纹或面容解锁")
                .setNegativeButtonText("使用主密码")
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    fun configureBiometric(enable: Boolean) {
        if (!enable) {
            store.disableBiometric()
            onBiometricEnabledChange(false)
            return
        }
        val host = hostActivity() as? FragmentActivity ?: return
        val key = dataKey ?: return
        if (!store.biometricAvailable()) return
        val cipher = runCatching { store.beginBiometricEncrypt() }.getOrNull() ?: return
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runCatching { store.saveBiometricWrapped(result.cryptoObject?.cipher ?: cipher, key) }
                    .onSuccess {
                        onBiometricEnabledChange(true)
                    }
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("启用生物识别")
                .setSubtitle("验证指纹或面容以启用快速解锁")
                .setNegativeButtonText("取消")
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    /** ON_PAUSE：切后台时若生物识别弹窗尚未回调，标志位可能卡 true，强制复位。 */
    fun onAppPause() {
        biometricPromptActive = false
    }

    /**
     * ON_RESUME：回到前台——取消未触发的后台延迟锁（用户 X 秒内回来 = 无缝继续，
     * 不断链不解锁页）。超时场景锁已触发（onAppStop 的 postDelayed 已跑），此处无操作。
     */
    fun onAppResume() {
        backgroundLockHandler.removeCallbacks(backgroundLockRunnable)
    }

    /**
     * ON_STOP：切后台锁定（可配延迟）。延迟 = 0 立即锁；>0 经 Handler 调度，
     * 延迟期内回前台由 [onAppResume] 取消。延迟期间 UI 仍显示内容——这是配置本身
     * 的语义（银行 App 同款），敏感场景用户应保持默认立即。
     * 例外：本体主动拉起扩展页（会话让渡 borrow）且未超时——此时 ON_STOP 是「让渡」
     * 不是「离开」，不锁。
     */
    fun onAppStop() {
        // Wiring 收口：宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存
        // （公开版直通/空实现），保证 merge 零冲突。
        if (!isUnlocked || !ExtensionWiring.shouldLockOnStop(SystemClock.uptimeMillis())) return
        scheduleBackgroundLock()
    }

    /**
     * 按后台锁定配置调度锁定（本体 onAppStop 与扩展 releaseSession 共用的唯一入口）：
     * 锚点与档位在「决定要锁的那一刻」捕获——判定只看「锚点 + 捕获的档位」，防止
     * 后台期间改设置导致回调触发时判定漂移（与 IdleLock 的 now/timeoutMs 快照同款思路）。
     * delay = 0 立即锁；>0 经 Handler 调度，延迟期内回前台由 [onAppResume] 取消。
     * 语义统一：本体页退后台和扩展页退后台走同一条路——扩展 releaseSession 的
     * releaseHook 也接这里（否则扩展退后台无视延迟档位瞬间锁，本体页却守档位）。
     */
    private fun scheduleBackgroundLock() {
        backgroundedAtMs = SystemClock.uptimeMillis()
        backgroundDelaySec = backgroundLockSeconds().coerceIn(0, 300)
        if (backgroundDelaySec <= 0) {
            lockApp()
        } else {
            backgroundLockHandler.postDelayed(backgroundLockRunnable, backgroundDelaySec * 1000L)
        }
    }

    /** 切后台锚点（uptimeMillis）：onAppStop 捕获，二次校验据此算真实已过时长。 */
    private var backgroundedAtMs = 0L
    /** 切后台那一刻捕获的延迟档位（秒）：后台期间改设置不影响本次判定。 */
    private var backgroundDelaySec = 0

    /** 后台延迟锁的调度器与任务（会话级，随会话生灭；lockApp 幂等，迟到触发无害）。 */
    private val backgroundLockHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 延迟锁回调：与 IdleLock.onTimeout 同款的防竞态二次校验——Handler 回调可能因
     * 主线程繁忙迟到，也可能在 borrow 让渡/锁态变化后残留触发；触发时用真实时钟
     * 重算「真在后台待满 delaySec 了吗」，语义：后台必须真待满才锁（`>=` 同秒边界，
     * 契约与 idleExpired 一致）。早到的（时钟未走满/极端重排竞态）直接拒锁。
     * 幂等性：lockApp 幂等，迟到/重复触发无害；扩展页返回键回本体（非 release 路径）
     * 场景延迟期内落回本体前台 → onAppResume removeCallbacks 取消，无缝继续。
     */
    private val backgroundLockRunnable = Runnable {
        if (isUnlocked && backgroundLockDue(SystemClock.uptimeMillis() - backgroundedAtMs, backgroundDelaySec)) lockApp()
    }
}

/**
 * 后台延迟锁到期判定（消费方：[UnlockSession.backgroundLockRunnable] 触发时的二次校验）。
 * 档位语义：delaySec = 0 是「立即锁」档——不经 Handler，onAppStop 现场就锁，故任何
 * elapsed 都算到期；>0 时与空闲锁的 idleExpired 同一契约：`>=`（真待满 delaySec*1000
 * 的那一刻必须锁，差 1ms 不算），时钟同源 uptimeMillis。
 * delaySec 本身不在此裁剪——coerceIn(0, 300) 已由调用方（SettingsContainer/AccountApp
 * 的 backgroundLockSeconds 入口）完成，本函数透传。
 */
internal fun backgroundLockDue(elapsedMs: Long, delaySec: Int): Boolean =
    delaySec <= 0 || elapsedMs >= delaySec * 1000L

/**
 * 组装工厂：容器只建一次；回调经 rememberUpdatedState 转发防闭包陈旧（同 IdleLock 教训）。
 * biometricEnabled/autofillEnabled 传「捕获容器而非快照」的 lambda，
 * 如 { settingsContainer.settings.biometricEnabled }。
 */
@Composable
internal fun rememberUnlockSession(
    store: SecureVaultStore,
    biometricEnabled: () -> Boolean,
    autofillEnabled: () -> Boolean,
    onBiometricEnabledChange: (Boolean) -> Unit,
    backgroundLockSeconds: () -> Int,
    kdfIterations: () -> Int,
    onUnlocked: () -> Unit,
    onLocked: () -> Unit,
): UnlockSession {
    val currentBiometricEnabled by rememberUpdatedState(biometricEnabled)
    val currentAutofillEnabled by rememberUpdatedState(autofillEnabled)
    val currentOnBiometricEnabledChange by rememberUpdatedState(onBiometricEnabledChange)
    val currentBackgroundLockSeconds by rememberUpdatedState(backgroundLockSeconds)
    val currentKdfIterations by rememberUpdatedState(kdfIterations)
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val currentOnLocked by rememberUpdatedState(onLocked)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        UnlockSession(
            context = context,
            scope = scope,
            store = store,
            hostActivity = { context as? ComponentActivity },
            biometricEnabled = { currentBiometricEnabled() },
            autofillEnabled = { currentAutofillEnabled() },
            onBiometricEnabledChange = { currentOnBiometricEnabledChange(it) },
            backgroundLockSeconds = { currentBackgroundLockSeconds() },
            kdfIterations = { currentKdfIterations() },
            onUnlocked = { currentOnUnlocked() },
            onLocked = { currentOnLocked() }
        )
    }
}
