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
    private val scope: CoroutineScope,
    private val store: SecureVaultStore,
    private val hostActivity: () -> ComponentActivity?,
    /** 现读现用的生物识别开关（生效设置）；实现方须保证每次调用取最新值。 */
    private val biometricEnabled: () -> Boolean,
    /** 启用/关闭生物识别后回写设置真值。 */
    private val onBiometricEnabledChange: (Boolean) -> Unit,
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

    private val vaultWriteMutex = Mutex()

    /** 落盘当前（或指定）库快照；DEK 拷贝用后清零，互斥防并发写坏文件。 */
    suspend fun saveVault(
        nextAccounts: List<Account> = accounts,
        nextGroups: List<Group> = groups,
        nextSelectedGroupId: String = selectedGroupId
    ): Result<Unit> {
        val key = dataKey?.copyOf() ?: return Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
        val snapshot = PersistedVault(accounts = nextAccounts, groups = nextGroups, selectedGroupId = nextSelectedGroupId)
        return try {
            vaultWriteMutex.withLock {
                withContext(Dispatchers.IO) { runCatching { store.save(snapshot, key) } }
            }
        } finally {
            key.fill(0)
        }
    }

    fun persistVault() {
        scope.launch { saveVault() }
    }

    /**
     * 主密码解锁（含首装建库）：PBKDF2 派生 + 文件读写放后台线程；成功即缓存导出密钥
     * （backupKey 用后即清）并进入解锁态。返回是否成功（UnlockScreen 据此清输入/报错）。
     */
    suspend fun unlockWithPassword(password: String): Boolean {
        val firstRun = !passwordConfigured
        val unlocked = withContext(Dispatchers.Default) {
            if (firstRun) {
                val initialState = PersistedVault(accounts = initialAccounts, groups = initialGroups)
                store.createInitial(password, initialState)
            } else {
                store.unlockWithPassword(password)
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

    fun lockApp() {
        dataKey?.fill(0)
        dataKey = null
        accounts = emptyList()
        lockGeneration++
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

    private fun finishUnlock(unlocked: VaultUnlock) {
        dataKey?.fill(0)
        dataKey = unlocked.dataKey
        accounts = unlocked.state.accounts
        groups = unlocked.state.groups.ifEmpty { initialGroups }
        selectedGroupId = unlocked.state.selectedGroupId.ifBlank { "default" }
        onUnlocked()
    }

    private fun finishBiometricUnlock(key: ByteArray, state: PersistedVault) {
        dataKey?.fill(0)
        dataKey = key
        accounts = state.accounts
        groups = state.groups.ifEmpty { initialGroups }
        selectedGroupId = state.selectedGroupId.ifBlank { "default" }
        onUnlocked()
    }

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

    /** ON_STOP：进入后台立即锁定（仅已解锁时）。 */
    fun onAppStop() {
        if (isUnlocked) lockApp()
    }
}

/**
 * 组装工厂：容器只建一次；回调经 rememberUpdatedState 转发防闭包陈旧（同 IdleLock 教训）。
 * biometricEnabled 传「捕获容器而非快照」的 lambda，如 { settingsContainer.settings.biometricEnabled }。
 */
@Composable
internal fun rememberUnlockSession(
    store: SecureVaultStore,
    biometricEnabled: () -> Boolean,
    onBiometricEnabledChange: (Boolean) -> Unit,
    onUnlocked: () -> Unit,
    onLocked: () -> Unit,
): UnlockSession {
    val currentBiometricEnabled by rememberUpdatedState(biometricEnabled)
    val currentOnBiometricEnabledChange by rememberUpdatedState(onBiometricEnabledChange)
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
            onBiometricEnabledChange = { currentOnBiometricEnabledChange(it) },
            onUnlocked = { currentOnUnlocked() },
            onLocked = { currentOnLocked() }
        )
    }
}
