package com.copy.account.security

import java.util.Base64
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.MAX_AUTO_LOCK_SECONDS
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.SavedTheme
import com.copy.account.data.model.vaultJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class AccDocument(
    val passwordVault: String,
    val appSettings: AccSettings
)

@Serializable
private data class AccSettings(
    val themeMode: String = "dark",
    val accentTheme: String = "green",
    val languageTag: String = "zh-CN",
    val customThemeJson: String = "",
    val customThemes: List<SavedTheme> = emptyList(),
    val autoLockSeconds: Int = 60,
    val clipboardClearSeconds: Int = 30,
    val allowScreenshots: Boolean = false
)

@Serializable
private data class AccPayload(
    val version: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val iterations: Int = DEFAULT_PASSWORD_ITERATIONS,
    val salt: String,
    val iv: String,
    val ciphertext: String
)

internal data class AccExportInput(
    val vault: PersistedVault,
    val settings: AppSettings
)

internal data class AccImportResult(
    val vault: PersistedVault,
    val settings: AppSettings
)

private val accJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

internal fun exportAcc(input: AccExportInput, key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    require(key.size == 32) { "备份密钥无效，请先使用主密码解锁" }
    require(salt.size >= 16) { "备份盐无效，请先使用主密码解锁" }
    val plain = vaultJson.encodeToString(PersistedVault.serializer(), input.vault).toByteArray(Charsets.UTF_8)
    return try {
        val encrypted = encryptBytes(key, plain)
        val payload = AccPayload(
            iterations = iterations,
            salt = Base64.getEncoder().encodeToString(salt),
            iv = Base64.getEncoder().encodeToString(encrypted.iv),
            ciphertext = Base64.getEncoder().encodeToString(encrypted.ciphertext)
        )
        val settings = input.settings
        accJson.encodeToString(
            AccDocument.serializer(),
            AccDocument(
                passwordVault = Base64.getEncoder().encodeToString(
                    accJson.encodeToString(AccPayload.serializer(), payload).toByteArray(Charsets.UTF_8),
                ),
                appSettings = AccSettings(
                    themeMode = settings.themeMode,
                    accentTheme = settings.accentTheme,
                    languageTag = settings.languageTag,
                    customThemeJson = settings.customThemeJson,
                    customThemes = settings.customThemes,
                    autoLockSeconds = settings.autoLockSeconds.coerceIn(0, MAX_AUTO_LOCK_SECONDS),
                    clipboardClearSeconds = settings.clipboardClearSeconds,
                    allowScreenshots = settings.allowScreenshots
                )
            )
        ).toByteArray(Charsets.UTF_8)
    } finally {
        plain.fill(0)
    }
}

internal fun importAcc(bytes: ByteArray, password: String): Result<AccImportResult> = runCatching {
    require(isMasterPasswordValid(password)) { "备份密码长度需为 4-20 个字符" }
    val document = accJson.decodeFromString(AccDocument.serializer(), bytes.toString(Charsets.UTF_8))
    val payloadBytes = Base64.getDecoder().decode(document.passwordVault)
    val payload = accJson.decodeFromString(AccPayload.serializer(), payloadBytes.toString(Charsets.UTF_8))
    require(payload.version == 1) { "不支持的备份版本" }
    require(payload.kdf == "PBKDF2-HMAC-SHA256") { "不支持的密钥派生算法" }
    require(payload.iterations in 10_000..2_000_000) { "无效的密钥派生参数" }
    val salt = Base64.getDecoder().decode(payload.salt)
    val iv = Base64.getDecoder().decode(payload.iv)
    val ciphertext = Base64.getDecoder().decode(payload.ciphertext)
    require(salt.size >= 16 && iv.size == 12 && ciphertext.size > 16) { "备份数据不完整" }

    val key = passwordHash(password, salt, payload.iterations)
    val plain = try {
        decryptBytes(key, iv, ciphertext)
    } finally {
        key.fill(0)
    }
    try {
        val vault = vaultJson.decodeFromString(PersistedVault.serializer(), plain.toString(Charsets.UTF_8))
        val settings = document.appSettings
        AccImportResult(
            vault = vault,
            settings = AppSettings(
                themeMode = settings.themeMode.lowercase().let { if (it in setOf("dark", "light", "system")) it else "dark" },
                accentTheme = if (settings.accentTheme == "blue") "blue" else "green",
                languageTag = "zh-CN",
                customThemeJson = settings.customThemeJson,
                customThemes = settings.customThemes,
                autoLockSeconds = settings.autoLockSeconds.coerceIn(0, MAX_AUTO_LOCK_SECONDS),
                clipboardClearSeconds = settings.clipboardClearSeconds.coerceIn(0, 86_400),
                allowScreenshots = settings.allowScreenshots
            )
        )
    } finally {
        plain.fill(0)
    }
}
