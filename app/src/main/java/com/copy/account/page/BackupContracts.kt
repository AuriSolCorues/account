/**
 * BackupScreen 与 AccountApp 之间的接线契约：三个语义接口 + 两轨归一的行模型。
 * 页面只依赖这里的抽象；文件 IO、加解密与线程切换全部由实现方（AccountApp）承担。
 */
package com.copy.account.page

/**
 * 两轨（SAF / 直写）备份文件的统一读写门面。
 * 实现方负责全部文件 IO 与线程切换：回调式成员（[refresh]/[delete]）在主线程回调；
 * [read] 为同步调用，页面会在 IO 分发器上调用它，实现方无需（也无法）自行切线程。
 */
internal interface BackupFileStore {

    /**
     * 刷新两轨备份文件列表（SAF + 直写），主线程调用；内部自行处理异步与线程切换，
     * 完成后主线程回调合并后的行数据（两轨归一为 [BackupRowUi]，排序由实现方负责）。
     */
    fun refresh(onResult: (List<BackupRowUi>) -> Unit)

    /** 读一个条目（SAF 轨 uri / 直写轨 file），失败返回 null。IO 在实现方。 */
    fun read(row: BackupRowUi): ByteArray?

    /** 删除一个条目，完成后主线程回调是否成功。IO 与线程切换在实现方。 */
    fun delete(row: BackupRowUi, onDone: (Boolean) -> Unit)
}

/**
 * 备份导出动作组：加密 .acc、明文 JSON、首次导出用主密码换导出密钥。
 * 回调式成员（[exportEncrypted]/[exportPlaintext]）由实现方处理异步与线程切换并在主线程回调；
 * [prepareExport] 为同步调用（含 PBKDF2，页面会在 Default 分发器上调用）。
 */
internal interface BackupExporter {

    /** 加密导出 .acc；门禁失败/缺密码/IO 错误返回 Result.failure（异常 message 已是用户可读文案）。 */
    fun exportEncrypted(onDone: (Result<String>) -> Unit)

    /** 明文导出 JSON。失败语义同 [exportEncrypted]：message 为用户可读文案。 */
    fun exportPlaintext(onDone: (Result<String>) -> Unit)

    /** 首次导出用主密码换导出密钥：密码错返回 failure（"主密码错误"）。 */
    fun prepareExport(password: String): Result<Unit>
}

/**
 * 备份目录组：轨道模式、授权状态、目录提示与目录相关动作。
 * 注意：这是普通 interface 而非 Compose 状态——实现方应保证属性现读现用
 * （getter 每次返回容器当前值），页面靠每次重组时的读取拿到最新状态。
 */
internal interface BackupDirectoryOps {

    /** true=直写轨（内部存储 + 所有文件访问权限），false=SAF 轨（已授权目录）。 */
    val directBackup: Boolean

    /** 直写轨的「所有文件访问」权限是否已授予。 */
    val storageAccessGranted: Boolean

    /** SAF 轨已授权的目录 uri；未授权为 null。 */
    val backupTreeUri: String?

    /** 备份子文件夹（内部存储或授权目录下的相对路径）。 */
    val backupFolder: String

    /** 目录相关提示文案（授权结果、改名结果等）；空串表示无提示。 */
    val directoryMessage: String

    /** 修改备份子路径；raw 非法（绝对路径 / ..）或保存失败返回 false。 */
    fun changeBackupFolder(raw: String): Boolean

    /** 发起 SAF 目录选择。 */
    fun chooseDirectory()

    /** 发起直写轨「所有文件访问」权限申请。 */
    fun requestStorageAccess()
}

/**
 * 两轨（SAF/直写）统一成一列的备份行模型，由 [BackupFileStore.refresh] 的实现方构造。
 * key：行唯一标识——直写轨为 file 绝对路径，SAF 轨为 uri 字符串；
 * direct：轨道标记，true=直写轨、false=SAF 轨，供 [BackupFileStore.read]/[BackupFileStore.delete] 分派。
 */
internal data class BackupRowUi(
    val key: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val direct: Boolean
)
