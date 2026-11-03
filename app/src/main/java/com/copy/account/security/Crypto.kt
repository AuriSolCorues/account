package com.copy.account.security

import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val DEFAULT_PASSWORD_ITERATIONS = 600_000

internal const val MIN_PASSWORD_ITERATIONS = 50_000
internal const val MAX_PASSWORD_ITERATIONS = 2_000_000

internal fun needsIterationUpgrade(storedIterations: Int, targetIterations: Int): Boolean =
    storedIterations != targetIterations

internal fun isMasterPasswordValid(password: String): Boolean =
    password.codePointCount(0, password.length) in 4..20

internal const val MASTER_PASSWORD_RULE_HINT = "主密码长度需为 4-20 个字符"

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

internal data class PasswordKeyMaterial(val wrappingKey: ByteArray, val verifier: ByteArray) {
    fun clear() {
        wrappingKey.fill(0)
        verifier.fill(0)
    }
}

internal fun passwordKeyMaterial(password: String, salt: ByteArray, iterations: Int = DEFAULT_PASSWORD_ITERATIONS): PasswordKeyMaterial {
    val spec = PBEKeySpec(normalizePassword(password).toCharArray(), salt, iterations, 512)
    val bytes = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
    return try {
        PasswordKeyMaterial(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
    } finally {
        bytes.fill(0)
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
