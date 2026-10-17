/**
 * 职责：WebAuthn/FIDO2 核心——CBOR 编解码（COSE_Key/attestation 对象）、authenticatorData
 *       （rpIdHash+flags+signCount+ attestedCredentialData）、clientDataJSON、packed 自证明
 *       CBOR 编解码、Get/Create 请求解析、ES256 签名格式与 signCount 回填。纯函数无状态无 IO、无 Android 依赖、
 *       不依赖 org.json（手拼 JSON + 轻量提取），可用固定测试向量做纯 JVM 单测。
 * 架构位置：service/PasskeyProviderService 与两个透明 Activity 调用本文件完成协议编解码；
 *           密钥对生成/签名在 PasskeyKeys.kt（AndroidKeyStore，不可在本文件测试）。
 * Python 类比：本文件 ≈ 一个手写的 cbor2 + webauthn 编解码库——
 *           cbor 编码 ≈ struct.pack 的递归版（major type 打头、参数跟后），
 *           authenticatorData ≈ 手拼 bytes：hashlib.sha256(rpId) + flags + 计数器 + 凭据段。
 */
package com.copy.account.security

import java.security.MessageDigest
import java.security.Signature

// ==================== 常量 ====================

/** ES256 的 COSE 算法编号（RFC 8152）。 */
internal const val COSE_ALG_ES256 = -7

// ==================== CBOR 编解码 ====================

/**
 * 极简 CBOR 编解码子集（WebAuthn 所需）：
 * 无符号整数（major 0）、负整数（major 1）、字节串（major 2）、文本串（major 3）、数组（major 4）、map（major 5）。
 */
internal sealed class CborValue {
    data class Unsigned(val v: Long) : CborValue()
    data class Negative(val v: Long) : CborValue() // 语义值 v，编码为 -1-v
    data class Bytes(val v: ByteArray) : CborValue() {
        // ByteArray 字段的内容相等：data class 默认 equals 是引用比较，必须重写（测试往返依赖）
        override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
    }
    data class Text(val v: String) : CborValue()
    data class Array(val items: kotlin.collections.List<CborValue>) : CborValue()
    data class Map(val entries: kotlin.collections.List<Pair<CborValue, CborValue>>) : CborValue()
}

internal fun cborEncode(value: CborValue): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    cborWrite(value, out)
    return out.toByteArray()
}

private fun cborWrite(value: CborValue, out: java.io.ByteArrayOutputStream) {
    when (value) {
        is CborValue.Unsigned -> cborHead(0, value.v, out)
        is CborValue.Negative -> cborHead(1, -1 - value.v, out) // 语义值 v → 编码值 -1-v（RFC 8949）
        is CborValue.Bytes -> {
            cborHead(2, value.v.size.toLong(), out)
            out.write(value.v)
        }
        is CborValue.Text -> {
            val utf8 = value.v.toByteArray(Charsets.UTF_8)
            cborHead(3, utf8.size.toLong(), out)
            out.write(utf8)
        }
        is CborValue.Array -> {
            cborHead(4, value.items.size.toLong(), out)
            value.items.forEach { cborWrite(it, out) }
        }
        is CborValue.Map -> {
            cborHead(5, value.entries.size.toLong(), out)
            value.entries.forEach { (k, v) ->
                cborWrite(k, out)
                cborWrite(v, out)
            }
        }
    }
}

/** 写 CBOR 头字节：3 位 major type + 长度；<24 直接放，否则按 1/2/4/8 字节扩展（0x18/0x19/0x1a/0x1b）。 */
private fun cborHead(major: Int, value: Long, out: java.io.ByteArrayOutputStream) {
    val m = major shl 5
    when {
        value < 24 -> out.write(m or value.toInt())
        value < 0x100 -> {
            out.write(m or 24)
            out.write(value.toInt())
        }
        value < 0x10000 -> {
            out.write(m or 25)
            out.write((value ushr 8).toInt())
            out.write((value and 0xff).toInt())
        }
        value < 0x100000000L -> {
            out.write(m or 26)
            repeat(4) { shift -> out.write((value ushr (8 * (3 - shift))).toInt()) }
        }
        else -> {
            out.write(m or 27)
            repeat(8) { shift -> out.write((value ushr (8 * (7 - shift))).toInt()) }
        }
    }
}

/**
 * 极简 CBOR 解码器：与编码器同一子集，另支持 major 7（简单值/浮点，跳过未消费校验）；
 * 越界/不支持的结构抛 IllegalArgumentException，调用方 runCatching 兜底。
 */
internal fun cborDecode(bytes: ByteArray): CborValue = CborReader(bytes).readValue()

