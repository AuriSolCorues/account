package com.copy.account.security

import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.DEFAULT_TOTP_ALGORITHM
import com.copy.account.data.model.newUid
import com.copy.account.data.model.DEFAULT_TOTP_DIGITS
import com.copy.account.data.model.DEFAULT_TOTP_PERIOD
import com.copy.account.data.model.DEFAULT_TOTP_TYPE
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.PersistedVault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val ACCOUNTBOX_MAGIC = "ACCOUNTBOX_JSON_13"
internal const val ACCOUNTBOX_VERSION = 13

private const val DYNAMIC_TYPE_STEAM = 2
private const val DYNAMIC_TYPE_TOTP = 3

@Serializable
private data class AbDynamicPassword(
    val type: Int,
    val algorithm: String,
    val period: Int,
    val secret: String,
    val digits: Int,
    val name: String = "",
    val account: String = ""
)

@Serializable
private data class AbAccountItem(
    val itemName: String,
    val itemValue: String
)

@Serializable
private data class AbTag(
    val tagName: String
)

@Serializable
private data class AbAccount(
    val favorite: Boolean = false,
    val lastEditTime: Long = 0,
    val dynamicPassword: AbDynamicPassword? = null,
    val name: String,
    val accountItemList: List<AbAccountItem> = emptyList(),
    val tagList: List<AbTag> = emptyList()
)

@Serializable
private data class AbDocument(
    val accountList: List<AbAccount> = emptyList(),
    val lastEditTime: Long = 0,
    val dynamicPasswordList: List<AbDynamicPassword> = emptyList(),
    val version: Int = ACCOUNTBOX_VERSION,
    val tagList: List<AbTag> = emptyList()
)

private val abJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

internal fun exportAccountBoxJson(vault: PersistedVault, exportedAtMs: Long): ByteArray {
    val tagNameById = vault.groups
        .filter { it.kind == GroupKind.CUSTOM }
        .associate { it.id to it.name }
    val document = AbDocument(
        accountList = vault.accounts.map { account ->
            val items = buildList {
                add(AbAccountItem(itemName = "账号", itemValue = account.username))
                add(AbAccountItem(itemName = "密码", itemValue = account.password))
                for (field in account.customFields) {
                    add(AbAccountItem(itemName = field.label, itemValue = field.value))
                }
            }
            AbAccount(
                favorite = false,
                lastEditTime = exportedAtMs,
                dynamicPassword = if (account.hasTotp && account.totpSecret.isNotBlank()) {
                    AbDynamicPassword(
                        type = if (account.totpType == "STEAM") DYNAMIC_TYPE_STEAM else DYNAMIC_TYPE_TOTP,
                        algorithm = account.totpAlgorithm,
                        period = account.totpPeriod,
                        secret = account.totpSecret,
                        digits = account.totpDigits
                    )
                } else {
                    null
                },
                name = account.name,
                accountItemList = items,
                tagList = account.groups.mapNotNull { tagNameById[it] }.map { AbTag(it) }
            )
        },
        tagList = vault.groups.filter { it.kind == GroupKind.CUSTOM }.map { AbTag(it.name) }
    )
    return (ACCOUNTBOX_MAGIC + "\n" + abJson.encodeToString(AbDocument.serializer(), document))
        .toByteArray(Charsets.UTF_8)
}

internal fun importAccountBoxJson(bytes: ByteArray): Result<PersistedVault> = runCatching {
    val text = bytes.toString(Charsets.UTF_8)
    val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
    require(firstLine == ACCOUNTBOX_MAGIC) { "不是账号本子明文格式（缺少 ACCOUNTBOX_JSON_13 标识）" }
    val document = abJson.decodeFromString(AbDocument.serializer(), text.substringAfter('\n'))
    val customGroups = document.tagList.distinctBy { it.tagName }.map { Group("ab-${it.tagName}", it.tagName, GroupKind.CUSTOM) }
    val tagNameToId = customGroups.associate { it.name to it.id }
    val now = System.currentTimeMillis()
    val accounts = document.accountList.map { ab ->
        val username = ab.accountItemList.firstOrNull { it.itemName == "账号" }?.itemValue.orEmpty()
        val password = ab.accountItemList.firstOrNull { it.itemName == "密码" }?.itemValue.orEmpty()
        val customFields = ab.accountItemList
            .filter { it.itemName != "账号" && it.itemName != "密码" }
            .map { AccountField(id = "abf-${now}-${ab.name.hashCode()}-${it.itemName.hashCode()}", label = it.itemName, value = it.itemValue, hidden = false) }
        val dynamic = ab.dynamicPassword
        Account(
            id = "ab-${now}-${ab.name.hashCode()}",
            uid = newUid(now),
            name = ab.name,
            username = username,
            password = password,
            groups = ab.tagList.mapNotNull { tagNameToId[it.tagName] }.toSet(),
            hasTotp = dynamic != null,
            totpSecret = dynamic?.secret.orEmpty(),
            totpDigits = dynamic?.digits ?: DEFAULT_TOTP_DIGITS,
            totpPeriod = dynamic?.period ?: DEFAULT_TOTP_PERIOD,
            totpAlgorithm = dynamic?.algorithm ?: DEFAULT_TOTP_ALGORITHM,
            totpType = when (dynamic?.type) {
                DYNAMIC_TYPE_STEAM -> "STEAM"
                else -> DEFAULT_TOTP_TYPE
            },
            customFields = customFields
        )
    }
    PersistedVault(accounts = accounts, groups = customGroups, selectedGroupId = "default")
}
