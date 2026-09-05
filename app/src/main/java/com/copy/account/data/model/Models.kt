/**
 * 职责：全应用的数据模型层——账号（Account）、分组（Group）、附加字段（AccountField）、
 *       软件设置（AppSettings）、密码库整体结构（PersistedVault）与首装示例数据，全部不可变。
 * 架构位置：被所有层引用的中心类型表；AccountApp 在内存持有这些实例，
 *           security/VaultStore 把 PersistedVault 整体加密进 vault.bin，
 *           security/AccCodec 负责导出/导入 .acc 时的序列化。
 * Python 类比：data class ≈ 自动生成 __init__/__eq__/__repr__ 的不可变 dataclass，
 *           .copy(...) ≈ dataclasses.replace()；@Serializable ≈ pydantic 模型
 *           （编译期生成序列化/反序列化代码，运行时无反射）。
 */
package com.copy.account.data.model

import com.copy.account.BuildConfig
import com.copy.account.ui.theme.SavedTheme
import kotlinx.serialization.Serializable

/**
 * 账号分组类型枚举。
 * @property DEFAULT 系统默认分组
 * @property DYNAMIC 动态密码（TOTP）专用分组
 * @property CUSTOM 用户自定义分组
 */
@Serializable
internal enum class GroupKind { DEFAULT, DYNAMIC, CUSTOM }

/**
 * 账号分组数据模型。
 * @param id 分组唯一标识
 * @param name 分组显示名称
 * @param kind 分组类型
 */
@Serializable
internal data class Group(
    val id: String,
    val name: String,
    val kind: GroupKind
)

/**
 * 账号的自定义附加字段模型（如网址、备注、恢复码等）。
 * @param id 字段唯一标识
 * @param label 字段显示名称（如“网址”）
 * @param value 字段实际值
 * @param hidden 是否默认隐藏（如敏感信息需点击后才显示明文）
 */
@Serializable
internal data class AccountField(
    val id: String,
    val label: String,
    val value: String,
    val hidden: Boolean = false
)

// ==================== TOTP/2FA 默认配置常量 ====================

/** 新建/缺省账号的两步验证类型；编辑页预填与此保持同一来源。 */
internal const val DEFAULT_TOTP_TYPE = "TOTP"
/** 验证码默认位数。 */
internal const val DEFAULT_TOTP_DIGITS = 6
/** 验证码默认有效周期（秒）。 */
internal const val DEFAULT_TOTP_PERIOD = 30
/** 验证码默认哈希算法。 */
internal const val DEFAULT_TOTP_ALGORITHM = "SHA256"

// ==================== 核心数据模型 ====================

/**
 * 账号核心数据模型。
 */
@Serializable
internal data class Account(
    val id: String,
    val name: String,
    val username: String,
    val password: String,

    /** 固定用户名行的可选显示名；空值继续显示“用户名”。 */
    val usernameLabel: String? = null,
    /** 固定密码行的可选显示名；空值继续显示“密码”。 */
    val passwordLabel: String? = null,

    /** 固定用户名行的掩码位（详情/速览显示与复制敏感据此）。 */
    val usernameHidden: Boolean = false,
    /** 固定密码行的掩码位。 */
    val passwordHidden: Boolean = true,

    /** 账号所属的分组 ID 集合。 */
    val groups: Set<String> = emptySet(),

    /** 是否启用两步验证（TOTP/HOTP）。 */
    val hasTotp: Boolean = false,
    /** 两步验证的密钥（Secret）。 */
    val totpSecret: String = "",
    /** 验证码位数。 */
    val totpDigits: Int = DEFAULT_TOTP_DIGITS,
    /** 验证码有效周期（秒）。 */
    val totpPeriod: Int = DEFAULT_TOTP_PERIOD,

    /**
     * HOTP 事件型计数器：当前显示的码即 counter 的码，复制后 +1 并持久化。
     * TOTP/Steam 类型忽略此字段。
     */
    val totpCounter: Long = 0,

    /**
     * 两步验证默认算法。仅影响新建账号；已有账号保留各自存储值，修改算法会破坏原验证码。
     */
    val totpAlgorithm: String = DEFAULT_TOTP_ALGORITHM,

    /** 自定义字段列表。 */
    val customFields: List<AccountField> = emptyList(),

    /** TOTP 为标准验证码，STEAM 为 Steam Guard 专用 5 字符验证码。 */
    val totpType: String = DEFAULT_TOTP_TYPE,

    /** 预留给后续自定义图标的稳定键；当前为空时继续使用现有文字界面。 */
    val iconKey: String? = null
)

// ==================== 扩展属性 (UI 辅助) ====================

// 扩展属性：给 Account「挂」新属性而不改动类定义。观感像 monkey-patch 加属性，
// 但编译期静态解析、无运行时侵入——本质就是一个以 Account 为首参的纯函数。

/**
 * 固定用户名行的显示名：自定义名为空/空串时回退为“用户名”。
 */
internal val Account.usernameRowLabel: String
    get() = usernameLabel?.ifBlank { "用户名" } ?: "用户名"

/**
 * 固定密码行的显示名：自定义名为空/空串时回退为“密码”。
 */
internal val Account.passwordRowLabel: String
    get() = passwordLabel?.ifBlank { "密码" } ?: "密码"

// ==================== 应用设置模型 ====================

// 注意：AppSettings 不进 vault.bin（那是账号数据），软件设置走两层——
// DataStore 存真值（见 data/config/Preferences.kt），可选 appsettings.json 只读覆盖。
// 字段默认值须与 AccountApp 首次从 DataStore 读取失败时的回退值保持一致。

/**
 * 应用全局设置数据模型（内存态或配合特定序列化器使用）。
 */
internal data class AppSettings(
    val biometricEnabled: Boolean = false,
    val autoLockMinutes: Int = 5,
    val themeMode: String = BuildConfig.DEFAULT_THEME_MODE,
    val accentTheme: String = "green",
    val languageTag: String = "zh-CN",
    val customThemeJson: String = "",
    val customThemes: List<SavedTheme> = emptyList(),
    val clipboardClearSeconds: Int = 30,
    val allowScreenshots: Boolean = false,
    /** 隐藏内容的掩码字符，可被 appsettings.json 外挂覆盖。 */
    val maskChar: String = "•"
)

// ==================== 持久化仓库模型 ====================

/**
 * 持久化密码库的整体数据结构。
 * @param version 数据版本号，用于后续平滑升级迁移
 * @param accounts 账号列表
 * @param groups 分组列表
 * @param selectedGroupId 当前选中的分组 ID
 */
@Serializable
internal data class PersistedVault(
    val version: Int = 1,
    val accounts: List<Account>,
    val groups: List<Group>,
    val selectedGroupId: String = "default"
)

// ==================== 初始默认数据 ====================

/**
 * 首次安装时的默认分组列表。
 */
internal val initialGroups = listOf(
    Group("default", "默认", GroupKind.DEFAULT),
    Group("dynamic", "动态密码", GroupKind.DYNAMIC),
    Group("social", "社交媒体", GroupKind.CUSTOM),
    Group("games", "游戏娱乐", GroupKind.CUSTOM),
    Group("bank", "银行卡", GroupKind.CUSTOM),
    Group("work", "工作", GroupKind.CUSTOM)
)

/**
 * 首次安装的示例账号列表，用于演示用法，用户可自行删除；不含任何真实凭据。
 */
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