private class CborReader(private val bytes: ByteArray) {
    private var pos = 0

    fun readValue(): CborValue {
        val initial = nextByte().toInt() and 0xff // 先转无符号：负 Byte 算术右移会把符号位拖满
        val major = initial ushr 5
        val info = initial and 0x1f
        val argument: Long = when {
            info < 24 -> info.toLong()
            info == 24 -> nextByte().toLong() and 0xff
            info == 25 -> {
                var v = 0L
                repeat(2) { v = (v shl 8) or (nextByte().toLong() and 0xff) }
                v
            }
            info == 26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or (nextByte().toLong() and 0xff) }
                v
            }
            info == 27 -> {
                var v = 0L
                repeat(8) { v = (v shl 8) or (nextByte().toLong() and 0xff) }
                v
            }
            else -> throw IllegalArgumentException("CBOR info=$info 不支持")
        }
        return when (major) {
            0 -> CborValue.Unsigned(argument)
            1 -> CborValue.Negative(-1 - argument) // 编码值 → 语义值（与编码器对称）
            2 -> CborValue.Bytes(nextBytes(argument.toInt()))
            3 -> CborValue.Text(String(nextBytes(argument.toInt()), Charsets.UTF_8))
            4 -> {
                val items = ArrayList<CborValue>(argument.toInt())
                repeat(argument.toInt()) { items += readValue() }
                CborValue.Array(items)
            }
            5 -> {
                val entries = ArrayList<Pair<CborValue, CborValue>>(argument.toInt())
                repeat(argument.toInt()) { entries += readValue() to readValue() }
                CborValue.Map(entries)
            }
            7 -> CborValue.Unsigned(argument) // 简单值（true/false/null 等）：WebAuthn 结构不使用，跳过兼容
            else -> throw IllegalArgumentException("CBOR major=$major 不支持")
        }
    }

    private fun nextByte(): Byte {
        if (pos >= bytes.size) throw IllegalArgumentException("CBOR 越界")
        return bytes[pos++]
    }

    private fun nextBytes(n: Int): ByteArray {
        if (pos + n > bytes.size) throw IllegalArgumentException("CBOR 越界")
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

// ==================== COSE_Key（ES256 公钥） ====================

/** ES256 COSE_Key：kty=EC2(2)、alg=ES256(-7)、crv=P-256(1)、x/y 坐标各 32 字节无符号大端。 */
internal fun coseEs256PublicKey(x: ByteArray, y: ByteArray): CborValue = CborValue.Map(
    listOf(
        CborValue.Unsigned(1) to CborValue.Unsigned(2), // kty: EC2
        CborValue.Unsigned(3) to CborValue.Negative(COSE_ALG_ES256.toLong()), // alg: ES256
        CborValue.Negative(-1) to CborValue.Unsigned(1), // crv: P-256（标签 -1）
        CborValue.Negative(-2) to CborValue.Bytes(x), // x 坐标（标签 -2）
        CborValue.Negative(-3) to CborValue.Bytes(y) // y 坐标（标签 -3）
    )
)

// ==================== 组包（已迁移官方 androidx.credentials.webauthn 包） ====================
// authenticatorData/clientDataJSON/attestationObject/断言与注册响应 JSON 全部由官方类
// （AuthenticatorAttestationResponse/AuthenticatorAssertionResponse/FidoPublicKeyCredential）
// 生成——自拼实现曾有 AAGUID 只写 2 字节（规范 16 字节）的布局 bug，Chrome 解析必挂，
// b7 起整体拆除。本文件保留：请求解析、CBOR/COSE 编码（官方构造器的 credentialPublicKey
// 参数要裸 COSE 字节）、base64url、签名格式工具。

// ==================== 官方 attestation 解包 ====================

/**
 * 从官方 [androidx.credentials.webauthn.AuthenticatorAttestationResponse] 的
 * attestationObject 里原样取回 authData 字节（官方类未公开该字段，Chrome CredMan 通道
 * 要求回包顶层 authenticatorData 与 attestationObject 内的逐字节一致，只能解包取同源字节）。
 */
internal fun attestationAuthData(cborAttestation: ByteArray): ByteArray =
    when (val decoded = cborDecode(cborAttestation)) {
        is CborValue.Map -> decoded.entries
            .firstOrNull { it.first is CborValue.Text && (it.first as CborValue.Text).v == "authData" }
            ?.second.let { it as? CborValue.Bytes }?.v
            ?: error("attestationObject 无 authData 字段")
        else -> error("attestationObject 不是 CBOR map")
    }

// ==================== signCount 回填 ====================

/**
 * 官方 [androidx.credentials.webauthn.AuthenticatorAssertionResponse.defaultAuthenticatorData]
 * 的 signCount 恒写 0；本库在 vault 里维护递增计数，需在签名前回填到 4 字节大端位
 * （偏移 33..36，WebAuthn L3 §6.1）。RP 侧以「计数器不增」判定克隆攻击。
 */
internal fun patchSignCount(authData: ByteArray, signCount: Int): ByteArray {
    require(authData.size >= 37) { "authData 须 ≥37 字节，实际 ${authData.size}" }
    val out = authData.copyOf()
    out[33] = (signCount ushr 24).toByte()
    out[34] = (signCount ushr 16).toByte()
    out[35] = (signCount ushr 8).toByte()
    out[36] = signCount.toByte()
    return out
}

// ==================== 签名格式工具 ====================

/**
 * ES256 签名（KeyStore Signature 输出 IEEE-P1363 裸 r||s 各 32 字节）转 WebAuthn 要求的
 * ASN.1 DER：SEQUENCE { INTEGER r, INTEGER s }。
 */
internal fun es256RawToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64) { "ES256 raw 签名须 64 字节，实际 ${raw.size}" }
    val rDer = derInteger(raw.copyOfRange(0, 32))
    val sDer = derInteger(raw.copyOfRange(32, 64))
    val out = java.io.ByteArrayOutputStream()
    out.write(0x30)
    out.write(rDer.size + sDer.size)
    out.write(rDer)
    out.write(sDer)
    return out.toByteArray()
}

