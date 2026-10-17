/**
 * 职责：官方 androidx.credentials.webauthn 组包行为断言（产线路径）——迁移后自拼协议已拆，
 *       此类锁住官方类在产线调用方式下的输出契约：
 *       - Create：attestationObject 内 authData 的 AAGUID 必须 16 字节（自拼版曾 2 字节被 Chrome 拒）、
 *         fmt=none、attStmt 空、COSE 公钥原样内嵌；
 *       - 传 clientDataHash 时 json() 省略 clientDataJSON 字段（浏览器会替换自己那份——官方设计）；
 *       - Get：dataToSign() = authData || clientDataHash（签名基准与浏览器视角字节一致）；
 *       - patchSignCount 回填后 dataToSign 前缀随之变化（签的就是回填后的 authData）。
 * 测试策略：Robolectric（官方类用 android.util.Base64 与 org.json）；密钥为固定字节，不碰 KeyStore。
 */
package com.copy.account.security

import androidx.credentials.webauthn.AuthenticatorAssertionResponse
import androidx.credentials.webauthn.AuthenticatorAttestationResponse
import androidx.credentials.webauthn.FidoPublicKeyCredential
import androidx.credentials.webauthn.PublicKeyCredentialCreationOptions
import androidx.credentials.webauthn.PublicKeyCredentialRequestOptions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // 官方包仅用 android.util.Base64/org.json；上限 35 即可（targetSdk 37 超 Robolectric 默认）
class WebAuthnOfficialPackagingTest {

    private val createRequestJson = """
        {"rp":{"name":"GitHub","id":"github.com"},
         "user":{"id":"dXNlcg","name":"octocat","displayName":"octocat"},
         "challenge":"c2hhbGxvdw",
         "pubKeyCredParams":[{"type":"public-key","alg":-7}],
         "attestation":"none"}
    """.trimIndent()

    private val getRequestJson = """
        {"rpId":"github.com","challenge":"c2hhbGxvdw","userVerification":"required"}
    """.trimIndent()

    private val clientDataHash = ByteArray(32) { 9 }

    /** 官方 Create 响应（产线 PasskeyCreateActivity 的调用形状）。 */
    private fun createResponse(coseBytes: ByteArray, credId: ByteArray): AuthenticatorAttestationResponse =
        AuthenticatorAttestationResponse(
            requestOptions = PublicKeyCredentialCreationOptions(createRequestJson),
            credentialId = credId,
            credentialPublicKey = coseBytes,
            origin = "https://github.com",
            up = true,
            uv = true,
            be = false,
            bs = false,
            packageName = null,
            clientDataHash = clientDataHash
        )

    @Test
    fun attestation_authData_aaguid_is_16_zero_bytes() {
        // b2~b6 被拒的最终根因回归锁：AAGUID 16 字节定长（自拼版只写 2 字节 → authData
        // 150 字节 ≠ 164 → Chrome ParseForResponseFields 必挂）。官方实现 new byte[16]。
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        val credId = ByteArray(32) { (it + 1).toByte() }
        val authData = attestationAuthData(createResponse(coseBytes, credId))
        // 37 头部 + AAGUID(16) + credIdLen(2) + credId(32) + COSE 公钥 = 官方定长布局
        assertEquals(37 + 16 + 2 + 32 + coseBytes.size, authData.size)
        // AAGUID 全零（37..52）
        assertTrue(authData.copyOfRange(37, 53).all { it == 0.toByte() })
        // 凭据长度 2 字节大端 = 32，凭据本体紧随其后
        assertEquals(32, ((authData[53].toInt() and 0xff) shl 8) or (authData[54].toInt() and 0xff))
        assertArrayEquals(credId, authData.copyOfRange(55, 87))
        // COSE 公钥逐字节原样内嵌
        assertArrayEquals(coseBytes, authData.copyOfRange(87, authData.size))
    }

