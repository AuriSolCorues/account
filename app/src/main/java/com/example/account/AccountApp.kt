package com.example.account

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.io.File
import java.io.FileInputStream
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.account.ui.theme.SavedTheme
import com.example.account.ui.theme.defaultThemePresets
import com.example.account.ui.theme.parseThemeJson
import com.example.account.ui.theme.LocalAccountThemePalette

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")
private val BIOMETRIC_SETTING = booleanPreferencesKey("biometric_enabled")
private val AUTO_LOCK_SETTING = intPreferencesKey("auto_lock_minutes")
private val THEME_MODE_SETTING = stringPreferencesKey("theme_mode")
private val ACCENT_THEME_SETTING = stringPreferencesKey("accent_theme")
private val LANGUAGE_TAG_SETTING = stringPreferencesKey("language_tag")
private val CUSTOM_THEME_JSON_SETTING = stringPreferencesKey("custom_theme_json")
private val CUSTOM_THEMES_SETTING = stringPreferencesKey("custom_themes")

@Serializable
internal enum class GroupKind { DEFAULT, DYNAMIC, CUSTOM }

@Serializable
internal data class Group(val id: String, val name: String, val kind: GroupKind)

@Serializable
internal data class AccountField(
    val id: String,
    val label: String,
    val value: String,
    val hidden: Boolean = false
)

@Serializable
internal data class Account(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val groups: Set<String> = emptySet(),
    val hasTotp: Boolean = false,
    val totpSecret: String = "",
    val totpDigits: Int = 6,
    val totpPeriod: Int = 30,
    val totpAlgorithm: String = "SHA1",
    val customFields: List<AccountField> = emptyList()
)

internal data class AppSettings(
    val biometricEnabled: Boolean = false,
    val autoLockMinutes: Int = 5,
    val themeMode: String = "dark",
    val accentTheme: String = "green",
    val languageTag: String = "zh-CN",
    val customThemeJson: String = "",
    val customThemes: List<SavedTheme> = emptyList()
)

@Serializable
internal data class PersistedVault(
    val version: Int = 1,
    val accounts: List<Account>,
    val groups: List<Group>,
    val selectedGroupId: String = "default"
)

@Serializable
private data class EncryptedFile(
    val version: Int = 1,
    val iv: String,
    val ciphertext: String
)

private val initialGroups = listOf(
    Group("default", "默认", GroupKind.DEFAULT),
    Group("dynamic", "动态密码", GroupKind.DYNAMIC),
    Group("social", "社交媒体", GroupKind.CUSTOM),
    Group("games", "游戏娱乐", GroupKind.CUSTOM),
    Group("bank", "银行卡", GroupKind.CUSTOM),
    Group("work", "工作", GroupKind.CUSTOM)
)

private val initialAccounts = listOf(
    Account("github", "GitHub", "hexo", "not-a-real-password", hasTotp = true, totpSecret = "JBSWY3DPEHPK3PXP"),
    Account("nas", "NAS", "admin", "not-a-real-password"),
    Account("gmail", "Gmail", "name@example.com", "not-a-real-password", setOf("social"), true, "NB2W45DFOIZA")
)

private const val SECURITY_PREFS = "account_security"
private const val PASSWORD_HASH = "master_password_hash"
private const val PASSWORD_SALT = "master_password_salt"
private const val PASSWORD_WRAPPED_DEK = "password_wrapped_dek"
private const val PASSWORD_WRAP_IV = "password_wrap_iv"
private const val BIOMETRIC_WRAPPED_DEK = "biometric_wrapped_dek"
private const val BIOMETRIC_WRAP_IV = "biometric_wrap_iv"
private const val BIOMETRIC_ALIAS = "account_vault_biometric"
private const val VAULT_FILE_NAME = "vault.bin"
internal const val PASSWORD_ITERATIONS = 120_000

internal val vaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** 自定义主题列表单独保存，密码库本身不包含界面偏好。 */
private fun decodeSavedThemes(raw: String): List<SavedTheme> = runCatching {
    vaultJson.decodeFromString<List<SavedTheme>>(raw)
}.getOrDefault(emptyList())

private fun encodeSavedThemes(themes: List<SavedTheme>): String =
    vaultJson.encodeToString(themes)

/** 主密码允许中文、英文、数字和符号，长度按 Unicode 码点计算。 */
internal fun isMasterPasswordValid(password: String): Boolean =
    password.codePointCount(0, password.length) in 4..20

private fun normalizePassword(password: String): String =
    Normalizer.normalize(password, Normalizer.Form.NFC)

