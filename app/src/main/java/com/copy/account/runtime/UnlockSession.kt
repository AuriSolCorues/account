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
    private val biometricEnabled: () -> Boolean,
    private val autofillEnabled: () -> Boolean,
    private val onBiometricEnabledChange: (Boolean) -> Unit,
    private val backgroundLockSeconds: () -> Int,
    private val kdfIterations: () -> Int,
    private val onUnlocked: () -> Unit,
    private val onLocked: () -> Unit,
) {
    var dataKey by mutableStateOf<ByteArray?>(null)
        private set
    var accounts by mutableStateOf(emptyList<Account>())
    var groups by mutableStateOf(initialGroups)
    var selectedGroupId by mutableStateOf("default")

    var lockGeneration by mutableIntStateOf(0)
        private set
    var passwordConfigured by mutableStateOf(store.hasMasterPassword())
        private set
    var biometricPromptActive by mutableStateOf(false)
        private set

    val isUnlocked: Boolean get() = dataKey != null

    init {
        ExtensionWiring.registerReleaseHook { scheduleBackgroundLock() }
    }

    private val vaultWriteMutex = Mutex()

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
        if (result.isSuccess && autofillEnabled()) {
            AutofillBridge.refresh(context, AutofillBridge.toAutofillAccounts(nextAccounts))
        }
        return result
    }

    fun persistVault() {
        scope.launch { saveVault() }
    }

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
        ExtensionWiring.onLocked()
        AutofillBridge.onSessionLocked()
        onLocked()
    }

    fun advanceHotp(id: String) {
        accounts = accounts.map { account ->
            if (account.id == id && account.isHotp) {
                account.copy(totpCounter = account.totpCounter + 1)
            } else account
        }
        persistVault()
    }

    private fun completeUnlock(key: ByteArray, state: PersistedVault) {
        dataKey?.fill(0)
        dataKey = key
        ExtensionWiring.onUnlocked()
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

    fun onAppPause() {
        biometricPromptActive = false
    }

    fun onAppResume() {
        backgroundLockHandler.removeCallbacks(backgroundLockRunnable)
    }

    fun onAppStop() {
        if (!isUnlocked || !ExtensionWiring.shouldLockOnStop(SystemClock.uptimeMillis())) return
        scheduleBackgroundLock()
    }

    private fun scheduleBackgroundLock() {
        backgroundedAtMs = SystemClock.uptimeMillis()
        backgroundDelaySec = backgroundLockSeconds().coerceIn(0, 300)
        if (backgroundDelaySec <= 0) {
            lockApp()
        } else {
            backgroundLockHandler.postDelayed(backgroundLockRunnable, backgroundDelaySec * 1000L)
        }
    }

    private var backgroundedAtMs = 0L
    private var backgroundDelaySec = 0

    private val backgroundLockHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val backgroundLockRunnable = Runnable {
        if (isUnlocked && backgroundLockDue(SystemClock.uptimeMillis() - backgroundedAtMs, backgroundDelaySec)) lockApp()
    }
}

internal fun backgroundLockDue(elapsedMs: Long, delaySec: Int): Boolean =
    delaySec <= 0 || elapsedMs >= delaySec * 1000L

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
