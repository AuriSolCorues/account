package com.copy.account.security

internal object NativeKdf {
    init {
        System.loadLibrary("account_kdf")
    }

    external fun derive(password: ByteArray, salt: ByteArray, iterations: Int, bits: Int): ByteArray
}
