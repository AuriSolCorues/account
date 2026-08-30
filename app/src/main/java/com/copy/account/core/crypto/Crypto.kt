package com.copy.account.core.crypto

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

internal val vaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** 主密码允许中文、英文、数字和符号，长度按 Unicode 码点计算。 */
internal fun isMasterPasswordValid(password: String): Boolean =
    password.codePointCount(0, password.length) in 4..20

private fun normalizePassword(password: String): String =
    Normalizer.normalize(password, Normalizer.Form.NFC)

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
