package com.copy.account.runtime

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.copy.account.data.model.Account
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal data class AutofillAccount(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val url: String,
    val packageName: String?
)

@Serializable
internal data class AutofillAccountLight(
    val id: String,
    val name: String,
    val url: String,
    val packageName: String?
)

internal object AutofillBridge {
    private const val INDEX_FILE_NAME = "autofill_public_index.json"

    @Volatile var snapshot: List<AutofillAccount> = emptyList()
        private set

    @Volatile var dek: ByteArray? = null
        private set

    @Volatile var publicIndex: List<AutofillAccountLight> = emptyList()
        private set

    var active: Boolean by mutableStateOf(false)
        private set

    fun enable(context: Context, data: List<AutofillAccount>, dekCopy: ByteArray) {
        snapshot = data
        dek = dekCopy.copyOf()
        active = true
        rebuildIndex(context, data)
    }

    fun refresh(context: Context, data: List<AutofillAccount>) {
        if (!active) return
        snapshot = data
        rebuildIndex(context, data)
    }

    fun onSessionLocked() {
        active = false
        dek?.fill(0)
        dek = null
        snapshot = emptyList()
    }

    fun clear(context: Context) {
        active = false
        dek?.fill(0)
        dek = null
        snapshot = emptyList()
        publicIndex = emptyList()
        runCatching { indexFile(context).delete() }
    }

    fun findAccount(id: String): AutofillAccount? = snapshot.firstOrNull { it.id == id }

    fun toAutofillAccounts(accounts: List<Account>): List<AutofillAccount> = accounts.map { it.toAutofillAccount() }

    fun loadPublicIndexIfEmpty(context: Context) {
        if (publicIndex.isNotEmpty()) return
        val file = indexFile(context)
        if (!file.exists()) return
        val vaultLast = File(context.filesDir, "vault.bin").lastModified()
        if (vaultLast - file.lastModified() > 2_000L) {
            runCatching { file.delete() }
            return
        }
        publicIndex = runCatching {
            indexJson.decodeFromString(ListSerializer(AutofillAccountLight.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    private val indexJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun indexFile(context: Context): File = File(context.filesDir, INDEX_FILE_NAME)

    private fun rebuildIndex(context: Context, data: List<AutofillAccount>) {
        val index = data.map {
            AutofillAccountLight(id = it.id, name = it.name, url = it.url, packageName = it.packageName)
        }
        publicIndex = index
        runCatching {
            indexFile(context).writeText(indexJson.encodeToString(ListSerializer(AutofillAccountLight.serializer()), index))
        }.onFailure {
            runCatching { indexFile(context).delete() }
            publicIndex = emptyList()
        }
    }
}

internal fun Account.toAutofillAccount(): AutofillAccount = AutofillAccount(
    id = id,
    name = name,
    username = username,
    password = password,
    url = url,
    packageName = packageName
)
