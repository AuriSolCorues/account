/**
 * 职责：自动填充的进程内数据通道——解锁时由 UnlockSession 灌入脱敏快照与 DEK 副本，
 *       锁定（onSessionLocked）时清快照与 DEK，只留「公开索引」供锁定态展示账号卡；
 *       手动关闭（设置页）/关开关（clear）连索引一起清。AppAutofillService 解锁态读
 *       快照做匹配，锁定态读索引做匹配；保存时用 DEK 走 VaultStore。
 *       active 现为 Compose 可观察（设置页经 AccountApp 的 prop 显示窗口状态）。
 * 架构位置：runtime/ 进程级单例（object），是 UI 进程与 AutofillService 之间唯一被认可的
 *           交接点；除此之外任何组件不得持有未加密的账号数据副本。
 * 安全边界：快照（username/password）与 DEK 只在解锁态驻留，锁定即 fill(0)/清空；
 *           公开索引只含展示与匹配所需字段（id/名称/url/包名），不含任何凭据字段，
 *           以普通 JSON 持久化到应用沙箱（filesDir），冷启动锁定态据此仍能显示账号卡；
 *           锁定态填充经 AutofillAuthActivity 验证后瞬时构建，不再重开窗口（零明文驻留）。
 * Python 类比：≈ 模块级全局变量 + 存取函数；@Volatile ≈ 保证多线程下「读到的一定是最新
 *           一次写入」，替代粗粒度锁。
 */
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

/** 快照行：自动填充匹配与填充所需的最小明文字段。 */
internal data class AutofillAccount(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val url: String,
    val packageName: String?
)

/**
 * 公开索引行：锁定态账号卡的展示与匹配字段。刻意不含 username/password——
 * 锁定后内存与磁盘（索引文件）里都不允许出现任何凭据。
 */
@Serializable
internal data class AutofillAccountLight(
    val id: String,
    val name: String,
    val url: String,
    val packageName: String?
)

internal object AutofillBridge {
    private const val INDEX_FILE_NAME = "autofill_public_index.json"

    // 线程安全说明：写入只发生在解锁/锁定/开关切换（Compose 主线程），读发生在 autofill
    // 的 binder 线程。@Volatile 保证引用级别的可见性与原子替换——列表本身一经发布不再
    // 原地修改，读端要么看旧快照要么看新快照，不存在半更新态，故无需加锁。
    @Volatile var snapshot: List<AutofillAccount> = emptyList()
        private set

    /** DEK 明文副本；enable 时 copyOf 隔离外部后续清零，clear/onSessionLocked 时 fill(0) 擦除。 */
    @Volatile var dek: ByteArray? = null
        private set

    /** 公开索引：锁定态账号卡的数据源；解锁态由 enable/refresh 同步维护，clear 连它一起清。 */
    @Volatile var publicIndex: List<AutofillAccountLight> = emptyList()
        private set

    /** autofill 开关打开 且 会话已解锁；两者任一不满足即视为不活跃，服务端拒绝工作。 */
    // Compose 可观察：AccountApp 组合读取 active 并经 props 下发设置页，窗口行据此即时刷新；
    // 写入仍在主线程（解锁/锁定/清桥），binder 线程只读快照/dek/索引，不受影响。
    var active: Boolean by mutableStateOf(false)
        private set

    /**
     * 解锁且开关打开：灌入快照与 DEK 副本，进入活跃态；同步重建公开索引并落盘，
     * 保证用户下次锁定/冷启动后账号卡与最新库一致。
     */
    fun enable(context: Context, data: List<AutofillAccount>, dekCopy: ByteArray) {
        snapshot = data
        dek = dekCopy.copyOf()
        active = true
        rebuildIndex(context, data)
    }

    /** 解锁态下数据变化时仅刷新快照与索引；非活跃态是空操作（不给死通道喂数据）。 */
    fun refresh(context: Context, data: List<AutofillAccount>) {
        if (!active) return
        snapshot = data
        rebuildIndex(context, data)
    }

    /**
     * 锁定：快照与 DEK 立即失效（fill(0) + 清空引用），退出活跃态；
     * 公开索引保留——锁定态账号卡据此展示与匹配。与 clear 的区别：索引是否清。
     */
    fun onSessionLocked() {
        active = false
        dek?.fill(0)
        dek = null
        snapshot = emptyList()
    }

    /** 手动关闭（设置页）/关开关：连公开索引一起清，彻底退出填充能力，直到下次解锁重灌。幂等。 */
    fun clear(context: Context) {
        active = false
        dek?.fill(0)
        dek = null
        snapshot = emptyList()
        publicIndex = emptyList()
        runCatching { indexFile(context).delete() }
    }

    /** 按 id 找快照行（填充时把结构化节点映射回账号明文）。锁定态快照为空，返回 null。 */
    fun findAccount(id: String): AutofillAccount? = snapshot.firstOrNull { it.id == id }

    /** Account 列表 → 快照行：只取填充所需字段，多余的 UI 态一律不带出。 */
    fun toAutofillAccounts(accounts: List<Account>): List<AutofillAccount> = accounts.map { it.toAutofillAccount() }

    /**
     * 冷启动恢复公开索引：进程死后索引内存态丢失，从沙箱文件读回（只含公开字段）。
     * 文件缺失或解析失败一律降级为空索引——锁定态退占位卡（验证后按目标平铺），不阻塞填充。
     * 只在 AutofillService 进程需要锁定态匹配时调用（App 主进程不主动预热）。
     */
    fun loadPublicIndexIfEmpty(context: Context) {
        if (publicIndex.isNotEmpty()) return
        val file = indexFile(context)
        if (!file.exists()) return
        // 过期自检：索引文件比密码库旧 = 库在索引落盘之后又被写过（历史脏数据/写索引
        // 中途进程死）→ 丢弃索引退占位卡，绝不展示旧名字。两者并存时时间差容忍 2 秒
        // （原子写库与写索引是两次独立落盘，毫秒级先后属正常时序）。
        val vaultLast = File(context.filesDir, "vault.bin").lastModified()
        if (vaultLast - file.lastModified() > 2_000L) {
            runCatching { file.delete() }
            return
        }
        publicIndex = runCatching {
            indexJson.decodeFromString(ListSerializer(AutofillAccountLight.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    // ==================== 公开索引持久化 ====================

    private val indexJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun indexFile(context: Context): File = File(context.filesDir, INDEX_FILE_NAME)

    private fun rebuildIndex(context: Context, data: List<AutofillAccount>) {
        val index = data.map {
            AutofillAccountLight(id = it.id, name = it.name, url = it.url, packageName = it.packageName)
        }
        publicIndex = index
        // 索引落盘失败 → 删旧文件并清内存索引（宁缺勿旧）：留着旧文件，冷启动/锁定态会
        // 展示已改名/已删除账号的旧名字卡。失败后锁定态退占位卡，验证路径不受影响。
        runCatching {
            indexFile(context).writeText(indexJson.encodeToString(ListSerializer(AutofillAccountLight.serializer()), index))
        }.onFailure {
            runCatching { indexFile(context).delete() }
            publicIndex = emptyList()
        }
    }
}

/** 单账号映射：快照行/自愈回包共用的六字段转换（service 层锁定态单段解封也走这里）。 */
internal fun Account.toAutofillAccount(): AutofillAccount = AutofillAccount(
    id = id,
    name = name,
    username = username,
    password = password,
    url = url,
    packageName = packageName
)
