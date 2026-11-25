package com.copy.account.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeKdfTest {

    private fun hex(s: String): ByteArray = s.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    @Test
    fun nativeLibraryIsLoaded_testsRunProductionBinary() {
        val dk = NativeKdf.derive("passwd".toByteArray(), "salt".toByteArray(), 10_000, 256)
        assertEquals(32, dk.size)
    }

    @Test
    fun rfc7914_vector2_jniEntry() {
        val dk = NativeKdf.derive("Password".toByteArray(), "NaCl".toByteArray(), 80_000, 512)
        assertArrayEquals(
            hex(
                "4ddcd8f60b98be21830cee5ef22701f9" +
                        "641a4418d04c0414aeff08876b34ab56" +
                        "a1d425a1225833549adb841b51c9b317" +
                        "6a272bdebba1d078478f62b397f33c8d"
            ),
            dk
        )
    }

    @Test
    fun utf8Password_matchesGoldenValue() {
        val pass = "测试密码123".toByteArray(Charsets.UTF_8)
        val salt = "盐值盐值盐值盐值".toByteArray(Charsets.UTF_8)
        val expected = hex(
            "4e7a3b9ceb3ac62a6d179940440175179767c80f1e8d113c6a67cebb9ab0e8eee317b5e510ec26fc74481e4b228e1b43645b1324b9f09ef9619fcbb56b1719f5"
        )
        val dk = NativeKdf.derive(pass, salt, 80_000, 512)
        assertArrayEquals(expected, dk)
    }

    @Test
    fun dkLen256_isPrefixOf512() {
        val short = NativeKdf.derive("测试密码123".toByteArray(), "盐值盐值盐值盐值".toByteArray(), 12_340, 256)
        val long = NativeKdf.derive("测试密码123".toByteArray(), "盐值盐值盐值盐值".toByteArray(), 12_340, 512)
        assertArrayEquals(short, long.copyOfRange(0, 32))
    }

    @Test
    fun passwordHash_usesNativeDerive() {
        assertEquals(32, passwordHash("passwd", "salt".toByteArray(), 10_000).size)
        val material = passwordKeyMaterial("Password", "NaCl".toByteArray(), 80_000)
        assertEquals(32, material.wrappingKey.size)
        assertEquals(32, material.verifier.size)
    }
}