    @Test
    fun attestation_is_none_format_with_up_uv_at_flags() {
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32), ByteArray(32)))
        val decoded = cborDecode(createResponse(coseBytes, ByteArray(32)).attestationObject)
        assertTrue(decoded is CborValue.Map)
        val entries = (decoded as CborValue.Map).entries
        fun entry(key: String) = entries.first {
            it.first is CborValue.Text && (it.first as CborValue.Text).v == key
        }.second
        assertEquals("none", (entry("fmt") as CborValue.Text).v)
        assertTrue((entry("attStmt") as CborValue.Map).entries.isEmpty())
        // flags：UP|UV|AT = 0x45（be/bs=false → 无 0x08/0x10）
        val authData = (entry("authData") as CborValue.Bytes).v
        assertEquals(0x45, authData[32].toInt() and 0xff)
        // rpIdHash = SHA-256("github.com")
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("github.com".toByteArray())
        assertArrayEquals(expectedHash, authData.copyOfRange(0, 32))
    }

    @Test
    fun credential_json_omits_clientDataJSON_when_hash_given() {
        // 官方设计（AuthenticatorResponse.json）：clientDataHash != null → 整个省略
        // clientDataJSON；浏览器会用自己那份替换（Chromium 注释认可缺失宽容）。
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32), ByteArray(32)))
        val json = FidoPublicKeyCredential(ByteArray(32) { 7 }, createResponse(coseBytes, ByteArray(32)), "platform")
            .json()
        assertFalse(json.contains("clientDataJSON"))
        assertTrue(json.contains("\"type\":\"public-key\""))
        assertTrue(json.contains("\"authenticatorAttachment\":\"platform\""))
        assertTrue(json.contains("\"clientExtensionResults\""))
        assertTrue(json.contains("\"attestationObject\":\"" + base64Url(createResponse(coseBytes, ByteArray(32)).attestationObject) + "\""))
    }

    @Test
    fun assertion_dataToSign_uses_official_hash_and_patched_count() {
        val requestOptions = PublicKeyCredentialRequestOptions(getRequestJson)
        val credId = ByteArray(16) { 3 }
        val response = AuthenticatorAssertionResponse(
            requestOptions = requestOptions,
            credentialId = credId,
            origin = "https://github.com",
            up = true,
            uv = true,
            be = false,
            bs = false,
            userHandle = ByteArray(4) { 5 },
            packageName = null,
            clientDataHash = clientDataHash
        )
        // 官方默认 authData：37 字节、signCount 恒 0、flags UP|UV=0x05
        assertEquals(37, response.authenticatorData.size)
        assertEquals(0x05, response.authenticatorData[32].toInt() and 0xff)
        val defaultCount = ((response.authenticatorData[33].toInt() and 0xff) shl 24) or
            ((response.authenticatorData[34].toInt() and 0xff) shl 16) or
            ((response.authenticatorData[35].toInt() and 0xff) shl 8) or
            (response.authenticatorData[36].toInt() and 0xff)
        assertEquals(0, defaultCount)
        // dataToSign() = authData || clientDataHash（浏览器流程签名基准）
        val dataToSign = response.dataToSign()
        assertArrayEquals(response.authenticatorData, dataToSign.copyOfRange(0, 37))
        assertArrayEquals(clientDataHash, dataToSign.copyOfRange(37, 69))

        // patchSignCount 回填后：计数出现在 authData 大端位，dataToSign 前缀随之变化
        response.authenticatorData = patchSignCount(response.authenticatorData, 1)
        assertEquals(1, response.authenticatorData[36].toInt())
        val patchedDataToSign = response.dataToSign()
        assertArrayEquals(response.authenticatorData, patchedDataToSign.copyOfRange(0, 37))
        assertEquals(69, patchedDataToSign.size)
    }

    @Test
    fun assertion_json_carries_response_fields() {
        val requestOptions = PublicKeyCredentialRequestOptions(getRequestJson)
        val credId = ByteArray(16) { 3 }
        val response = AuthenticatorAssertionResponse(
            requestOptions = requestOptions,
            credentialId = credId,
            origin = "https://github.com",
            up = true,
            uv = true,
            be = false,
            bs = false,
            userHandle = ByteArray(4) { 5 },
            packageName = null,
            clientDataHash = clientDataHash
        )
        response.authenticatorData = patchSignCount(response.authenticatorData, 1)
        response.signature = byteArrayOf(9, 8, 7)
        val json = FidoPublicKeyCredential(credId, response, "platform").json()
        // 传 hash 时无 clientDataJSON；authenticatorData/signature/userHandle 全 base64url
        assertFalse(json.contains("clientDataJSON"))
        assertTrue(json.contains("\"authenticatorData\":\"" + base64Url(response.authenticatorData) + "\""))
        assertTrue(json.contains("\"signature\":\"" + base64Url(byteArrayOf(9, 8, 7)) + "\""))
        assertTrue(json.contains("\"userHandle\":\"" + base64Url(ByteArray(4) { 5 }) + "\""))
        // 顶层 id/rawId = credentialId 的 base64url
        assertTrue(json.contains("\"rawId\":\"" + base64Url(credId) + "\""))
    }

    @Test
    fun create_response_json_carries_chrome_required_fields() {
        // b7 实锤回归锁：Chrome CredMan 直连通道（value_conversions.cc
        // MakeCredentialResponseFromValue）在 attestationObject 之外必查
        // publicKeyAlgorithm/authenticatorData/publicKey；官方 json() 缺这三字段被拒。
        // 产线补丁（PasskeyCreateActivity）= KeePassDX vendor 版 json() 的字段集。
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        val credId = ByteArray(32) { (it + 7).toByte() }
        val response = createResponse(coseBytes, credId)
        val derBytes = ByteArray(91) { (it % 251).toByte() }

        // ===== 复刻产线补丁逻辑（与 PasskeyCreateActivity 保持同构）=====
        val patched = org.json.JSONObject(
            FidoPublicKeyCredential(credId, response, "platform").json()
        ).apply {
            val resp = getJSONObject("response")
            resp.put("publicKeyAlgorithm", COSE_ALG_ES256)
            resp.put("authenticatorData", base64Url(attestationAuthData(response.attestationObject)))
            resp.put("publicKey", base64Url(derBytes))
        }

        val responseObj = patched.getJSONObject("response")
        assertEquals(-7, responseObj.getInt("publicKeyAlgorithm"))
        // authenticatorData 与 attestationObject 内逐字节同源
        assertEquals(
            base64Url(attestationAuthData(response.attestationObject)),
            responseObj.getString("authenticatorData")
        )
        // publicKey 必须 base64url：无标准 Base64 的 '+'、'/'、'='（b5 实锤坑）
        val pk = responseObj.getString("publicKey")
        assertTrue(!pk.contains('+') && !pk.contains('/') && !pk.contains('='))
        assertArrayEquals(derBytes, base64UrlDecode(pk))
    }

    @Test
    fun attestation_authData_helper_roundtrips_official_bytes() {
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32), ByteArray(32)))
        val response = createResponse(coseBytes, ByteArray(32) { 3 })
        val authData = attestationAuthData(response.attestationObject)
        // 37 头部 + AAGUID(16) + credIdLen(2) + credId(32) + COSE 公钥
        assertEquals(37 + 16 + 2 + 32 + coseBytes.size, authData.size)
        // rpIdHash 抽查：SHA-256("github.com") 前 8 字节
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("github.com".toByteArray())
        assertArrayEquals(expected.copyOfRange(0, 8), authData.copyOfRange(0, 8))
    }

    /** 官方 attestationObject 解包出 authData（测试辅助）。 */
    private fun attestationAuthData(response: AuthenticatorAttestationResponse): ByteArray =
        (cborDecode(response.attestationObject) as CborValue.Map).entries
            .first { it.first is CborValue.Text && (it.first as CborValue.Text).v == "authData" }
            .second.let { (it as CborValue.Bytes).v }
}