internal fun passwordHash(password: String, salt: ByteArray, iterations: Int = PASSWORD_ITERATIONS): ByteArray {
    val spec = PBEKeySpec(normalizePassword(password).toCharArray(), salt, iterations, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal data class CipherPayload(val iv: ByteArray, val ciphertext: ByteArray)

internal fun encryptBytes(key: ByteArray, plaintext: ByteArray): CipherPayload {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return CipherPayload(cipher.iv, cipher.doFinal(plaintext))
}

internal fun decryptBytes(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
}

private class SecureVaultStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
    private val vaultFile = File(context.filesDir, VAULT_FILE_NAME)

    fun hasMasterPassword(): Boolean = prefs.contains(PASSWORD_HASH)

    fun verifyMasterPassword(password: String): Boolean {
        if (!isMasterPasswordValid(password)) return false
        val salt = prefs.getString(PASSWORD_SALT, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return false
        val expected = prefs.getString(PASSWORD_HASH, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return false
        val actual = passwordHash(password, salt)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
            salt.fill(0)
            expected.fill(0)
        }
    }

    /** 导出无需再次输入密码，复用首次设置主密码时派生的 KEK 和盐。 */
    fun masterKeyMaterial(): Pair<ByteArray, ByteArray>? {
        val salt = prefs.getString(PASSWORD_SALT, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val key = prefs.getString(PASSWORD_HASH, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        return if (salt.isNotEmpty() && key.size == 32) key to salt else null
    }

    fun createInitial(password: String, state: PersistedVault): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kek = passwordHash(password, salt)
        val wrapped = encryptBytes(kek, dek)
        prefs.edit()
            .putString(PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PASSWORD_HASH, Base64.encodeToString(kek, Base64.NO_WRAP))
            .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
            .putString(PASSWORD_WRAP_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
            .apply()
        save(state, dek)
        return dek
    }

    fun unlockWithPassword(password: String): Pair<ByteArray, PersistedVault>? {
        val salt = prefs.getString(PASSWORD_SALT, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val expected = prefs.getString(PASSWORD_HASH, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val kek = passwordHash(password, salt)
        return try {
            if (!MessageDigest.isEqual(expected, kek)) return null
            val wrapped = prefs.getString(PASSWORD_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) }
            val iv = prefs.getString(PASSWORD_WRAP_IV, null)?.let { Base64.decode(it, Base64.DEFAULT) }
            val dek = if (wrapped != null && iv != null) {
                runCatching { decryptBytes(kek, iv, wrapped) }.getOrNull()
            } else null
            val actualDek = dek ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                val payload = encryptBytes(kek, it)
                prefs.edit()
                    .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
                    .putString(PASSWORD_WRAP_IV, Base64.encodeToString(payload.iv, Base64.NO_WRAP))
                    .apply()
            }
            val state = load(actualDek) ?: PersistedVault(accounts = initialAccounts, groups = initialGroups)
            if (!vaultFile.exists()) save(state, actualDek)
            actualDek to state
        } finally {
            salt.fill(0)
            expected.fill(0)
            kek.fill(0)
        }
    }

    fun save(state: PersistedVault, dek: ByteArray) {
        val plain = vaultJson.encodeToString(PersistedVault.serializer(), state).toByteArray(Charsets.UTF_8)
        val payload = encryptBytes(dek, plain)
        val encoded = vaultJson.encodeToString(
            EncryptedFile.serializer(),
            EncryptedFile(iv = Base64.encodeToString(payload.iv, Base64.NO_WRAP), ciphertext = Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
        ).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(vaultFile)
        val stream = atomic.startWrite()
        try {
            stream.write(encoded)
            stream.flush()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun load(dek: ByteArray): PersistedVault? = runCatching {
        if (!vaultFile.exists()) return null
        val bytes = FileInputStream(vaultFile).use { it.readBytes() }
        val envelope = vaultJson.decodeFromString(EncryptedFile.serializer(), bytes.toString(Charsets.UTF_8))
        val plain = decryptBytes(dek, Base64.decode(envelope.iv, Base64.DEFAULT), Base64.decode(envelope.ciphertext, Base64.DEFAULT))
        vaultJson.decodeFromString(PersistedVault.serializer(), plain.toString(Charsets.UTF_8))
    }.getOrNull()

    fun biometricAvailable(): Boolean = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(BIOMETRIC_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(BIOMETRIC_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun beginBiometricEncrypt(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, biometricKey())
        return cipher
    }

    fun beginBiometricDecrypt(): Cipher? {
        val wrapped = prefs.getString(BIOMETRIC_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val iv = prefs.getString(BIOMETRIC_WRAP_IV, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, biometricKey(), GCMParameterSpec(128, iv))
        return cipher
    }

    fun saveBiometricWrapped(cipher: Cipher, dek: ByteArray) {
        val encrypted = cipher.doFinal(dek)
        prefs.edit()
            .putString(BIOMETRIC_WRAPPED_DEK, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(BIOMETRIC_WRAP_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun biometricCiphertext(): ByteArray? = prefs.getString(BIOMETRIC_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) }

    fun disableBiometric() {
        prefs.edit().remove(BIOMETRIC_WRAPPED_DEK).remove(BIOMETRIC_WRAP_IV).apply()
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(BIOMETRIC_ALIAS)
        }
    }
}

private fun normalizedTotpSecret(raw: String): String {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull()
    return if (uri?.scheme == "otpauth") uri.getQueryParameter("secret").orEmpty() else raw
}

private fun decodeBase32(input: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = input.uppercase().filter { it in alphabet }
    var buffer = 0
    var bits = 0
    val output = ArrayList<Byte>()
    clean.forEach { char ->
        buffer = (buffer shl 5) or alphabet.indexOf(char)
        bits += 5
        if (bits >= 8) {
            bits -= 8
            output += ((buffer shr bits) and 0xff).toByte()
        }
    }
    return output.toByteArray()
}

private fun totpCode(account: Account, nowMillis: Long): String {
    if (!account.hasTotp || account.totpSecret.isBlank()) return "------"
    return runCatching {
        val algorithm = when (account.totpAlgorithm.uppercase().replace("-", "")) {
            "SHA256" -> "SHA256"
            "SHA512" -> "SHA512"
            else -> "SHA1"
        }
        val period = account.totpPeriod.coerceAtLeast(1)
        val digits = if (account.totpDigits == 8) 8 else 6
        val secret = decodeBase32(normalizedTotpSecret(account.totpSecret))
        if (secret.isEmpty()) return@runCatching "------"
        val counter = nowMillis / 1000L / period
        val message = ByteArray(8)
        for (index in 7 downTo 0) message[index] = (counter ushr ((7 - index) * 8)).toByte()
        val mac = Mac.getInstance("Hmac$algorithm")
        mac.init(SecretKeySpec(secret, "Hmac$algorithm"))
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        val modulus = if (digits == 8) 100_000_000 else 1_000_000
        (binary % modulus).toString().padStart(digits, '0').chunked(3).joinToString(" ")
    }.getOrDefault("------")
}

@Composable
private fun rememberClock(): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    return nowMillis
}

@Composable
private fun accountTopBarColors() = TopAppBarDefaults.topAppBarColors(
    // 每个页面使用完整的一套色板，切换主题时不混用明暗不同的顶栏和内容区。
    containerColor = LocalAccountThemePalette.current.topBar,
    titleContentColor = LocalAccountThemePalette.current.topBarText,
    navigationIconContentColor = LocalAccountThemePalette.current.topBarText,
    actionIconContentColor = LocalAccountThemePalette.current.topBarText
)

private sealed interface AppPage {
    data object Unlock : AppPage
    data object Home : AppPage
    data object Groups : AppPage
    data object Settings : AppPage
    data object Backup : AppPage
    data class Detail(val accountId: String) : AppPage
    data class Edit(val accountId: String?) : AppPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountApp(
    themeMode: String = "dark",
    onThemeModeChange: (String) -> Unit = {},
    accentTheme: String = "green",
    onAccentThemeChange: (String) -> Unit = {},
    customThemeJson: String = "",
    onCustomThemeJsonChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val store = remember { SecureVaultStore(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<AppPage>(AppPage.Unlock) }
    var selectedGroupId by remember { mutableStateOf("default") }
    var groups by remember { mutableStateOf(initialGroups) }
    var accounts by remember { mutableStateOf(emptyList<Account>()) }
    var dataKey by remember { mutableStateOf<ByteArray?>(null) }
    var lockGeneration by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(AppSettings(customThemeJson = customThemeJson)) }
    var passwordConfigured by remember { mutableStateOf(store.hasMasterPassword()) }

    LaunchedEffect(Unit) {
        val values = context.settingsDataStore.data.first()
        settings = AppSettings(
            biometricEnabled = values[BIOMETRIC_SETTING] ?: false,
            autoLockMinutes = (values[AUTO_LOCK_SETTING] ?: 5).coerceAtLeast(1),
            themeMode = values[THEME_MODE_SETTING] ?: themeMode,
            accentTheme = values[ACCENT_THEME_SETTING] ?: accentTheme,
            languageTag = values[LANGUAGE_TAG_SETTING] ?: "zh-CN",
            customThemeJson = values[CUSTOM_THEME_JSON_SETTING] ?: customThemeJson,
            customThemes = decodeSavedThemes(values[CUSTOM_THEMES_SETTING].orEmpty())
        )
        onThemeModeChange(settings.themeMode)
        onAccentThemeChange(settings.accentTheme)
        onCustomThemeJsonChange(settings.customThemeJson)
    }

    fun persistSettings(value: AppSettings) {
        scope.launch {
            context.settingsDataStore.edit {
                it[BIOMETRIC_SETTING] = value.biometricEnabled
                it[AUTO_LOCK_SETTING] = value.autoLockMinutes
                it[THEME_MODE_SETTING] = value.themeMode
                it[ACCENT_THEME_SETTING] = value.accentTheme
                it[LANGUAGE_TAG_SETTING] = value.languageTag
                it[CUSTOM_THEME_JSON_SETTING] = value.customThemeJson
                it[CUSTOM_THEMES_SETTING] = encodeSavedThemes(value.customThemes)
            }
        }
    }

    fun persistVault() {
        val key = dataKey ?: return
        runCatching { store.save(PersistedVault(accounts = accounts, groups = groups, selectedGroupId = selectedGroupId), key) }
    }

    fun finishUnlock(key: ByteArray, state: PersistedVault) {
        dataKey?.fill(0)
        dataKey = key
        accounts = state.accounts
        groups = state.groups.ifEmpty { initialGroups }
        selectedGroupId = state.selectedGroupId.ifBlank { "default" }
        page = AppPage.Home
    }

    fun lockApp() {
        dataKey?.fill(0)
        dataKey = null
        accounts = emptyList()
        lockGeneration++
        page = AppPage.Unlock
    }

    fun authenticateBiometric(onError: () -> Unit = {}) {
        val host = activity as? FragmentActivity ?: return onError()
        if (!settings.biometricEnabled || !store.biometricAvailable()) return onError()
        val cipher = runCatching { store.beginBiometricDecrypt() }.getOrNull() ?: return onError()
        val encryptedDek = store.biometricCiphertext() ?: return onError()
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val key = runCatching { result.cryptoObject?.cipher?.doFinal(encryptedDek) }.getOrNull()
                val state = key?.let { store.load(it) }
                if (key != null && state != null) finishUnlock(key, state) else onError()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError() }
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
            settings = settings.copy(biometricEnabled = false)
            persistSettings(settings)
            return
        }
        val host = activity as? FragmentActivity ?: return
        val key = dataKey ?: return
        if (!store.biometricAvailable()) return
        val cipher = runCatching { store.beginBiometricEncrypt() }.getOrNull() ?: return
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runCatching { store.saveBiometricWrapped(result.cryptoObject?.cipher ?: cipher, key) }
                    .onSuccess {
                        settings = settings.copy(biometricEnabled = true)
                        persistSettings(settings)
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

    DisposableEffect(activity, settings.autoLockMinutes) {
        if (activity == null) return@DisposableEffect onDispose { }
        var firstStart = true
        val handler = Handler(Looper.getMainLooper())
        val lockRunnable = Runnable { lockApp() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (firstStart) firstStart = false else lockApp()
                    handler.removeCallbacks(lockRunnable)
                }
                Lifecycle.Event.ON_STOP -> handler.postDelayed(lockRunnable, settings.autoLockMinutes * 60 * 1000L)
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            handler.removeCallbacks(lockRunnable)
            activity.lifecycle.removeObserver(observer)
        }
    }

    fun createGroup(name: String): String {
        val id = "custom-${System.currentTimeMillis()}"
        groups = groups + Group(id, name.trim(), GroupKind.CUSTOM)
        // 立即保存分组，避免用户离开账号编辑页但未保存账号时丢失分组。
        persistVault()
        return id
    }

    when (val current = page) {
        AppPage.Unlock -> UnlockScreen(
            firstUse = !passwordConfigured,
            biometricEnabled = settings.biometricEnabled && store.biometricAvailable(),
            resetKey = lockGeneration,
            onBiometricUnlock = { authenticateBiometric() },
            onUnlock = { password ->
                if (!passwordConfigured) {
                    val initialState = PersistedVault(accounts = initialAccounts, groups = initialGroups)
                    val key = store.createInitial(password, initialState)
                    passwordConfigured = true
                    finishUnlock(key, initialState)
                    true
                } else {
                    val unlocked = store.unlockWithPassword(password)
                    if (unlocked != null) {
                        finishUnlock(unlocked.first, unlocked.second)
                        true
                    } else false
                }
            }
        )

        AppPage.Home -> HomeScreen(
            accounts = accounts,
            groups = groups,
            selectedGroupId = selectedGroupId,
            onGroupSelected = { selectedGroupId = it; persistVault() },
            onNewAccount = { page = AppPage.Edit(null) },
            onEditAccount = { page = AppPage.Edit(it) },
            onManageGroups = { page = AppPage.Groups },
            onOpenSettings = { page = AppPage.Settings },
            onOpenDetail = { page = AppPage.Detail(it) }
        )

        AppPage.Groups -> GroupManageScreen(
            groups = groups,
            onBack = { page = AppPage.Home },
            onAddGroup = { val id = createGroup(it); persistVault(); id },
            onRenameGroup = { id, name -> groups = groups.map { if (it.id == id) it.copy(name = name) else it }; persistVault() },
            onDeleteGroup = { id ->
                groups = groups.filterNot { it.id == id }
                accounts = accounts.map { it.copy(groups = it.groups - id) }
                if (selectedGroupId == id) selectedGroupId = "default"
                persistVault()
            },
            onMoveCustomGroup = { id, direction ->
                val fixed = groups.take(2)
                val custom = groups.drop(2).toMutableList()
                val index = custom.indexOfFirst { it.id == id }
                val target = index + direction
                if (index >= 0 && target in custom.indices) {
                    val item = custom.removeAt(index)
                    custom.add(target, item)
                    groups = fixed + custom
                    persistVault()
                }
            }
        )

        AppPage.Settings -> SettingsScreen(
            biometricEnabled = settings.biometricEnabled,
            biometricAvailable = store.biometricAvailable(),
            onToggleBiometric = ::configureBiometric,
            autoLockMinutes = settings.autoLockMinutes,
            onAutoLockChange = { minutes ->
                settings = settings.copy(autoLockMinutes = minutes.coerceAtLeast(1))
                persistSettings(settings)
            },
            themeMode = settings.themeMode,
            onThemeModeChange = { mode ->
                val normalized = mode.lowercase().let { if (it == "light" || it == "system") it else "dark" }
                settings = settings.copy(themeMode = normalized)
                persistSettings(settings)
                onThemeModeChange(normalized)
            },
            accentTheme = settings.accentTheme,
            onAccentThemeChange = { accent ->
                val normalized = if (accent == "blue") "blue" else "green"
                // 选择内置配色时退出 JSON 自定义主题，避免两个色板同时生效。
                settings = settings.copy(accentTheme = normalized, customThemeJson = "")
                persistSettings(settings)
                onAccentThemeChange(normalized)
                onCustomThemeJsonChange("")
            },
            customThemeJson = settings.customThemeJson,
            customThemes = settings.customThemes,
            onApplyThemeJson = { json ->
                val parsed = parseThemeJson(json)
                if (parsed == null) {
                    false
                } else {
                    val mode = parsed.defaultMode
                    settings = settings.copy(customThemeJson = json.trim(), themeMode = mode)
                    persistSettings(settings)
                    onCustomThemeJsonChange(settings.customThemeJson)
                    onThemeModeChange(mode)
                    true
                }
            },
            onSaveCustomTheme = { name, json ->
                val parsed = parseThemeJson(json)
                if (parsed != null) {
                    val saved = SavedTheme("custom-${System.currentTimeMillis()}", name.ifBlank { parsed.name }, json.trim())
                    settings = settings.copy(customThemes = (settings.customThemes + saved).distinctBy { it.id })
                    persistSettings(settings)
                }
            },
            onDeleteCustomTheme = { id ->
                settings = settings.copy(customThemes = settings.customThemes.filterNot { it.id == id })
                persistSettings(settings)
            },
            onBack = { page = AppPage.Home },
            languageTag = settings.languageTag,
            onOpenBackup = { page = AppPage.Backup }
        )

        AppPage.Backup -> BackupScreen(
            onBack = { page = AppPage.Settings },
            onCreateBackup = {
                val material = store.masterKeyMaterial()
                if (material == null) {
                    Result.failure(IllegalStateException("未找到主密码密钥，请重新解锁"))
                } else {
                    val (key, salt) = material
                    runCatching {
                        exportAcc(
                            AccExportInput(
                                PersistedVault(accounts = accounts, groups = groups, selectedGroupId = selectedGroupId),
                                settings
                            ),
                            key,
                            salt
                        )
                    }.also {
                        key.fill(0)
                        salt.fill(0)
                    }
                }
            },
            onImportBackup = { bytes, password ->
                if (!store.verifyMasterPassword(password)) null else importAcc(bytes, password).getOrNull()
            },
            onApplyImport = { imported ->
                accounts = imported.vault.accounts
                groups = imported.vault.groups.ifEmpty { initialGroups }
                selectedGroupId = imported.vault.selectedGroupId.ifBlank { "default" }
                settings = imported.settings
                persistVault()
                persistSettings(settings)
                onThemeModeChange(settings.themeMode)
                onAccentThemeChange(settings.accentTheme)
                onCustomThemeJsonChange(settings.customThemeJson)
                page = AppPage.Home
            }
        )

        is AppPage.Detail -> AccountDetailScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            onBack = { page = AppPage.Home },
            onEdit = { page = AppPage.Edit(current.accountId) }
        )

        is AppPage.Edit -> AccountEditScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            groups = groups,
            initialGroupId = selectedGroupId,
            onBack = { page = AppPage.Home },
            onCreateGroup = ::createGroup,
            onSave = { edited ->
                accounts = if (accounts.any { it.id == edited.id }) {
                    accounts.map { if (it.id == edited.id) edited else it }
                } else {
                    accounts + edited
                }
                persistVault()
                page = AppPage.Home
            }
        )
    }
}

@Composable
private fun UnlockScreen(
    firstUse: Boolean,
    biometricEnabled: Boolean,
    resetKey: Int,
    onBiometricUnlock: () -> Unit,
    onUnlock: (String) -> Boolean
) {
    var password by remember(resetKey) { mutableStateOf("") }
    var confirmation by remember(resetKey) { mutableStateOf("") }
    var error by remember(resetKey) { mutableStateOf("") }
    var showPassword by remember(resetKey, biometricEnabled) { mutableStateOf(firstUse || !biometricEnabled) }

    LaunchedEffect(firstUse, biometricEnabled) {
        if (!firstUse && biometricEnabled) onBiometricUnlock()
    }
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("账号本子", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (firstUse) "首次使用 · 设置主密码" else "输入主密码以解锁", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(42.dp))
        if (showPassword) OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("主密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        if (firstUse) {
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次输入主密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        if (showPassword) androidx.compose.material3.Button(onClick = {
            if (!isMasterPasswordValid(password)) error = "主密码长度需为 4-20 个字符"
            else if (firstUse && password != confirmation) error = "两次输入的主密码不一致"
            else if (!onUnlock(password)) error = "无法解锁"
            else error = ""
        }, enabled = isMasterPasswordValid(password), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(if (firstUse) "设置并进入" else "解锁")
        }
        if (!firstUse && biometricEnabled) TextButton(onClick = onBiometricUnlock) { Text("◉ 使用指纹 / 面容解锁") }
        if (!firstUse && !showPassword) TextButton(onClick = { showPassword = true }) { Text("使用主密码") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    accounts: List<Account>,
    groups: List<Group>,
    selectedGroupId: String,
    onGroupSelected: (String) -> Unit,
    onNewAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onManageGroups: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var previewAccount by remember { mutableStateOf<Account?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId } ?: groups.first()
    val visibleAccounts = accounts.filter { account ->
        val inGroup = when (selectedGroup.kind) {
            GroupKind.DEFAULT -> account.groups.isEmpty()
            GroupKind.DYNAMIC -> account.hasTotp
            GroupKind.CUSTOM -> selectedGroup.id in account.groups
        }
        inGroup && (searchQuery.isBlank() || account.name.contains(searchQuery, true) || account.username.contains(searchQuery, true))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = accountTopBarColors(),
                title = {
                    if (searchOpen) OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("搜索账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    else Text("账号本子", color = LocalAccountThemePalette.current.topBarText, fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = onNewAccount) { Text("＋", color = LocalAccountThemePalette.current.topBarText, fontSize = 24.sp) }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) { Text("⌕", color = LocalAccountThemePalette.current.topBarText, fontSize = 22.sp) }
                    IconButton(onClick = onManageGroups) { Text("☰", color = LocalAccountThemePalette.current.topBarText, fontSize = 20.sp) }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            GroupSidebar(groups, selectedGroupId, onGroupSelected, onManageGroups, onOpenSettings, Modifier.width(132.dp).fillMaxHeight())
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                Text("${selectedGroup.name} · ${visibleAccounts.size} 条", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                if (visibleAccounts.isEmpty()) {
                    EmptyState(if (selectedGroup.kind == GroupKind.DYNAMIC) "在账号编辑页添加两步验证" else "暂无账号", Modifier.fillMaxWidth().weight(1f))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(visibleAccounts, key = { it.id }) { account ->
                            AccountCard(account, selectedGroup.kind == GroupKind.DYNAMIC, nowMillis, { previewAccount = account }, { onEditAccount(account.id) })
                        }
                    }
                }
            }
        }
    }
    previewAccount?.let { account -> AccountPreviewSheet(account, { previewAccount = null }, { previewAccount = null; onEditAccount(account.id) }, { previewAccount = null; onOpenDetail(account.id) }) }
}

@Composable
private fun GroupSidebar(
    groups: List<Group>,
    selectedGroupId: String,
    onGroupSelected: (String) -> Unit,
    onManageGroups: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val rowsPerColumn = maxOf(1, ((maxHeight.value - 8f) / 48f).toInt())
            Column {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.chunked(rowsPerColumn).forEach { column ->
                        Column(modifier = Modifier.width(116.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            column.forEach { group ->
                                val selected = group.id == selectedGroupId
                                val selectionColor by animateColorAsState(
                                    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    label = "group-selection-color"
                                )
                                Surface(
                                    color = selectionColor,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(if (selected) 1.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                        .clickable { onGroupSelected(group.id) }
                                ) {
                                    Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
                                }
                            }
                        }
                    }
                }
                if (groups.size > rowsPerColumn) Text("← 左右滑动查看更多 →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider()
        TextButton(onClick = onManageGroups, modifier = Modifier.fillMaxWidth()) { Text("⚙ 分组管理") }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("设置") }
    }
}

@Composable
private fun AccountCard(account: Account, showTotp: Boolean, nowMillis: Long, onClick: () -> Unit, onEdit: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(account.name.take(1), fontWeight = FontWeight.Bold) }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showTotp && account.hasTotp) "${totpCode(account, nowMillis)}  ·  ${account.totpPeriod - (nowMillis / 1000L % account.totpPeriod)} 秒" else account.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (account.hasTotp) Text("2FA", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onEdit) { Text("⋮") }
        }
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPreviewSheet(account: Account, onDismiss: () -> Unit, onEdit: () -> Unit, onDetail: () -> Unit) {
    val nowMillis = rememberClock()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(account.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick = onDetail) { Text("详情") }; TextButton(onClick = onEdit) { Text("编辑") } }
            Spacer(Modifier.height(12.dp))
            SensitiveValueRow("用户名", account.username)
            SensitiveValueRow("密码", account.password, masked = true, sensitive = true)
            if (account.hasTotp) SensitiveValueRow("动态密码", totpCode(account, nowMillis), sensitive = true)
            account.customFields.forEach { field -> SensitiveValueRow(field.label, field.value, masked = field.hidden, sensitive = field.hidden) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 写入剪贴板并只在内容仍未被用户替换时自动清除。Android 不需要申请剪贴板权限。 */
private fun copyToClipboard(context: Context, text: String, sensitive: Boolean) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("account", text))
    if (sensitive) Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == text) clipboard.clearPrimaryClip()
    }, 30_000L)
}

@Composable
private fun SensitiveValueRow(label: String, value: String, masked: Boolean = false, sensitive: Boolean = masked) {
    val context = LocalContext.current
    var revealed by remember(value, masked) { mutableStateOf(!masked) }
    val displayed = if (masked && !revealed) "••••••••" else value
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(
            displayed,
            modifier = Modifier.weight(1f).clickable {
                if (masked && !revealed) revealed = true else copyToClipboard(context, value, sensitive)
            },
            fontWeight = if (masked && !revealed) FontWeight.Normal else FontWeight.Medium
        )
        TextButton(onClick = { copyToClipboard(context, value, sensitive) }) { Text("复制") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupManageScreen(
    groups: List<Group>,
    onBack: () -> Unit,
    onAddGroup: (String) -> String,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMoveCustomGroup: (String, Int) -> Unit
) {
    var dialogGroup by remember { mutableStateOf<Group?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(colors = accountTopBarColors(), title = { Text("分组管理", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }, actions = { TextButton(onClick = { adding = true; dialogText = "" }) { Text("＋ 新增", color = LocalAccountThemePalette.current.topBarText) } })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("固定分组（可改名）", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(groups.take(2), key = { it.id }) { group -> GroupManageItem(group, true, {}, {}, { dialogGroup = group; dialogText = group.name }, {}) }
            item { Text("自定义分组（长按拖动排序）", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
            items(groups.drop(2), key = { it.id }) { group ->
                val index = groups.drop(2).indexOf(group)
                GroupManageItem(group, false, { onMoveCustomGroup(group.id, -1) }, { onMoveCustomGroup(group.id, 1) }, { dialogGroup = group; dialogText = group.name }, { onDeleteGroup(group.id) }, index == 0, index == groups.drop(2).lastIndex)
            }
            item { Text("固定分组不可删除或排序；删除自定义分组不会删除账号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
    if (adding || dialogGroup != null) {
        val title = if (adding) "新增分组" else "修改分组名称"
        AlertDialog(onDismissRequest = { adding = false; dialogGroup = null }, title = { Text(title) }, text = { OutlinedTextField(dialogText, { dialogText = it }, label = { Text("分组名称") }, singleLine = true) }, confirmButton = {
            TextButton(enabled = dialogText.isNotBlank(), onClick = {
                if (adding) onAddGroup(dialogText) else dialogGroup?.let { onRenameGroup(it.id, dialogText.trim()) }
                adding = false; dialogGroup = null
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { adding = false; dialogGroup = null }) { Text("取消") } })
    }
}

@Composable
private fun GroupManageItem(
    group: Group,
    fixed: Boolean,
    moveUp: () -> Unit,
    moveDown: () -> Unit,
    rename: () -> Unit,
    delete: () -> Unit,
    first: Boolean = false,
    last: Boolean = false
) {
    var dragDistance by remember(group.id) { mutableStateOf(0f) }
    var dragOffsetY by remember(group.id) { mutableStateOf(0f) }
    var isDragging by remember(group.id) { mutableStateOf(false) }
    val dragModifier = if (fixed) Modifier else Modifier.pointerInput(group.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { isDragging = true; dragOffsetY = 0f; dragDistance = 0f },
            onDrag = { change, amount ->
                change.consume()
                dragOffsetY += amount.y
                dragDistance += amount.y
                if (dragDistance > 48f) { moveDown(); dragDistance -= 48f; dragOffsetY -= 56f }
                if (dragDistance < -48f) { moveUp(); dragDistance += 48f; dragOffsetY += 56f }
            },
            onDragEnd = { dragDistance = 0f; dragOffsetY = 0f; isDragging = false },
            onDragCancel = { dragDistance = 0f; dragOffsetY = 0f; isDragging = false }
        )
    }
    val cardColor by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "group-drag-color"
    )
    val cardElevation by animateDpAsState(if (isDragging) 8.dp else 1.dp, label = "group-drag-elevation")
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                if (isDragging) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                }
            }
            .then(dragModifier)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!fixed) Text(if (isDragging) "↕" else "☷", color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            Text(group.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = rename) { Text("改名") }
            if (!fixed) TextButton(onClick = delete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditScreen(
    account: Account?,
    groups: List<Group>,
    initialGroupId: String,
    onBack: () -> Unit,
    onCreateGroup: (String) -> String,
    onSave: (Account) -> Unit
) {
    var name by remember(account?.id) { mutableStateOf(account?.name.orEmpty()) }
    var username by remember(account?.id) { mutableStateOf(account?.username.orEmpty()) }
    var password by remember(account?.id) { mutableStateOf(account?.password.orEmpty()) }
    var hasTotp by remember(account?.id) { mutableStateOf(account?.hasTotp ?: false) }
    var totpSecret by remember(account?.id) { mutableStateOf(account?.totpSecret.orEmpty()) }
    val nowMillis = rememberClock()
    var selectedCustomGroups by remember(account?.id, initialGroupId) { mutableStateOf(account?.groups ?: if (groups.any { it.id == initialGroupId && it.kind == GroupKind.CUSTOM }) setOf(initialGroupId) else emptySet()) }
    var fields by remember(account?.id) { mutableStateOf(account?.customFields ?: emptyList()) }
    var addFieldHidden by remember { mutableStateOf<Boolean?>(null) }
    var addGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var showMissingName by remember { mutableStateOf(false) }
    val customGroups = groups.filter { it.kind == GroupKind.CUSTOM }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(colors = accountTopBarColors(), title = { Text(if (account == null) "新建账号" else "编辑账号", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }, actions = {
            TextButton(onClick = {
                if (name.isBlank()) showMissingName = true else onSave(Account(account?.id ?: "account-${System.currentTimeMillis()}", name.trim(), username, password, selectedCustomGroups, hasTotp, normalizedTotpSecret(totpSecret.trim()), account?.totpDigits ?: 6, account?.totpPeriod ?: 30, account?.totpAlgorithm ?: "SHA1", fields))
            }) { Text("保存", color = LocalAccountThemePalette.current.topBarText) }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("账号名称 *") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Text("分组", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    customGroups.forEach { group ->
                        FilterChip(selected = group.id in selectedCustomGroups, onClick = { selectedCustomGroups = if (group.id in selectedCustomGroups) selectedCustomGroups - group.id else selectedCustomGroups + group.id }, label = { Text(group.name) })
                    }
                    FilterChip(selected = false, onClick = { addGroupDialog = true; newGroupName = "" }, label = { Text("＋ 增加组") })
                }
                Text("当前进入：${groups.firstOrNull { it.id == initialGroupId }?.name ?: "默认"}；动态密码由 TOTP 自动决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { AccountFieldItem("用户名", username, false, onValueChange = { username = it }) }
            item { AccountFieldItem("密码", password, true, onValueChange = { password = it }) }
            items(fields, key = { it.id }) { field ->
                AccountFieldItem(field.label, field.value, field.hidden, { value -> fields = fields.map { if (it.id == field.id) it.copy(value = value) else it } }, { fields = fields.filterNot { it.id == field.id } })
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { addFieldHidden = false }) { Text("＋ 添加字段") }; TextButton(onClick = { addFieldHidden = true }) { Text("＋ 添加隐藏字段") } } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) { Text("两步验证", style = MaterialTheme.typography.titleMedium); Text(if (hasTotp) "已配置 · 自动显示在动态密码分组" else "未配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(checked = hasTotp, onCheckedChange = { hasTotp = it })
                }
            }
            if (hasTotp) {
                item { OutlinedTextField(totpSecret, { totpSecret = it }, label = { Text("TOTP 密钥（Base32）") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    val normalizedSecret = normalizedTotpSecret(totpSecret)
                    val decodedLength = decodeBase32(normalizedSecret).size
                    Text(
                        when {
                            totpSecret.isBlank() -> "请输入密钥以生成验证码"
                            decodedLength < 10 -> "TOTP 密钥格式不正确，请检查 Base32 内容"
                            else -> "当前验证码：${totpCode(Account("preview", name, username, password, hasTotp = true, totpSecret = normalizedSecret), nowMillis)}"
                        },
                        color = if (decodedLength in 1..9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            item { TextButton(onClick = {}) { Text("删除账号", color = MaterialTheme.colorScheme.error) } }
        }
    }

    addFieldHidden?.let { hidden ->
        var label by remember(hidden) { mutableStateOf("") }
        var value by remember(hidden) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addFieldHidden = null }, title = { Text(if (hidden) "添加隐藏字段" else "添加字段") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(label, { label = it }, label = { Text("字段名称") }, singleLine = true); OutlinedTextField(value, { value = it }, label = { Text("字段内容") }, singleLine = true) } }, confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { fields = fields + AccountField("field-${System.currentTimeMillis()}", label.trim(), value, hidden); addFieldHidden = null }) { Text("添加") } }, dismissButton = { TextButton(onClick = { addFieldHidden = null }) { Text("取消") } })
    }
    if (addGroupDialog) {
        AlertDialog(onDismissRequest = { addGroupDialog = false }, title = { Text("新增分组") }, text = { OutlinedTextField(newGroupName, { newGroupName = it }, label = { Text("分组名称") }, singleLine = true) }, confirmButton = { TextButton(enabled = newGroupName.isNotBlank() && customGroups.none { it.name == newGroupName.trim() }, onClick = { selectedCustomGroups = selectedCustomGroups + onCreateGroup(newGroupName); addGroupDialog = false }) { Text("创建并选择") } }, dismissButton = { TextButton(onClick = { addGroupDialog = false }) { Text("取消") } })
    }
    if (showMissingName) AlertDialog(onDismissRequest = { showMissingName = false }, title = { Text("缺少账号名称") }, text = { Text("请输入账号名称后再保存。") }, confirmButton = { TextButton(onClick = { showMissingName = false }) { Text("确定") } })
}

@Composable
private fun AccountFieldItem(label: String, value: String, hidden: Boolean, onValueChange: (String) -> Unit, onDelete: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.weight(1f))
        if (onDelete != null) TextButton(onClick = onDelete) { Text("删除") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailScreen(account: Account?, onBack: () -> Unit, onEdit: () -> Unit) {
    val nowMillis = rememberClock()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(colors = accountTopBarColors(), title = { Text(account?.name ?: "账号详情", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }, actions = { TextButton(onClick = onEdit) { Text("编辑", color = LocalAccountThemePalette.current.topBarText) } })
    }) { padding ->
        if (account == null) {
            EmptyState("账号不存在", Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text("分组：${account.groups.joinToString().ifBlank { "默认" }}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) }
                item { Text("登录信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                item { SensitiveValueRow("用户名", account.username) }
                item { SensitiveValueRow("密码", account.password, masked = true, sensitive = true) }
                item { Text("两步验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
                item { if (account.hasTotp) SensitiveValueRow("动态密码", totpCode(account, nowMillis), sensitive = true) else Text("未配置", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(account.customFields, key = { it.id }) { field -> SensitiveValueRow(field.label, field.value, masked = field.hidden, sensitive = field.hidden) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    autoLockMinutes: Int,
    onAutoLockChange: (Int) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    accentTheme: String,
    onAccentThemeChange: (String) -> Unit,
    customThemeJson: String,
    customThemes: List<SavedTheme>,
    languageTag: String,
    onApplyThemeJson: (String) -> Boolean,
    onSaveCustomTheme: (String, String) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit
) {
    val context = LocalContext.current
    val presets = remember { defaultThemePresets() }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var draftThemeJson by remember(customThemeJson) { mutableStateOf(customThemeJson.ifBlank { presets.first().json }) }
    var jsonError by remember { mutableStateOf("") }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(colors = accountTopBarColors(), title = { Text("设置", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SettingsHeader("安全") }
            item { SettingsRow("自动锁定", "$autoLockMinutes 分钟") { showAutoLockDialog = true } }
            item {
                SettingsRow(
                    "生物识别解锁",
                    when {
                        !biometricAvailable -> "设备不支持"
                        biometricEnabled -> "开启"
                        else -> "关闭"
                    },
                    if (biometricAvailable) { { onToggleBiometric(!biometricEnabled) } } else null
                )
            }
            item { SettingsHeader("剪贴板与外观") }
            item { SettingsRow("敏感内容自动清除", "30 秒") }
            item {
                SettingsRow(
                    "明暗模式",
                    when (themeMode) {
                        "light" -> "浅色"
                        "system" -> "跟随系统"
                        else -> "深色"
                    }
                ) { showThemeDialog = true }
            }
            item {
                SettingsRow("配色方案", if (accentTheme == "blue") "蓝色" else "绿色（设计）") { showAccentDialog = true }
            }
            item {
                SettingsRow("自定义主题 JSON", if (customThemeJson.isBlank()) "未启用" else "已启用") {
                    draftThemeJson = customThemeJson.ifBlank { presets.first().json }
                    jsonError = ""
                    showJsonDialog = true
                }
            }
            if (customThemes.isNotEmpty()) {
                item { SettingsHeader("已保存的自定义主题") }
                items(customThemes, key = { it.id }) { saved ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { draftThemeJson = saved.json; showJsonDialog = true }, modifier = Modifier.weight(1f), content = { Text(saved.name) })
                        TextButton(onClick = { onDeleteCustomTheme(saved.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { SettingsRow("语言", languageLabel(languageTag)) }
            item { SettingsHeader("数据与备份") }
            item { SettingsRow("加密备份", "打开", onOpenBackup) }
        }
    }
    if (showAutoLockDialog) AlertDialog(
        onDismissRequest = { showAutoLockDialog = false },
        title = { Text("自动锁定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 5, 10, 30).forEach { minutes ->
                    TextButton(onClick = { onAutoLockChange(minutes); showAutoLockDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("${minutes} 分钟")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAutoLockDialog = false }) { Text("取消") } }
    )
    if (showThemeDialog) AlertDialog(
        onDismissRequest = { showThemeDialog = false },
        title = { Text("颜色主题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统").forEach { (mode, label) ->
                    TextButton(onClick = { onThemeModeChange(mode); showThemeDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("取消") } }
    )
    if (showAccentDialog) AlertDialog(
        onDismissRequest = { showAccentDialog = false },
        title = { Text("配色方案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("green" to "绿色（设计）", "blue" to "蓝色").forEach { (accent, label) ->
                    TextButton(onClick = { onAccentThemeChange(accent); showAccentDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAccentDialog = false }) { Text("取消") } }
    )
    if (showJsonDialog) AlertDialog(
        onDismissRequest = { showJsonDialog = false },
        title = { Text("自定义主题 JSONC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("示例主题（可直接载入后修改）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { preset -> TextButton(onClick = { draftThemeJson = preset.json; jsonError = "" }) { Text(preset.name) } }
                }
                OutlinedTextField(
                    value = draftThemeJson,
                    onValueChange = { draftThemeJson = it; jsonError = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                    minLines = 10,
                    maxLines = 20,
                    label = { Text("主题配置（支持 // 和 /* */ 注释）") }
                )
                if (jsonError.isNotBlank()) Text(jsonError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("account-theme.jsonc", draftThemeJson))
                    }) { Text("复制 JSON") }
                    TextButton(onClick = {
                        val parsed = parseThemeJson(draftThemeJson)
                        if (parsed == null) jsonError = "JSON 或颜色格式无效" else onSaveCustomTheme(parsed.name, draftThemeJson)
                    }) { Text("保存副本") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onApplyThemeJson(draftThemeJson)) {
                    jsonError = ""
                    showJsonDialog = false
                } else jsonError = "JSON 或颜色格式无效"
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = { showJsonDialog = false }) { Text("取消") } }
    )
}

/** 当前只提供简体中文；未知标签回退中文，后续可直接增加语言资源。 */
private fun languageLabel(languageTag: String): String = when (languageTag) {
    "zh-CN" -> "简体中文"
    else -> "简体中文"
}

@Composable
private fun SettingsHeader(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)) }

@Composable
private fun SettingsRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Card(onClick = onClick ?: {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, modifier = Modifier.weight(1f)); Text(value, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScreen(
    onBack: () -> Unit,
    onCreateBackup: () -> Result<ByteArray>,
    onImportBackup: (ByteArray, String) -> AccImportResult?,
    onApplyImport: (AccImportResult) -> Unit
) {
    val context = LocalContext.current
    var showExportReminder by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf("") }
    var exportSucceeded by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportPassword by remember { mutableStateOf("") }
    var pendingImportResult by remember { mutableStateOf<AccImportResult?>(null) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { fileUri ->
        val bytes = pendingBytes
        if (fileUri != null && bytes != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(fileUri)?.use { it.write(bytes) }
                    ?: error("无法写入所选文件")
            }
            exportSucceeded = result.isSuccess
            exportError = result.exceptionOrNull()?.message ?: "导出失败，请更换保存位置后重试"
            if (result.isSuccess) exportError = "导出成功：文件已保存"
        } else if (bytes != null) {
            exportSucceeded = false
            exportError = "未选择保存位置"
        }
        bytes?.fill(0)
        pendingBytes = null
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            pendingImportPassword = ""
            return@rememberLauncherForActivityResult
        }
        val password = pendingImportPassword
        val result = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取备份文件")
            try {
                onImportBackup(bytes, password) ?: error("主密码错误或文件已损坏")
            } finally {
                bytes.fill(0)
            }
        }.getOrNull()
        pendingImportPassword = ""
        if (result == null) {
            importError = "备份密码错误或文件已损坏"
            showImportPasswordDialog = true
        } else {
            pendingImportResult = result
            showImportConfirmDialog = true
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(colors = accountTopBarColors(), title = { Text("加密备份", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本地加密备份", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("导出使用当前主密码作为备份密码，不需要再次输入。系统会请求你选择保存文件的位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { exportError = ""; exportSucceeded = false; showExportReminder = true }) { Text("导出加密备份 .acc") }
            TextButton(onClick = { importPassword = ""; importError = ""; showImportPasswordDialog = true }) { Text("从 .acc 恢复") }
            if (exportError.isNotBlank()) Text(exportError, color = if (exportSucceeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showExportReminder) AlertDialog(
        onDismissRequest = { showExportReminder = false },
        title = { Text("导出加密备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导出文件使用当前主密码加密。导入时必须输入相同的主密码，请务必记住。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onCreateBackup()
                if (result.isFailure) {
                    exportSucceeded = false
                    exportError = result.exceptionOrNull()?.message ?: "备份生成失败，请先解锁后重试"
                    showExportReminder = false
                } else {
                    pendingBytes = result.getOrThrow()
                    showExportReminder = false
                    val filename = "account-${java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())}.acc"
                    createDocument.launch(filename)
                }
            }) { Text("选择位置并导出", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = { showExportReminder = false }) { Text("取消", color = MaterialTheme.colorScheme.primary) } }
    )
    if (showImportPasswordDialog) AlertDialog(
        onDismissRequest = { showImportPasswordDialog = false },
        title = { Text("恢复加密备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请输入当前主密码以验证并解密备份。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(importPassword, { importPassword = it; importError = "" }, label = { Text("主密码（4-20 个字符）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!isMasterPasswordValid(importPassword)) {
                    importError = "主密码长度需为 4-20 个字符"
                } else {
                    pendingImportPassword = importPassword
                    showImportPasswordDialog = false
                    openDocument.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                }
            }) { Text("选择文件", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = { showImportPasswordDialog = false }) { Text("取消", color = MaterialTheme.colorScheme.primary) } }
    )
    if (showImportConfirmDialog) {
        val result = pendingImportResult
        if (result != null) AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false; pendingImportResult = null },
            title = { Text("确认恢复") },
            text = { Text("将替换当前密码库，导入 ${result.vault.accounts.size} 个账号和 ${result.vault.groups.size} 个分组，并应用备份中的软件设置。") },
            confirmButton = {
                TextButton(onClick = {
                    onApplyImport(result)
                    showImportConfirmDialog = false
                    pendingImportResult = null
                }) { Text("确认恢复", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showImportConfirmDialog = false; pendingImportResult = null }) { Text("取消", color = MaterialTheme.colorScheme.primary) } }
        )
    }
}
