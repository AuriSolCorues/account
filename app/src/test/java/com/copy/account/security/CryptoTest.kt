package com.copy.account.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 纯 JVM 可测的加密原语：不依赖 Android 类，随 `./gradlew test` 在本地跑。 */
class CryptoTest {

    @Test
    fun passwordHash_deterministic_and_32_bytes() {
        val salt = ByteArray(16) { it.toByte() }
        val a = passwordHash("测试密码123", salt, 10_000)
        val b = passwordHash("测试密码123", salt, 10_000)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun passwordHash_differs_on_salt_or_password() {
        val salt1 = ByteArray(16) { it.toByte() }
        val salt2 = ByteArray(16) { (it + 1).toByte() }
        val a = passwordHash("密码", salt1, 10_000)
        val b = passwordHash("密码", salt2, 10_000)
        val c = passwordHash("密码2", salt1, 10_000)
        assertFalse(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun passwordKeyMaterial_separates_verifier_from_wrapping_key() {
        val salt = ByteArray(16) { it.toByte() }
        val material = passwordKeyMaterial("测试密码123", salt, 10_000)
        try {
            assertEquals(32, material.wrappingKey.size)
            assertEquals(32, material.verifier.size)
            assertFalse(material.wrappingKey.contentEquals(material.verifier))
        } finally {
            material.clear()
        }
    }

    @Test
    fun backupKeyMaterial_clear_wipes_session_key() {
        val material = BackupKeyMaterial(ByteArray(32) { 1 }, ByteArray(16) { 2 }, 10_000)
        material.clear()
        assertTrue(material.key.all { it == 0.toByte() })
        assertTrue(material.salt.all { it == 0.toByte() })
    }

    @Test
    fun encrypt_decrypt_roundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "hello 账号本子".toByteArray(Charsets.UTF_8)
        val payload = encryptBytes(key, plain)
        val decrypted = decryptBytes(key, payload.iv, payload.ciphertext)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun decrypt_wrongKey_fails() {
        val key = ByteArray(32) { it.toByte() }
        val wrong = ByteArray(32) { (it + 1).toByte() }
        val payload = encryptBytes(key, "secret".toByteArray())
        try {
            decryptBytes(wrong, payload.iv, payload.ciphertext)
            fail("错误密钥应解密失败")
        } catch (_: Exception) {
            // 预期：AEADBadTagException 等
        }
    }

    @Test
    fun masterPasswordValid_boundaries() {
        assertTrue(isMasterPasswordValid("abcd"))
        assertTrue(isMasterPasswordValid("账号本子")) // 4 个中文字符
        assertTrue(isMasterPasswordValid("12345678901234567890")) // 20
        assertFalse(isMasterPasswordValid("abc")) // 3
        assertFalse(isMasterPasswordValid("123456789012345678901")) // 21
        assertFalse(isMasterPasswordValid(""))
    }
}
