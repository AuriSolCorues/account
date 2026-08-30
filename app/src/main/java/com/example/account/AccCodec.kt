package com.example.account

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** .acc 的明文外层只保留密码库和软件设置两个字段。 */
@Serializable
private data class AccDocument(
    val passwordVault: String,
    val appSettings: AccSettings
)

/** 备份中的设置可读可编辑，但不包含本机凭据、生物识别或日志。 */
@Serializable
private data class AccSettings(
    val themeMode: String = "dark",
    val accentTheme: String = "green",
    val languageTag: String = "zh-CN",
    val customThemeJson: String = "",
    val customThemes: List<com.example.account.ui.theme.SavedTheme> = emptyList(),
    val autoLockSeconds: Int = 300,
    val clipboardClearSeconds: Int = 30
)

/** passwordVault 使用主密码派生的 KEK；导出和导入使用同一密码。 */
@Serializable
private data class AccPayload(
    val version: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val iterations: Int = PASSWORD_ITERATIONS,
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
    // 外层格式保持严格，根节点出现未知字段时直接拒绝，避免误读其他格式。
    ignoreUnknownKeys = false
}

/** 生成完整 .acc；调用方传入当前主密码的 KEK 和盐，界面无需再次输入密码。 */
internal fun exportAcc(input: AccExportInput, key: ByteArray, salt: ByteArray): ByteArray {
    require(key.size == 32) { "主密码密钥无效" }
    require(salt.size >= 16) { "主密码盐无效" }
    val plain = vaultJson.encodeToString(PersistedVault.serializer(), input.vault).toByteArray(Charsets.UTF_8)
    return try {
        val encrypted = encryptBytes(key, plain)
        val payload = AccPayload(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)
        )
        val settings = input.settings
        accJson.encodeToString(
            AccDocument.serializer(),
            AccDocument(
                passwordVault = Base64.encodeToString(
                    accJson.encodeToString(AccPayload.serializer(), payload).toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                ),
                appSettings = AccSettings(
                    themeMode = settings.themeMode,
                    accentTheme = settings.accentTheme,
                    languageTag = settings.languageTag,
                    customThemeJson = settings.customThemeJson,
                    customThemes = settings.customThemes,
                    autoLockSeconds = settings.autoLockMinutes.coerceAtLeast(1) * 60
                )
            )
        ).toByteArray(Charsets.UTF_8)
    } finally {
        plain.fill(0)
    }
}

/** 完整校验并解密 .acc；失败时不修改当前密码库或设置。 */
internal fun importAcc(bytes: ByteArray, password: String): Result<AccImportResult> = runCatching {
    require(isMasterPasswordValid(password)) { "主密码长度需为 4-20 个字符" }
    val document = accJson.decodeFromString(AccDocument.serializer(), bytes.toString(Charsets.UTF_8))
    val payloadBytes = Base64.decode(document.passwordVault, Base64.DEFAULT)
    val payload = accJson.decodeFromString(AccPayload.serializer(), payloadBytes.toString(Charsets.UTF_8))
    require(payload.version == 1) { "不支持的备份版本" }
    require(payload.kdf == "PBKDF2-HMAC-SHA256") { "不支持的密钥派生算法" }
    require(payload.iterations in 10_000..2_000_000) { "无效的密钥派生参数" }
    val salt = Base64.decode(payload.salt, Base64.DEFAULT)
    val iv = Base64.decode(payload.iv, Base64.DEFAULT)
    val ciphertext = Base64.decode(payload.ciphertext, Base64.DEFAULT)
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
                languageTag = if (settings.languageTag == "zh-CN") "zh-CN" else "zh-CN",
                customThemeJson = settings.customThemeJson,
                customThemes = settings.customThemes,
                autoLockMinutes = (settings.autoLockSeconds / 60).coerceIn(1, 120)
            )
        )
    } finally {
        plain.fill(0)
    }
}
