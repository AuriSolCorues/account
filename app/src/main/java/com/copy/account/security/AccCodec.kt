/**
 * 职责：.acc 备份文件的编解码。外层是明文 JSON（AccDocument：密码库密文 + 明文软件设置），
 *       内层 AccPayload 记录 KDF 参数与 AES-GCM 密文；导出复用当前主密码的 KEK 与盐，无需再输密码。
 * 架构位置：AccountApp 的导出/导入回调调 exportAcc/importAcc；产物字节交给 data/backup 双轨写盘。
 *           加密原语来自 security/Crypto.kt。
 * Python 类比：结构像一个 JSON 信封——{"passwordVault": base64(json(payload)), "appSettings": {...}}，
 *           payload 里再藏 base64 的 AES-GCM 密文；信封明文可读，盒子必须用备份密码打开。
 */
package com.copy.account.security

import java.util.Base64
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.MAX_AUTO_LOCK_MINUTES
import com.copy.account.data.model.PersistedVault
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
    val customThemes: List<com.copy.account.ui.theme.SavedTheme> = emptyList(),
    /** 真正的秒（默认 300 = 5 分钟）。名字里的 "Seconds" 名副其实，别被 AppSettings 的分钟单位误导。 */
    val autoLockSeconds: Int = 300,
    val clipboardClearSeconds: Int = 30,
    val allowScreenshots: Boolean = false
)

/** passwordVault 使用主密码派生的 KEK；导出和导入使用同一密码。 */
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

// 与 vaultJson 的分工：accJson 严格（未知字段即拒收，防把无关文件误当备份）；vaultJson 宽松
// （容忍旧版数据缺字段）。内外两层 .acc 解析都走 accJson 的严格模式。
private val accJson = Json {
    encodeDefaults = true
    // 外层格式保持严格，根节点出现未知字段时直接拒绝，避免误读其他格式。
    ignoreUnknownKeys = false
}

/** 生成完整 .acc；密钥来自本次主密码解锁会话，绝不落盘。 */
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
                    // 存档单位是秒而内存里是分钟，这里是唯一的换算点；0（关闭）原样保留为 0。
                    autoLockSeconds = settings.autoLockMinutes.coerceIn(0, MAX_AUTO_LOCK_MINUTES) * 60,
                    clipboardClearSeconds = settings.clipboardClearSeconds,
                    allowScreenshots = settings.allowScreenshots
                )
            )
        ).toByteArray(Charsets.UTF_8)
    } finally {
        // 明文字节用完立刻清零：GC 时代这是尽力而为的内存卫生，缩短敏感数据在堆上的暴露窗口。
        plain.fill(0)
    }
}

/** 完整校验并解密 .acc；失败时不修改当前密码库或设置。 */
internal fun importAcc(bytes: ByteArray, password: String): Result<AccImportResult> = runCatching {
    require(isMasterPasswordValid(password)) { "备份密码长度需为 4-20 个字符" }
    val document = accJson.decodeFromString(AccDocument.serializer(), bytes.toString(Charsets.UTF_8))
    val payloadBytes = Base64.getDecoder().decode(document.passwordVault)
    val payload = accJson.decodeFromString(AccPayload.serializer(), payloadBytes.toString(Charsets.UTF_8))
    // 逐条 require 校验版本/KDF/迭代数/字段长度，任一不满足抛 IllegalArgumentException
    // （被外层 runCatching 捕成 Result.failure）——先验形再解密，防误读与参数降级攻击。
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
                // 导入按秒解回分钟（1.3 版备份的 autoLockSeconds 就是秒，漏掉这个 /60 会让 5 分钟变成 5 小时）；
                // 放宽下界到 0 才能导入「关闭自动锁定」，上界对齐 24 小时。
                autoLockMinutes = (settings.autoLockSeconds / 60).coerceIn(0, MAX_AUTO_LOCK_MINUTES),
                clipboardClearSeconds = settings.clipboardClearSeconds.coerceIn(0, 86_400),
                allowScreenshots = settings.allowScreenshots
            )
        )
    } finally {
        plain.fill(0)
    }
}