/** DER 整数：剥前导零、高位为 1 时补 0x00 前缀。 */
private fun derInteger(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
    val trimmed = bytes.copyOfRange(start, bytes.size)
    val needsPad = trimmed[0].toInt() and 0x80 != 0
    val out = java.io.ByteArrayOutputStream()
    out.write(0x02)
    out.write(trimmed.size + if (needsPad) 1 else 0)
    if (needsPad) out.write(0x00)
    out.write(trimmed)
    return out.toByteArray()
}

// ==================== 请求解析 ====================

/** 解析 Get/Create 请求 JSON（透明 Activity 收到的 requestJson / createJson）。 */
internal data class WebAuthnRequest(
    val rpId: String,
    val challengeBase64Url: String,
    val userName: String?, // 仅 Create：user.name
    val userIdBase64Url: String?, // 仅 Create：user.id（userHandle）
    val originHint: String?, // 调用方 origin
    val pubKeyCredParams: List<Int> = emptyList(), // 仅 Create：网站声明的可接受算法（alg 编号）
    val allowCredentialIds: List<String> = emptyList(), // 仅 Get：网站允许的 credentialId（Base64URL）
    val requestJson: String = "" // 请求原文（官方 androidx webauthn 类构造器要原始 JSON）
)

internal fun parseWebAuthnRequest(json: String): WebAuthnRequest? = runCatching {
    // rpId 的字段形状因流程而异：Get 请求是顶层 "rpId"；Create 请求是 "rp":{"id":...}。
    // （Get 无 rp 对象——此前只查 rp.id，登录请求解析恒 null → 出卡恒空 →
    //  系统弹「未找到可登录信息」，GitHub 不识别的第二个根因。）
    val rpId = jsonExtractString(json, "rpId") // Get：顶层 rpId
        ?: jsonExtractString(json, "id", section = "rp") // Create：rp.id
        ?: jsonExtractString(json, "id", section = "relayingParty")
        ?: return@runCatching null
    val challenge = jsonExtractString(json, "challenge")?.takeIf { it.isNotBlank() } ?: return null
    WebAuthnRequest(
        rpId = rpId,
        challengeBase64Url = challenge,
        userName = jsonExtractString(json, "name", section = "user")?.takeIf { it.isNotBlank() },
        userIdBase64Url = jsonExtractString(json, "id", section = "user")?.takeIf { it.isNotBlank() },
        originHint = jsonExtractString(json, "origin")?.takeIf { it.isNotBlank() },
        pubKeyCredParams = jsonExtractIntList(json, "alg", section = "pubKeyCredParams"),
        allowCredentialIds = jsonExtractStringList(json, "id", section = "allowCredentials"),
        requestJson = json
    )
}.getOrNull()

/**
 * 极简 JSON 字符串数组提取（只服务 Get 的 allowCredentials[].id，不求通用）：
 * 定位 "section":[ ... ] 平衡中括号取段，段内收集所有 "key":"value"。
 */
