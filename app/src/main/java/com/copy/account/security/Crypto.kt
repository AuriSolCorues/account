/**
 * 职责：加密原语层——PBKDF2 主密码派生、AES-256-GCM 加解密、主密码合法性校验，
 *       外加全库共用的宽松 JSON 配置（vaultJson）。无 UI、无状态，纯 JVM 可单测。
 * 架构位置：security 包的最底层；VaultStore（库加密）与 AccCodec（.acc 导出导入）只调这里，
 *           其余代码不直接碰 javax.crypto。
 * Python 类比：passwordHash ≈ hashlib.pbkdf2_hmac("sha256", password, salt, iterations) 出 32 字节；
 *           AES/GCM 在 Python 需第三方 cryptography 库——GCM 特点是每次随机 IV，
 *           且密文自带完整性校验（被篡改则解密直接抛异常，≈ 加密 + HMAC 二合一）。
 */
package com.copy.account.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json

/** 新建密码库时的 PBKDF2 迭代次数；每个库会在 prefs 里单独记录，改这里只影响新建的库。 */
internal const val DEFAULT_PASSWORD_ITERATIONS = 300_000

// 宽松 JSON：写入默认值、读取容忍未知键——vault.bin 与 .acc 内层数据都用它，兼容旧版本数据。
internal val vaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// codePointCount 而非 length：Java/Kotlin 的 String 按 UTF-16 存储，一个增补平面字符占 2 个 char；
// 按码点数才等于用户直觉的「字符数」（≈ Python 里 len(str) 的语义）。
/** 主密码允许中文、英文、数字和符号，长度按 Unicode 码点计算。 */
internal fun isMasterPasswordValid(password: String): Boolean =
    password.codePointCount(0, password.length) in 4..20

private fun normalizePassword(password: String): String =
    Normalizer.normalize(password, Normalizer.Form.NFC)

// PBEKeySpec 是 JCA 对 PBKDF2 的封装。finally 里 clearPassword() 尽力清掉内部 char[]——
// JVM 有 GC、中途可能有副本，无法保证内存真被清零，但能缩短敏感材料在堆上的存活时间（全库同此约定）。
internal fun passwordHash(password: String, salt: ByteArray, iterations: Int = DEFAULT_PASSWORD_ITERATIONS): ByteArray {
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
