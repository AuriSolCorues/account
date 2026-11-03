package com.copy.account.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthnTest {

    @Test
    fun cbor_head_small_values() {
        assertArrayEquals(byteArrayOf(0x00), cborEncode(CborValue.Unsigned(0)))
        assertArrayEquals(byteArrayOf(0x17), cborEncode(CborValue.Unsigned(23)))
    }

    @Test
    fun cbor_head_extended_lengths() {
        assertArrayEquals(byteArrayOf(0x18, 0x18), cborEncode(CborValue.Unsigned(24)))
        assertArrayEquals(byteArrayOf(0x18.toByte(), 0x64.toByte()), cborEncode(CborValue.Unsigned(100)))
        assertArrayEquals(
            byteArrayOf(0x19.toByte(), 0x03, 0xe8.toByte()),
            cborEncode(CborValue.Unsigned(1000))
        )
        assertArrayEquals(
            byteArrayOf(0x1a.toByte(), 0x00, 0x0f, 0x42, 0x40),
            cborEncode(CborValue.Unsigned(1_000_000))
        )
    }

    @Test
    fun cbor_negative_integers() {
        assertArrayEquals(byteArrayOf(0x20.toByte()), cborEncode(CborValue.Negative(-1)))
        assertArrayEquals(
            byteArrayOf(0x39.toByte(), 0x03, 0xe7.toByte()),
            cborEncode(CborValue.Negative(-1000))
        )
        assertEquals(CborValue.Negative(-1000), cborDecode(cborEncode(CborValue.Negative(-1000))))
    }

    @Test
    fun cbor_text_string() {
        assertArrayEquals(
            byteArrayOf(0x64, 0x49, 0x45, 0x54, 0x46),
            cborEncode(CborValue.Text("IETF"))
        )
    }

    @Test
    fun cbor_bytes_string() {
        assertArrayEquals(
            byteArrayOf(0x44, 0x01, 0x02, 0x03, 0x04),
            cborEncode(CborValue.Bytes(byteArrayOf(1, 2, 3, 4)))
        )
    }

    @Test
    fun cbor_array_and_map_official_vector() {
        val array = CborValue.Array(listOf(CborValue.Unsigned(1), CborValue.Unsigned(2), CborValue.Unsigned(3)))
        assertArrayEquals(byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03), cborEncode(array))
        val map = CborValue.Map(
            listOf(
                CborValue.Text("a") to CborValue.Unsigned(1),
                CborValue.Text("b") to CborValue.Array(listOf(CborValue.Unsigned(2), CborValue.Unsigned(3)))
            )
        )
        assertArrayEquals(
            byteArrayOf(0xa2.toByte(), 0x61, 0x61, 0x01, 0x61, 0x62, 0x82.toByte(), 0x02, 0x03),
            cborEncode(map)
        )
    }

    @Test
    fun cbor_roundtrip_all_types() {
        val original = CborValue.Map(
            listOf(
                CborValue.Unsigned(1) to CborValue.Unsigned(2),
                CborValue.Unsigned(3) to CborValue.Negative(-7),
                CborValue.Text("id") to CborValue.Bytes(ByteArray(32) { it.toByte() }),
                CborValue.Text("nested") to CborValue.Array(listOf(CborValue.Text("x"), CborValue.Unsigned(9)))
            )
        )
        val decoded = cborDecode(cborEncode(original))
        assertEquals(original, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cbor_decode_rejects_truncated() {
        cborDecode(byteArrayOf(0x19.toByte(), 0x03))
    }

    @Test
    fun cose_key_structure_matches_webauthn_spec() {
        val x = ByteArray(32) { 0x11 }
        val y = ByteArray(32) { 0x22 }
        val encoded = cborEncode(coseEs256PublicKey(x, y))
        val decoded = cborDecode(encoded) as CborValue.Map
        val entries = decoded.entries
        assertEquals(5, entries.size)
        assertEquals(CborValue.Unsigned(2), entries[0].second)
        assertEquals(CborValue.Negative(-7), entries[1].second)
        assertEquals(CborValue.Unsigned(1), entries[2].second)
        assertArrayEquals(x, (entries[3].second as CborValue.Bytes).v)
        assertArrayEquals(y, (entries[4].second as CborValue.Bytes).v)
    }

    @Test
    fun der_signature_no_padding_needed() {
        val raw = ByteArray(64) { 0x01 }
        val der = es256RawToDer(raw)
        assertEquals(70, der.size)
        assertEquals(0x30, der[0].toInt())
        assertEquals(68, der[1].toInt())
        assertEquals(0x02, der[2].toInt())
        assertEquals(32, der[3].toInt())
    }

    @Test
    fun der_signature_pads_high_bit_and_strips_leading_zero() {
        val raw = ByteArray(64) { if (it < 32) 0x81.toByte() else 0x01 }
        val der = es256RawToDer(raw)
        assertEquals(71, der.size)
        assertEquals(0x30, der[0].toInt())
        assertEquals(0x02, der[2].toInt())
        assertEquals(0x21.toByte(), der[3])
        assertEquals(0x00, der[4].toInt())
        assertEquals(0x02, der[37].toInt())
        assertEquals(0x20, der[38].toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun der_signature_rejects_wrong_length() {
        es256RawToDer(ByteArray(63))
    }

    @Test
    fun sign_adapts_raw_to_der() {
        val raw = ByteArray(64) { 0x01 }
        assertEquals(70, es256Adapt(raw).size)
    }

    @Test
    fun sign_passthrough_der() {
        val der = ByteArray(72) { 0x30.toByte() }
        assertTrue(der.contentEquals(es256Adapt(der)))
    }

    private fun es256Adapt(out: ByteArray): ByteArray {
        return if (out.size == 64) es256RawToDer(out) else out
    }

    @Test
    fun parse_create_request_minimal() {
        val json = """{"rp":{"id":"github.com"},"challenge":"abc123"}"""
        val req = parseWebAuthnRequest(json)!!
        assertEquals("github.com", req.rpId)
        assertEquals("abc123", req.challengeBase64Url)
        assertNull(req.userName)
    }

    @Test
    fun parse_get_request_toplevel_rpId() {
        val json = """{"challenge":"abc123","rpId":"github.com","userVerification":"required"}"""
        val req = parseWebAuthnRequest(json)!!
        assertEquals("github.com", req.rpId)
        assertEquals("abc123", req.challengeBase64Url)
        assertNull(req.userName)
    }

    @Test
    fun parse_create_request_full() {
        val json = """
            {"rp":{"id":"google.com"},"challenge":"zzz",
             "user":{"name":"user@example.com","id":"dXNlcg"}}
        """.trimIndent()
        val req = parseWebAuthnRequest(json)!!
        assertEquals("google.com", req.rpId)
        assertEquals("zzz", req.challengeBase64Url)
        assertEquals("user@example.com", req.userName)
        assertEquals("dXNlcg", req.userIdBase64Url)
    }

    @Test
    fun parse_request_rejects_missing_rp_or_challenge() {
        assertNull(parseWebAuthnRequest("""{"challenge":"x"}"""))
        assertNull(parseWebAuthnRequest("""{"rp":{"id":"x.com"}}"""))
        assertNull(parseWebAuthnRequest("""{"rpId":"x.com"}"""))
        assertNull(parseWebAuthnRequest("not json"))
    }

    @Test
    fun base64url_roundtrip_no_padding() {
        val bytes = ByteArray(33) { it.toByte() }
        val encoded = base64Url(bytes)
        assertTrue(!encoded.contains("="))
        assertArrayEquals(bytes, base64UrlDecode(encoded))
    }

    @Test
    fun parse_request_extracts_pubKeyCredParams_and_allowCredentials() {
        val createJson = """
            {"rp":{"id":"github.com"},"challenge":"c2hhbGxvdw","pubKeyCredParams":[
              {"type":"public-key","alg":-8},{"type":"public-key","alg":-7}],
             "user":{"id":"dXNlcg","name":"octocat"}}
        """.trimIndent()
        val create = parseWebAuthnRequest(createJson)!!
        assertEquals(listOf(-8, -7), create.pubKeyCredParams)
        assertEquals("octocat", create.userName)

        val getJson = """
            {"rpId":"github.com","challenge":"c2hhbGxvdw",
             "allowCredentials":[{"type":"public-key","id":"YWJj"},{"type":"public-key","id":"eHl6"}]}
        """.trimIndent()
        val get = parseWebAuthnRequest(getJson)!!
        assertEquals(listOf("YWJj", "eHl6"), get.allowCredentialIds)
    }

    @Test
    fun software_key_pair_round_trip_sign_and_verify() {
        val (privateB64, publicB64, publicDerB64) = generateSoftwareEs256KeyPair()
        assertEquals(64, java.util.Base64.getDecoder().decode(publicB64).size)
        assertTrue(publicDerB64.isNotBlank())
        val derDecoded = java.util.Base64.getDecoder().decode(publicDerB64)
        assertEquals(0x30, derDecoded[0].toInt() and 0xff)
        val restored = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(derDecoded))
        assertEquals("EC", restored.algorithm)
        val data = "authData||clientDataHash".toByteArray()
        val signature = es256AdaptRaw(softwareSigner(privateB64).apply { update(data) }.sign())
        assertEquals(0x30, signature[0].toInt() and 0xff)
        val xy = java.util.Base64.getDecoder().decode(publicB64)
        val kf = java.security.KeyFactory.getInstance("EC")
        val ecPoint = java.security.spec.ECPoint(
            java.math.BigInteger(1, xy.copyOfRange(0, 32)),
            java.math.BigInteger(1, xy.copyOfRange(32, 64))
        )
        val params = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val publicKey = kf.generatePublic(java.security.spec.ECPublicKeySpec(ecPoint, params))
            as java.security.interfaces.ECPublicKey
        val verifier = java.security.Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        assertTrue(verifier.verify(signature))
    }
}
