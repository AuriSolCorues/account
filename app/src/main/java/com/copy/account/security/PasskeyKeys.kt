/**
 * 职责：通行密钥的 ES256 密钥工具（软件密钥架构，对齐 KeePassDX 存 kdbx 的取舍）——
 *       生成软件密钥对、PKCS8 编解码、authData||clientDataHash 签名（DER 输出）。
 *       私钥本体存 Account.passkeys（账号段 AES-GCM 密文内），不在 KeyStore：
 *       卸载重装后靠 .acc 备份完整恢复，通行密钥不再随设备消失。
 * 架构位置：PasskeyCreateActivity 生成密钥对并把私钥写进账号段（经 PasskeyVaultAccess）；
 *           PasskeyGetActivity 从 Account.passkeys 取私钥签名。KeyStore 路线已整体拆除。
 * 安全模型：落盘安全 = vault 分段加密（与密码字段同容器）；签名闸门 = 出卡确认页的
 *           BiometricPrompt（活体确认，非 CryptoObject 密码学绑定——软件密钥做不到，
 *           KeePassDX 同样没有，这一档让渡换来了可备份可迁移）。
 * Python 类比：旧 KeyStore 方案 ≈ 钥匙锁在银行保险柜（换机即失）；本方案 ≈ 钥匙抄进
 *           自家加密笔记本（笔记本整体加密，备份搬家都方便）。
 */
package com.copy.account.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * 生成 ES256 软件密钥对（P-256）：返回 (私钥 PKCS8 Base64, 公钥坐标 Base64(x||y),
 * 公钥 SPKI DER Base64)。DER 供 Chrome CredMan 通道回包 publicKey 字段（严格 DER 比对），
 * 坐标供 attestation 内 COSE key 编码；两者都随 AccountPasskey 存库。
 */
internal fun generateSoftwareEs256KeyPair(): Triple<String, String, String> {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val keyPair = generator.generateKeyPair()
    val privateB64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
    val pub = keyPair.public as ECPublicKey
    return Triple(privateB64, publicXYB64(pub), publicDerB64(pub))
}

/** 公钥坐标 Base64（x||y，attestation 内 COSE key 编码用）。 */
internal fun publicXYB64(key: ECPublicKey): String =
    Base64.getEncoder().encodeToString(ecPublicKeyXY(key))

/** 公钥 SPKI DER Base64（Chrome CredMan 回包 publicKey 字段要求 DER 格式）。 */
internal fun publicDerB64(key: ECPublicKey): String =
    Base64.getEncoder().encodeToString(key.encoded)

// ==================== PendingIntent 完整性标签（HMAC） ====================

/**
 * 防第三方 App 伪造 extras 直接拉起 passkey 透明浮层（断言钓鱼）：
 * 两个浮层 Activity 是 exported 的（系统凭据流程拉起），任何本地 App 也能 startActivity
 * 构造假的 requestJson（rpId 指向任意站点）骗签名。防线 = Service 建 PendingIntent 时对
 * requestJson 加 HMAC 标签，Activity 验签不过即取消——攻击者没有密钥（AndroidKeyStore
 * 不可提取），伪造不了标签；重放旧标签只能得到旧 challenge 的断言，RP 一次一密必拒，
 * 无收益。
 *
 * 密钥无用户认证门槛（PURPOSE 仅 SIGN|VERIFY）：Service 在锁定态就要建 PendingIntent，
 * 认证门槛会让出卡直接失败；密钥不参与任何数据解密，被滥用面仅「替攻击者算标签」，
 * 而标签只有本应用组件会验。
 */
internal object PasskeyIntegrity {
    private const val KEY_ALIAS = "passkey_intent_hmac"

    /** get-or-create KeyStore HMAC-SHA256 密钥（AndroidKeyStore，不可提取）。 */
    private fun macKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .build()
        )
        return generator.generateKey()
    }

    /** payload（requestJson 原文）→ HMAC 标签。 */
    fun tag(payload: String, key: SecretKey = macKey()): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(payload.toByteArray(Charsets.UTF_8))

    /** 常量时间比较，防时序侧信道；key 抽成参数供 JVM 单测注入本地密钥。 */
    fun verify(payload: String, tag: ByteArray?, key: SecretKey = macKey()): Boolean =
        tag != null && MessageDigest.isEqual(tag, tag(payload, key))
}

/** PKCS8/Base64 私钥 → JCA 签名对象（SHA256withECDSA，标准 provider 输出 DER）。 */
internal fun softwareSigner(privateKeyPkcs8B64: String): Signature {
    val privateKey = KeyFactory.getInstance("EC")
        .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyPkcs8B64)))
    return Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
}

/** ECPublicKey → 64 字节坐标（x||y，各 32 字节无符号大端，前导补零）。 */
internal fun ecPublicKeyXY(key: ECPublicKey): ByteArray {
    val w = key.w
    return pad32(w.affineX) + pad32(w.affineY)
}

/** BigInteger → 32 字节无符号大端（剥符号位多余字节/前导补零）。 */
private fun pad32(value: java.math.BigInteger): ByteArray {
    val bytes = value.toByteArray()
    return when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size) // 剥前导符号 0x00
        else -> ByteArray(32 - bytes.size) + bytes
    }
}
