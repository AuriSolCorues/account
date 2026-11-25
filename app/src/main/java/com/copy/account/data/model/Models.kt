package com.copy.account.data.model

import com.copy.account.BuildConfig
import com.copy.account.R
import com.copy.account.security.DEFAULT_PASSWORD_ITERATIONS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class GroupKind { DEFAULT, DYNAMIC, CUSTOM }

@Serializable
internal data class Group(
    val id: String,
    val name: String,
    val kind: GroupKind
)

@Serializable
internal data class AccountField(
    val id: String,
    val label: String,
    val value: String,
    val hidden: Boolean = false
)

internal const val DEFAULT_TOTP_TYPE = "TOTP"
internal const val DEFAULT_TOTP_DIGITS = 6
internal const val DEFAULT_TOTP_PERIOD = 30
internal const val DEFAULT_TOTP_ALGORITHM = "SHA256"

@Serializable
internal data class Account(
    val id: String,
    val name: String,
    val username: String,
    val password: String,

    val usernameLabel: String? = null,
    val passwordLabel: String? = null,

    val usernameHidden: Boolean = false,
    val passwordHidden: Boolean = true,

    val groups: Set<String> = emptySet(),

    val hasTotp: Boolean = false,
    val totpSecret: String = "",
    val totpDigits: Int = DEFAULT_TOTP_DIGITS,
    val totpPeriod: Int = DEFAULT_TOTP_PERIOD,

    val totpCounter: Long = 0,

    val totpAlgorithm: String = DEFAULT_TOTP_ALGORITHM,

    val customFields: List<AccountField> = emptyList(),

    val url: String = "",

    val packageName: String? = null,

    val totpType: String = DEFAULT_TOTP_TYPE,

    val uid: String = "",
)

private const val UID_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

internal fun newUid(nowMillis: Long = System.currentTimeMillis()): String {
    val suffix = buildString(6) {
        repeat(6) { append(UID_ALPHABET[kotlin.random.Random.nextInt(UID_ALPHABET.length)]) }
    }
    return "a-$nowMillis-$suffix"
}

internal fun embeddedMillis(tag: String): Long =
    tag.split('-').firstNotNullOfOrNull { segment -> segment.toLongOrNull() } ?: 0L

internal val Account.createdMillis: Long get() = embeddedMillis(id)

internal val Account.modifiedMillis: Long get() = if (uid.isNotBlank()) embeddedMillis(uid) else createdMillis

internal fun Account.withUidFilled(): Account = if (uid.isBlank()) copy(uid = newUid()) else this

internal fun sortAccounts(accounts: List<Account>): List<Account> =
    accounts.sortedWith(compareByDescending { it.createdMillis })

internal const val FIXED_GROUP_COUNT = 2

internal fun accountInGroup(account: Account, groups: List<Group>, groupId: String): Boolean {
    val group = groups.firstOrNull { it.id == groupId } ?: return false
    return when (group.kind) {
        GroupKind.DEFAULT -> account.groups.isEmpty()
        GroupKind.DYNAMIC -> account.hasTotp
        GroupKind.CUSTOM -> groupId in account.groups
    }
}

internal const val MAX_AUTO_LOCK_SECONDS = 86_400

@Serializable

internal data class AppSettings(
    val biometricEnabled: Boolean = false,
    val autofillEnabled: Boolean = false,
    val autoLockSeconds: Int = 60,
    val backgroundLockSeconds: Int = 5,
    val themeMode: String = BuildConfig.DEFAULT_THEME_MODE,
    val accentTheme: String = "green",
    val languageTag: String = "zh-CN",
    val customThemeJson: String = "",
    val customThemes: List<SavedTheme> = emptyList(),
    val clipboardClearSeconds: Int = 30,
    val allowScreenshots: Boolean = false,
    val kdfIterations: Int = DEFAULT_PASSWORD_ITERATIONS,
    val maskChar: String = "•"
)

@Serializable
internal data class WebDavConfigBackup(
    val url: String,
    val user: String,
    val passwordEnc: String
)

@Serializable
internal data class PersistedVault(
    val version: Int = 1,
    val accounts: List<Account>,
    val groups: List<Group>,
    val selectedGroupId: String = "default",
    val webdavConfig: WebDavConfigBackup? = null
)

internal val vaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun initialGroups(context: android.content.Context): List<Group> = listOf(
    Group("default", context.getString(R.string.seed_group_default), GroupKind.DEFAULT),
    Group("dynamic", context.getString(R.string.seed_group_dynamic), GroupKind.DYNAMIC),
    Group("social", context.getString(R.string.seed_group_social), GroupKind.CUSTOM),
    Group("games", context.getString(R.string.seed_group_games), GroupKind.CUSTOM),
    Group("bank", context.getString(R.string.seed_group_bank), GroupKind.CUSTOM),
    Group("work", context.getString(R.string.seed_group_work), GroupKind.CUSTOM)
)

internal fun initialAccounts(context: android.content.Context): List<Account> = listOf(
    Account(
        id = "demo-login",
        name = context.getString(R.string.seed_account_plain_name),
        username = "hello@example.com",
        password = "demo-password",
        url = "https://github.com",
        customFields = listOf(
            AccountField("demo-url", context.getString(R.string.seed_field_url), "https://example.com"),
            AccountField("demo-recovery", context.getString(R.string.seed_field_recovery), "demo-recovery-code", hidden = true),
            AccountField("demo-note", context.getString(R.string.seed_field_note), context.getString(R.string.seed_note_plain))
        )
    ),
    Account(
        id = "demo-totp",
        name = context.getString(R.string.seed_account_totp_name),
        username = "user@example.com",
        password = "demo-password",
        hasTotp = true,
        totpSecret = "JBSWY3DPEHPK3PXP",
        customFields = listOf(
            AccountField("demo-note", context.getString(R.string.seed_field_note), context.getString(R.string.seed_note_totp, context.getString(R.string.seed_group_dynamic)))
        )
    )
)
