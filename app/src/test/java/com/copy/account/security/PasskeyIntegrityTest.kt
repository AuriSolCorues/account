/**
 * PasskeyIntegrity 单测：PendingIntent 完整性标签的 tag/verify 语义。
 * JVM 环境注入本地 SecretKeySpec（AndroidKeyStore 在单测不可用），
 * 验证往返、篡改拒绝、空标签拒绝——Activity 端闸门依赖的全部行为。
 */
package com.copy.account.security

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyIntegrityTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    private val requestJson = """{"rpId":"github.com","challenge":"abc123","user":{"name":"octocat"}}"""

    @Test
    fun `tag then verify roundtrip`() {
        val tag = PasskeyIntegrity.tag(requestJson, key)
        assertTrue(PasskeyIntegrity.verify(requestJson, tag, key))
    }

    @Test
    fun `tampered payload rejected`() {
        val tag = PasskeyIntegrity.tag(requestJson, key)
        val evil = requestJson.replace("github.com", "evil.example")
        assertFalse(PasskeyIntegrity.verify(evil, tag, key))
    }

    @Test
    fun `tampered tag rejected`() {
        val tag = PasskeyIntegrity.tag(requestJson, key)
        tag[0] = (tag[0].toInt() xor 0x01).toByte()
        assertFalse(PasskeyIntegrity.verify(requestJson, tag, key))
    }

    @Test
    fun `null tag rejected`() {
        assertFalse(PasskeyIntegrity.verify(requestJson, null, key))
    }

    @Test
    fun `foreign key rejected`() {
        val tag = PasskeyIntegrity.tag(requestJson, key)
        val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")
        assertFalse(PasskeyIntegrity.verify(requestJson, tag, otherKey))
    }

    @Test
    fun `tag is 32 bytes deterministic for same payload and key`() {
        val t1 = PasskeyIntegrity.tag(requestJson, key)
        val t2 = PasskeyIntegrity.tag(requestJson, key)
        org.junit.Assert.assertArrayEquals(t1, t2)
        assertEquals(32, t1.size)
    }
}
