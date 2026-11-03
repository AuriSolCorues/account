package com.copy.account.security

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

@Serializable
internal data class PasskeyRegistryEntry(
    val credentialId: String,
    val rpId: String,
    val userHandle: String,
    val userName: String = "",
    val accountId: String,
    val signCount: Long = 0,
    val createdAt: Long
)

internal class PasskeyRegistry(context: Context) {

    private val file: File = File(context.filesDir, "passkey_registry.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<PasskeyRegistryEntry> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(ListSerializer(PasskeyRegistryEntry.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun save(entries: List<PasskeyRegistryEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(PasskeyRegistryEntry.serializer()), entries))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(ListSerializer(PasskeyRegistryEntry.serializer()), entries))
            tmp.delete()
        }
    }

    fun add(entry: PasskeyRegistryEntry) {
        save(load().filterNot { it.credentialId == entry.credentialId } + entry)
    }

    fun byRpId(rpId: String): List<PasskeyRegistryEntry> = load().filter { it.rpId == rpId }

    fun byAccountId(accountId: String): List<PasskeyRegistryEntry> = load().filter { it.accountId == accountId }

    fun find(credentialId: String): PasskeyRegistryEntry? = load().firstOrNull { it.credentialId == credentialId }

    fun updateSignCount(credentialId: String, signCount: Long) {
        val entries = load()
        val updated = entries.firstOrNull { it.credentialId == credentialId } ?: return
        save(entries.filterNot { it.credentialId == credentialId } + updated.copy(signCount = signCount))
    }

    fun remove(credentialId: String) {
        save(load().filterNot { it.credentialId == credentialId })
    }

    fun removeAllForAccount(accountId: String) {
        save(load().filterNot { it.accountId == accountId })
    }

    fun rebuildFromVault(accounts: List<com.copy.account.data.model.Account>) {
        save(accounts.flatMap { acc ->
            acc.passkeys.map { pk ->
                PasskeyRegistryEntry(
                    credentialId = pk.credentialId,
                    rpId = pk.rpId,
                    userHandle = pk.userHandle,
                    userName = pk.userName,
                    accountId = acc.id,
                    signCount = pk.signCount,
                    createdAt = pk.createdAt
                )
            }
        })
    }

    companion object {
        private val random = SecureRandom()

        fun newCredentialId(): String = base64Url(ByteArray(32).also { random.nextBytes(it) })

        fun newUserHandle(): String = base64Url(ByteArray(32).also { random.nextBytes(it) })
    }
}
