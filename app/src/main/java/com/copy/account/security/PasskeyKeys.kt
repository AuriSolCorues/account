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

internal fun generateSoftwareEs256KeyPair(): Triple<String, String, String> {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val keyPair = generator.generateKeyPair()
    val privateB64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
    val pub = keyPair.public as ECPublicKey
    return Triple(privateB64, publicXYB64(pub), publicDerB64(pub))
}

internal fun publicXYB64(key: ECPublicKey): String =
    Base64.getEncoder().encodeToString(ecPublicKeyXY(key))

internal fun publicDerB64(key: ECPublicKey): String =
    Base64.getEncoder().encodeToString(key.encoded)

internal object PasskeyIntegrity {
    private const val KEY_ALIAS = "passkey_intent_hmac"

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

    fun tag(payload: String, key: SecretKey = macKey()): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(payload.toByteArray(Charsets.UTF_8))

    fun verify(payload: String, tag: ByteArray?, key: SecretKey = macKey()): Boolean =
        tag != null && MessageDigest.isEqual(tag, tag(payload, key))
}

internal fun softwareSigner(privateKeyPkcs8B64: String): Signature {
    val privateKey = KeyFactory.getInstance("EC")
        .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyPkcs8B64)))
    return Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
}

internal fun ecPublicKeyXY(key: ECPublicKey): ByteArray {
    val w = key.w
    return pad32(w.affineX) + pad32(w.affineY)
}

private fun pad32(value: java.math.BigInteger): ByteArray {
    val bytes = value.toByteArray()
    return when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
        else -> ByteArray(32 - bytes.size) + bytes
    }
}
