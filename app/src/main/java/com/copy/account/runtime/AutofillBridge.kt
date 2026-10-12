/**
 * 职责：自动填充的进程内明文通道——解锁时由 UnlockSession 灌入脱敏快照与 DEK 副本，
 *       手动关闭（设置页）/关开关/进程死时清零。AppAutofillService 读快照做匹配，
 *       保存时用 DEK 走 VaultStore。active 现为 Compose 可观察（设置页显示窗口状态）。
 * 架构位置：runtime/ 进程级单例（object），是 UI 进程与 AutofillService 之间唯一被认可的
 *           明文交接点；除此之外任何组件不得持有未加密的账号数据副本。
 * 安全边界：非锁定态无数据；DEK 仅存于内存，永不落盘、不打日志；快照清空用逐字段覆盖
 *           （先 fill(0) 再置 null/emptyList）而非仅断引用，避免堆转储残留明文。
 * Python 类比：≈ 模块级全局变量 + 存取函数；@Volatile ≈ 保证多线程下「读到的一定是最新
 *           一次写入」，替代粗粒度锁。
 */
package com.copy.account.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.copy.account.data.model.Account

/** 快照行：自动填充匹配与填充所需的最小明文字段。 */
internal data class AutofillAccount(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val url: String,
    val packageName: String?
)

internal object AutofillBridge {
    // 线程安全说明：写入只发生在解锁/锁定（Compose 主线程）与开关切换，读发生在 autofill
    // 的 binder 线程。@Volatile 保证引用级别的可见性与原子替换——列表本身一经发布不再
    // 原地修改，读端要么看旧快照要么看新快照，不存在半更新态，故无需加锁。
    @Volatile var snapshot: List<AutofillAccount> = emptyList()
        private set

    /** DEK 明文副本；enable 时 copyOf 隔离外部后续清零，clear 时 fill(0) 擦除。 */
    @Volatile var dek: ByteArray? = null
        private set

    /** autofill 开关打开 且 会话已解锁；两者任一不满足即视为不活跃，服务端拒绝工作。 */
    // Compose 可观察：设置页组合读取 active，「手动关闭填充窗口」行据此即时刷新；
    // 写入仍在主线程（解锁/清桥），binder 线程只读快照/dek，不受影响。
    var active: Boolean by mutableStateOf(false)
        private set

    /** 解锁且开关打开：灌入快照与 DEK 副本，进入活跃态。 */
    fun enable(data: List<AutofillAccount>, dekCopy: ByteArray) {
        snapshot = data
        dek = dekCopy.copyOf()
        active = true
    }

    /** 解锁态下数据变化时仅刷新快照；非活跃态是空操作（不给死通道喂数据）。 */
    fun refresh(data: List<AutofillAccount>) {
        if (active) snapshot = data
    }

    /** 手动关闭（设置页）/关开关：DEK 逐字节清零、快照整体丢弃、退出活跃态。幂等，可放心多次调用。 */
    fun clear() {
        active = false
        dek?.fill(0)
        dek = null
        snapshot = emptyList()
    }

    /** 按 id 找快照行（填充时把结构化节点映射回账号明文）。 */
    fun findAccount(id: String): AutofillAccount? = snapshot.firstOrNull { it.id == id }

    /** Account 列表 → 快照行：只取填充所需字段，多余的 UI 态一律不带出。 */
    fun toAutofillAccounts(accounts: List<Account>): List<AutofillAccount> = accounts.map {
        AutofillAccount(
            id = it.id,
            name = it.name,
            username = it.username,
            password = it.password,
            url = it.url,
            packageName = it.packageName
        )
    }
}
