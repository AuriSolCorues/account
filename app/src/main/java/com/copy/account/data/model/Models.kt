package com.copy.account.data.model

import com.copy.account.BuildConfig
import com.copy.account.ui.theme.SavedTheme
import kotlinx.serialization.Serializable

@Serializable
internal enum class GroupKind { DEFAULT, DYNAMIC, CUSTOM }

@Serializable
internal data class Group(val id: String, val name: String, val kind: GroupKind)

@Serializable
internal data class AccountField(
    val id: String,
    val label: String,
    val value: String,
    val hidden: Boolean = false
)

@Serializable
internal data class Account(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val groups: Set<String> = emptySet(),
    val hasTotp: Boolean = false,
    val totpSecret: String = "",
    val totpDigits: Int = 6,
    val totpPeriod: Int = 30,
    val totpAlgorithm: String = "SHA1",
    val customFields: List<AccountField> = emptyList(),
    /** TOTP 为标准验证码，STEAM 为 Steam Guard 专用 5 字符验证码。 */
    val totpType: String = "TOTP",
    /** 预留给后续自定义图标的稳定键；当前为空时继续使用现有文字界面。 */
    val iconKey: String? = null
)

internal data class AppSettings(
    val biometricEnabled: Boolean = false,
    val autoLockMinutes: Int = 5,
    val themeMode: String = BuildConfig.DEFAULT_THEME_MODE,
    val accentTheme: String = "green",
    val languageTag: String = "zh-CN",
    val customThemeJson: String = "",
    val customThemes: List<SavedTheme> = emptyList(),
    val clipboardClearSeconds: Int = 30,
    val allowScreenshots: Boolean = false
)

@Serializable
internal data class PersistedVault(
    val version: Int = 1,
    val accounts: List<Account>,
    val groups: List<Group>,
    val selectedGroupId: String = "default"
)

internal val initialGroups = listOf(
    Group("default", "默认", GroupKind.DEFAULT),
    Group("dynamic", "动态密码", GroupKind.DYNAMIC),
    Group("social", "社交媒体", GroupKind.CUSTOM),
    Group("games", "游戏娱乐", GroupKind.CUSTOM),
    Group("bank", "银行卡", GroupKind.CUSTOM),
    Group("work", "工作", GroupKind.CUSTOM)
)

/** 首次安装的示例账号，用来演示用法，用户可自行删除；不含任何真实凭据。 */
internal val initialAccounts = listOf(
    Account(
        id = "demo-login",
        name = "示例 · 普通账号",
        username = "hello@example.com",
        password = "demo-password",
        customFields = listOf(
            AccountField("demo-url", "网址", "https://example.com"),
            AccountField("demo-recovery", "恢复码", "demo-recovery-code", hidden = true),
            AccountField("demo-note", "备注", "点密码的圆点可看明文、再点复制；长按账号可编辑/删除/模板新建；右上角＋可新增账号。")
        )
    ),
    Account(
        id = "demo-totp",
        name = "示例 · 动态密码",
        username = "user@example.com",
        password = "demo-password",
        hasTotp = true,
        totpSecret = "JBSWY3DPEHPK3PXP",
        customFields = listOf(
            AccountField("demo-note", "备注", "动态密码每 30 秒更新一次，点验证码即可复制；在「动态密码」分组可看倒计时。")
        )
    )
)
