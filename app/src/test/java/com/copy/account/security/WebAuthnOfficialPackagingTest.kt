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
@Config(sdk = [35])
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
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        val credId = ByteArray(32) { (it + 1).toByte() }
        val authData = attestationAuthData(createResponse(coseBytes, credId))
        assertEquals(37 + 16 + 2 + 32 + coseBytes.size, authData.size)
        assertTrue(authData.copyOfRange(37, 53).all { it == 0.toByte() })
        assertEquals(32, ((authData[53].toInt() and 0xff) shl 8) or (authData[54].toInt() and 0xff))
        assertArrayEquals(credId, authData.copyOfRange(55, 87))
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
        val authData = (entry("authData") as CborValue.Bytes).v
        assertEquals(0x45, authData[32].toInt() and 0xff)
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("github.com".toByteArray())
        assertArrayEquals(expectedHash, authData.copyOfRange(0, 32))
    }

    @Test
    fun credential_json_omits_clientDataJSON_when_hash_given() {
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
        assertEquals(37, response.authenticatorData.size)
        assertEquals(0x05, response.authenticatorData[32].toInt() and 0xff)
        val defaultCount = ((response.authenticatorData[33].toInt() and 0xff) shl 24) or
            ((response.authenticatorData[34].toInt() and 0xff) shl 16) or
            ((response.authenticatorData[35].toInt() and 0xff) shl 8) or
            (response.authenticatorData[36].toInt() and 0xff)
        assertEquals(0, defaultCount)
        val dataToSign = response.dataToSign()
        assertArrayEquals(response.authenticatorData, dataToSign.copyOfRange(0, 37))
        assertArrayEquals(clientDataHash, dataToSign.copyOfRange(37, 69))

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
        assertFalse(json.contains("clientDataJSON"))
        assertTrue(json.contains("\"authenticatorData\":\"" + base64Url(response.authenticatorData) + "\""))
        assertTrue(json.contains("\"signature\":\"" + base64Url(byteArrayOf(9, 8, 7)) + "\""))
        assertTrue(json.contains("\"userHandle\":\"" + base64Url(ByteArray(4) { 5 }) + "\""))
        assertTrue(json.contains("\"rawId\":\"" + base64Url(credId) + "\""))
    }

    @Test
    fun create_response_json_carries_chrome_required_fields() {
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        val credId = ByteArray(32) { (it + 7).toByte() }
        val response = createResponse(coseBytes, credId)
        val derBytes = ByteArray(91) { (it % 251).toByte() }

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
        assertEquals(
            base64Url(attestationAuthData(response.attestationObject)),
            responseObj.getString("authenticatorData")
        )
        val pk = responseObj.getString("publicKey")
        assertTrue(!pk.contains('+') && !pk.contains('/') && !pk.contains('='))
        assertArrayEquals(derBytes, base64UrlDecode(pk))
    }

    @Test
    fun attestation_authData_helper_roundtrips_official_bytes() {
        val coseBytes = cborEncode(coseEs256PublicKey(ByteArray(32), ByteArray(32)))
        val response = createResponse(coseBytes, ByteArray(32) { 3 })
        val authData = attestationAuthData(response.attestationObject)
        assertEquals(37 + 16 + 2 + 32 + coseBytes.size, authData.size)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("github.com".toByteArray())
        assertArrayEquals(expected.copyOfRange(0, 8), authData.copyOfRange(0, 8))
    }

    private fun attestationAuthData(response: AuthenticatorAttestationResponse): ByteArray =
        (cborDecode(response.attestationObject) as CborValue.Map).entries
            .first { it.first is CborValue.Text && (it.first as CborValue.Text).v == "authData" }
            .second.let { (it as CborValue.Bytes).v }
}