internal fun jsonExtractStringList(json: String, key: String, section: String): List<String> {
    val sectionMarker = "\"" + section + "\""
    val start = json.indexOf(sectionMarker).takeIf { it >= 0 } ?: return emptyList()
    val bracketStart = json.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
    var depth = 0
    var end = bracketStart
    while (end < json.length) {
        when (json[end]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        end++
    }
    if (depth != 0) return emptyList()
    val scope = json.substring(bracketStart, end)
    val keyMarker = "\"" + key + "\":\""
    val values = ArrayList<String>()
    var pos = 0
    while (true) {
        val keyPos = scope.indexOf(keyMarker, pos).takeIf { it >= 0 } ?: break
        val valueStart = keyPos + keyMarker.length
        val valueEnd = scope.indexOf('"', valueStart).takeIf { it >= 0 } ?: break
        values += scope.substring(valueStart, valueEnd)
        pos = valueEnd
    }
    return values
}

/**
 * 极简 JSON 整数数组提取（只服务 Create 的 pubKeyCredParams[].alg，不求通用）：
 * 先定位 "section":{ ... } 平衡大括号取段，再段内扫描所有 "key":<整数>。
 * 算法列表为空 = 网站未声明偏好（可退 ES256 默认，绝大多数 RP 接受）。
 */
internal fun jsonExtractIntList(json: String, key: String, section: String): List<Int> {
    val sectionMarker = "\"" + section + "\""
    val start = json.indexOf(sectionMarker).takeIf { it >= 0 } ?: return emptyList()
    val braceStart = json.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
    var depth = 0
    var end = braceStart
    while (end < json.length) {
        when (json[end]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        end++
    }
    if (depth != 0) return emptyList()
    val scope = json.substring(braceStart, end)
    val keyMarker = "\"" + key + "\""
    val values = ArrayList<Int>()
    var pos = 0
    while (true) {
        val keyPos = scope.indexOf(keyMarker, pos).takeIf { it >= 0 } ?: break
        val colon = scope.indexOf(':', keyPos + keyMarker.length).takeIf { it >= 0 } ?: break
        var numStart = colon + 1
        while (numStart < scope.length && !scope[numStart].isDigit() && scope[numStart] != '-') numStart++
        if (numStart >= scope.length) break
        var numEnd = numStart
        while (numEnd < scope.length && (scope[numEnd].isDigit() || scope[numEnd] == '-')) numEnd++
        val value = scope.substring(numStart, numEnd).toIntOrNull() ?: break
        values += value
        pos = numEnd
    }
    return values
}

/**
 * 极简 JSON 字符串字段提取（只服务请求解析，不求通用）：
 * [section] 非 null 时先定位 "section":{ ... }（平衡大括号取段）再在段内找 "key"；
 * 顶层 key 全文找首个 "key":"value"。值必须带双引号（只取字符串字段）。
 */
internal fun jsonExtractString(json: String, key: String, section: String? = null): String? {
    val scope = if (section != null) {
        val sectionMarker = "\"" + section + "\""
        val start = json.indexOf(sectionMarker).takeIf { it >= 0 } ?: return null
        val braceStart = json.indexOf('{', start).takeIf { it >= 0 } ?: return null
        var depth = 0
        var end = braceStart
        while (end < json.length) {
            when (json[end]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            end++
        }
        if (depth != 0) return null
        json.substring(braceStart, end + 1)
    } else {
        json
    }
    val keyMarker = "\"" + key + "\""
    val keyPos = scope.indexOf(keyMarker).takeIf { it >= 0 } ?: return null
    val colon = scope.indexOf(':', keyPos + keyMarker.length).takeIf { it >= 0 } ?: return null
    val quoteOpen = scope.indexOf('"', colon).takeIf { it >= 0 } ?: return null
    val quoteClose = scope.indexOf('"', quoteOpen + 1).takeIf { it >= 0 } ?: return null
    return scope.substring(quoteOpen + 1, quoteClose)
}

// ==================== Base64URL（WebAuthn 标准，无填充） ====================

internal fun base64Url(bytes: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun base64UrlDecode(text: String): ByteArray =
    java.util.Base64.getUrlDecoder().decode(text)

// ==================== 签名便捷封装 ====================

/** ES256 签名格式自适应：64 字节（裸 r||s）转 DER；否则视为 DER 原样返回（标准 JCA 直出 DER）。 */
internal fun es256AdaptRaw(out: ByteArray): ByteArray =
    if (out.size == 64) es256RawToDer(out) else out

/** credentialId → 确定性别名（旧 KeyStore 路线遗留的派生规则，现仅测试与展示指纹用）。 */
internal fun passkeyAlias(credentialId: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(credentialId)
        .take(16)
        .joinToString("") { "%02x".format(it) }
