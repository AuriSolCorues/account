/**
 * 职责：ACCOUNTBOX v13 明文格式的双向编解码——与「账号本子」App 互导。导出把密码库
 *       转成对方能直接导入的文件（首行 ACCOUNTBOX_JSON_13 标识 + JSON 正文）；导入把
 *       该格式还原成 PersistedVault。明文轨从此以对方格式为准（自家明文格式删除，
 *       加密备份仍走 AccCodec 的 .acc）。
 * 架构位置：security/ 纯编解码层，无 Android 依赖、无隐式时钟（exportedAtMs 由调用方
 *           传入，保持可单测）；接线在 AccountApp（导出）与 BackupScreen→onImport
 *           （导入，与 .acc 共用确认对话框，按首行标识嗅探分流）。
 * 字段映射（本应用 → ACCOUNTBOX）：
 *   Account.name            → accountList[].name
 *   Account.username        → accountItemList[itemName="账号"].itemValue
 *   Account.password        → accountItemList[itemName="密码"].itemValue
 *   Account.customFields    → accountItemList[]（其余项原样追加）
 *   Account.groups(自定义组) → tagList[].tagName（按 groups 列表解出显示名）
 *   Account.hasTotp+secret  → dynamicPassword（type: TOTP=3 / STEAM=2；废弃字段恒空串）
 *   —— favorite 恒 false（无收藏概念）；lastEditTime 恒导出时刻；url/packageName/
 *      掩码位/totpCounter 无对应概念，不导出（迁移到账号本子时这些信息丢失，已确认）。
 *   导入反向映射：tagList → 自定义组（id="ab-"+tagName）；accountItemList 按 itemName
 *   认领「账号」/「密码」，其余进 customFields；favorite/lastEditTime 忽略；账号 id 新生成。
 * Python 类比：≈ json.dumps/json.loads 加一层业务字段翻译；首行标识 ≈ shebang。
 */
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

/** 首行标识与格式版本号（账号本子当前版本 13）。 */
internal const val ACCOUNTBOX_MAGIC = "ACCOUNTBOX_JSON_13"
internal const val ACCOUNTBOX_VERSION = 13

/** 账号本子动态密码类型枚举：2=STEAM，3=TOTP（对方格式定义）。 */
private const val DYNAMIC_TYPE_STEAM = 2
private const val DYNAMIC_TYPE_TOTP = 3

@Serializable
private data class AbDynamicPassword(
    val type: Int,
    val algorithm: String,
    val period: Int,
    val secret: String,
    val digits: Int,
    // 废弃字段：对方格式仍携带，导出恒空串、导入忽略。
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
    // 废弃字段：恒空列表导出，导入忽略。
    val dynamicPasswordList: List<AbDynamicPassword> = emptyList(),
    val version: Int = ACCOUNTBOX_VERSION,
    val tagList: List<AbTag> = emptyList()
)

/** 对方格式的宽松解析：未知/废弃字段一律忽略。 */
private val abJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

/**
 * 导出为 ACCOUNTBOX v13 明文格式：首行魔数 + 换行 + pretty JSON。
 * @param vault 密码库全量内容
 * @param exportedAtMs 导出时刻毫秒值（favorite/lastEditTime 等占位字段统一用它）
 */
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

/**
 * 导入 ACCOUNTBOX v13 明文文件：首行魔数校验 → JSON 还原 PersistedVault。
 * 返回纯库数据（无软件设置——对方格式没有该概念）。
 */
internal fun importAccountBoxJson(bytes: ByteArray): Result<PersistedVault> = runCatching {
    val text = bytes.toString(Charsets.UTF_8)
    val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
    require(firstLine == ACCOUNTBOX_MAGIC) { "不是账号本子明文格式（缺少 ACCOUNTBOX_JSON_13 标识）" }
    val document = abJson.decodeFromString(AbDocument.serializer(), text.substringAfter('\n'))
    // 标签 → 自定义组：id 用确定性前缀，避开固定组（default/dynamic）与运行时生成 id 的冲突。
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
            // 同批导入 now 相同：uid 随机后缀防撞号；内嵌毫秒即导入时间（修改锚）。
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
