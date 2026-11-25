package com.copy.account.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.copy.account.data.model.Account
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.PersistedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccCodecTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val password = "测试密码123"
    private val input = AccExportInput(
        vault = PersistedVault(accounts = listOf(Account(id = "id", name = "示例", username = "user", password = "secret")), groups = emptyList()),
        settings = AppSettings(themeMode = "light")
    )

    @Test
    fun export_import_roundTrip() {
        val bytes = export()
        try {
            val result = importAcc(context, bytes, password).getOrThrow()
            assertEquals(input.vault.accounts, result.vault.accounts)
            assertEquals("light", result.settings.themeMode)
        } finally {
            bytes.fill(0)
        }
    }

    @Test
    fun import_rejects_tampered_backup() {
        val bytes = export()
        try {
            bytes[bytes.lastIndex] = if (bytes.last() == '}'.code.toByte()) ']'.code.toByte() else '}'.code.toByte()
            assertTrue(importAcc(context, bytes, password).isFailure)
        } finally {
            bytes.fill(0)
        }
    }

    private fun export(): ByteArray {
        val salt = ByteArray(16) { it.toByte() }
        val key = passwordHash(password, salt)
        return try {
            exportAcc(input, key, salt, DEFAULT_PASSWORD_ITERATIONS)
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }
}
