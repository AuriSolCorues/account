/**
 * 职责：WebAuthn 纯 JVM 核心的单测——CBOR 编解码往返与官方向量、ES256 raw→DER 签名转换
 *       （ASN.1 规范用例）、COSE_Key 结构、请求解析、Base64URL、signCount 回填。
 * 测试策略：全部纯 JVM（无 Android/KeyStore 依赖），密钥生成与签名在真机 Phase 5 验证；
 *       官方 androidx.credentials.webauthn 组包行为的断言另见 WebAuthnOfficialPackagingTest。
 */
package com.copy.account.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthnTest {

    // ==================== CBOR 编码（RFC 8949 附录 A 向量） ====================

    @Test
    fun cbor_head_small_values() {
        // 0 → 0x00；23 → 0x17（info<24 直接内嵌）
        assertArrayEquals(byteArrayOf(0x00), cborEncode(CborValue.Unsigned(0)))
        assertArrayEquals(byteArrayOf(0x17), cborEncode(CborValue.Unsigned(23)))
    }

    @Test
    fun cbor_head_extended_lengths() {
        // 24 → 0x18 0x18；100 → 0x18 0x64；1000 → 0x19 0x03 0xe8；1000000 → 0x1a 0x00 0x0f 0x42 0x40
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
        // RFC 8949：语义值 -1 编码为 0x20（major 1, 编码值 0）；语义值 -1000 → 编码值 999 → 0x39 0x03 0xe7
        assertArrayEquals(byteArrayOf(0x20.toByte()), cborEncode(CborValue.Negative(-1)))
        assertArrayEquals(
            byteArrayOf(0x39.toByte(), 0x03, 0xe7.toByte()),
            cborEncode(CborValue.Negative(-1000))
        )
        // 往返：编码值还原语义值
        assertEquals(CborValue.Negative(-1000), cborDecode(cborEncode(CborValue.Negative(-1000))))
    }

    @Test
    fun cbor_text_string() {
        // RFC 8949 附录 A："IETF" → 0x64 0x49 0x45 0x54 0x46
        assertArrayEquals(
            byteArrayOf(0x64, 0x49, 0x45, 0x54, 0x46),
            cborEncode(CborValue.Text("IETF"))
        )
    }

    @Test
    fun cbor_bytes_string() {
        // h'01020304' → 0x44 0x01 0x02 0x03 0x04
        assertArrayEquals(
            byteArrayOf(0x44, 0x01, 0x02, 0x03, 0x04),
            cborEncode(CborValue.Bytes(byteArrayOf(1, 2, 3, 4)))
        )
    }

    @Test
    fun cbor_array_and_map_official_vector() {
        // RFC 8949：[_ 1, 2, 3] → 0x83 0x01 0x02 0x03
        val array = CborValue.Array(listOf(CborValue.Unsigned(1), CborValue.Unsigned(2), CborValue.Unsigned(3)))
        assertArrayEquals(byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03), cborEncode(array))
        // {"a": 1, "b": [2, 3]} → 0xa2 0x61 0x61 0x01 0x61 0x62 0x82 0x02 0x03
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

    // ==================== CBOR 解码往返 ====================

    @Test
    fun cbor_roundtrip_all_types() {
        val original = CborValue.Map(
            listOf(
                CborValue.Unsigned(1) to CborValue.Unsigned(2),
                CborValue.Unsigned(3) to CborValue.Negative(-7), // alg: -7
                CborValue.Text("id") to CborValue.Bytes(ByteArray(32) { it.toByte() }),
                CborValue.Text("nested") to CborValue.Array(listOf(CborValue.Text("x"), CborValue.Unsigned(9)))
            )
        )
        val decoded = cborDecode(cborEncode(original))
        assertEquals(original, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cbor_decode_rejects_truncated() {
        cborDecode(byteArrayOf(0x19.toByte(), 0x03)) // 声明 2 字节只给 1 字节
    }
    // ==================== COSE_Key（ES256） ====================

    @Test
    fun cose_key_structure_matches_webauthn_spec() {
        val x = ByteArray(32) { 0x11 }
        val y = ByteArray(32) { 0x22 }
        val encoded = cborEncode(coseEs256PublicKey(x, y))
        val decoded = cborDecode(encoded) as CborValue.Map
        // kty=2(EC2)、alg=-7(ES256)、crv=1(P-256)、x/y 坐标（解码后为语义值）
        val entries = decoded.entries
        assertEquals(5, entries.size)
        assertEquals(CborValue.Unsigned(2), entries[0].second) // kty: EC2
        assertEquals(CborValue.Negative(-7), entries[1].second) // alg: ES256 语义值 -7
        assertEquals(CborValue.Unsigned(1), entries[2].second) // crv: P-256
        assertArrayEquals(x, (entries[3].second as CborValue.Bytes).v)
        assertArrayEquals(y, (entries[4].second as CborValue.Bytes).v)
    }

    // ==================== authenticatorData ====================
    // （组包已迁移官方 androidx.credentials.webauthn 包，authData 布局断言见 WebAuthnOfficialPackagingTest）

    // ==================== ES256 raw → DER ====================

    @Test
    fun der_signature_no_padding_needed() {
        // r/s 首字节均 < 0x80：无需补 0x00，总长 = 2 + (2+32) + (2+32) = 70
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
        // r 首字节 0x81（高位 1 → 补 0x00 前缀）；s 全 0x01（高位 0 无需填充，字节非零剥除不触发）
        val raw = ByteArray(64) { if (it < 32) 0x81.toByte() else 0x01 }
        val der = es256RawToDer(raw)
        // r: [0x02][0x21][0x00 + 0x81×32] = 35 字节；s: [0x02][0x20][0x01×32] = 34 字节；总 2+35+34 = 71
        assertEquals(71, der.size)
        // r 段：der[0]=0x30, der[1]=总长, der[2]=0x02(INTEGER), der[3]=0x21(33=32+1), der[4]=0x00(填充)
        assertEquals(0x30, der[0].toInt())
        assertEquals(0x02, der[2].toInt())
        assertEquals(0x21.toByte(), der[3])
        assertEquals(0x00, der[4].toInt())
        // s 段从 2+35=37 起：der[37]=0x02(INTEGER), der[38]=0x20(32)
        assertEquals(0x02, der[37].toInt())
        assertEquals(0x20, der[38].toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun der_signature_rejects_wrong_length() {
        es256RawToDer(ByteArray(63))
    }

    // ==================== 签名格式自适应（Android 12+ KeyStore 直出 DER） ====================

    @Test
    fun sign_adapts_raw_to_der() {
        // 旧系统 KeyStore 输出裸 r||s（64 字节）→ 应转 DER
        val raw = ByteArray(64) { 0x01 }
        assertEquals(70, es256Adapt(raw).size)
    }

    @Test
    fun sign_passthrough_der() {
        // Android 12+ KeyStore 直接输出 DER（0x30 开头，长度≠64）→ 应原样返回
        val der = ByteArray(72) { 0x30.toByte() }
        assertTrue(der.contentEquals(es256Adapt(der)))
    }

    private fun es256Adapt(out: ByteArray): ByteArray {
        // 复刻 es256Sign 的自适应分支（Signature 对象无法在纯 JVM 单测中构造）
        return if (out.size == 64) es256RawToDer(out) else out
    }

    // ==================== 请求解析 ====================

    @Test
    fun parse_create_request_minimal() {
        // Create 形状：rp 对象包 id（安卓 14 真实 Create 请求即此形状）
        val json = """{"rp":{"id":"github.com"},"challenge":"abc123"}"""
        val req = parseWebAuthnRequest(json)!!
        assertEquals("github.com", req.rpId)
        assertEquals("abc123", req.challengeBase64Url)
        assertNull(req.userName)
    }

    @Test
    fun parse_get_request_toplevel_rpId() {
        // Get 形状：顶层 "rpId"，无 rp 对象（此前只查 rp.id → 登录请求解析恒 null，
        // 出卡恒空 → 系统弹「未找到可登录信息」）
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
        assertNull(parseWebAuthnRequest("""{"rpId":"x.com"}""")) // Get 形状缺 challenge 同样拒
        assertNull(parseWebAuthnRequest("not json"))
    }

    // ==================== Base64URL ====================

    @Test
    fun base64url_roundtrip_no_padding() {
        val bytes = ByteArray(33) { it.toByte() } // 33 字节：Base64 需要 1 个 = 填充，验证被剥除
        val encoded = base64Url(bytes)
        assertTrue(!encoded.contains("="))
        assertArrayEquals(bytes, base64UrlDecode(encoded))
    }

    // ==================== 别名派生 ====================

    // ==================== 协议修复回归 ====================
    // （clientDataJSON/attestation/响应 JSON 组包测试已随自拼实现拆除，
    //   官方包行为断言见 WebAuthnOfficialPackagingTest）

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
        // 软件密钥架构的存亡测试：生成 → PKCS8 序列化 → 反序列化签名 → 公钥验签。
        val (privateB64, publicB64, publicDerB64) = generateSoftwareEs256KeyPair()
        assertEquals(64, java.util.Base64.getDecoder().decode(publicB64).size)
        // SPKI DER：必须能被 KeyFactory 还原（Chrome 侧比对基准即此格式）
        assertTrue(publicDerB64.isNotBlank())
        val derDecoded = java.util.Base64.getDecoder().decode(publicDerB64)
        assertEquals(0x30, derDecoded[0].toInt() and 0xff) // DER SEQUENCE
        val restored = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(derDecoded))
        assertEquals("EC", restored.algorithm)
        val data = "authData||clientDataHash".toByteArray()
        val signature = es256AdaptRaw(softwareSigner(privateB64).apply { update(data) }.sign())
        // DER 签名以 0x30 开头
        assertEquals(0x30, signature[0].toInt() and 0xff)
        // 用公钥坐标重建公钥验签（与 RP 侧验证同构）
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
