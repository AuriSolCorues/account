/**
 * 职责：本体的可选扩展门面——扩展模块的在装发现（action 解析，零包名硬编码）、
 *       设置页拉起、指针文件写入。本体零联网红线不受影响：
 *       这里只有 PackageManager 查询与纯文件 IO，触网发生在扩展进程。
 * 架构位置：runtime/ 层；AccountApp（导出指针）、SettingsScreen（入口行）消费。
 * Python 类比：≈ 一个扩展模块的 host SDK——discover()、launch()、状态文件的写入器。
 */
package com.copy.account.runtime

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/** 扩展桥契约的平台侧部分（action/指针文件名——字符串即契约）。 */
object ExtensionContract {
    /** 扩展设置页的 action（扩展 intent-filter 声明；本体按此发现在装扩展）。 */
    const val ACTION_SETTINGS = "com.copy.account.action.SYNC_SETTINGS"
    const val POINTER_FILE_NAME = ".account-sync.json"
}

/**
 * 在装扩展查询（设置页「扩展」入口行的显示条件）。
 * 按 action 解析，不硬编码任何扩展包名——公开仓库零具体扩展字样。
 * 返回 null = 无扩展在装；非 null = 首个响应扩展的拉起 Intent（可直接 startActivity）。
 */
fun resolveExtensionSettingsIntent(context: Context): Intent? =
    context.packageManager.queryIntentActivities(
        Intent(ExtensionContract.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
        0
    ).firstOrNull()?.let { info ->
        Intent(ExtensionContract.ACTION_SETTINGS)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

/**
 * 拉起扩展设置页（会话让渡的唯一起点）：解析 intent → 借出会话 → startActivity。
 * borrow 必须先行——本体 ON_STOP 是「让渡」不是「离开」，否则拉起瞬间就被锁定链路回锁。
 * 两处复用：设置页「扩展」行、解锁后回跳扩展页（consumeResumeExtension 消费点）。
 * 返回 false = 无扩展在装，调用方自行兜底。
 */
fun launchExtensionSettings(context: Context): Boolean {
    val intent = resolveExtensionSettingsIntent(context) ?: return false
    ExtensionPlatformState.borrowSession(android.os.SystemClock.uptimeMillis())
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** 跳板 action（扩展 intent-filter 声明；本体按此发现在装扩展，零包名硬编码）。 */
const val ACTION_KICK_SYNC = "com.copy.account.action.SYNC_KICK"

/**
 * 导出后自动上传接线（fire-and-forget）：按 action 解析在装扩展的跳板 Activity →
 * startActivity（Intent 只带 mode/range 动词参数，零凭据过桥——凭据由扩展服务自取）。
 * 结果痕迹只在扩展侧经桥 appendLog 落本体操作日志（用户拍板：无任何弹扰）。
 * 返回 false = 无扩展在装（静默跳过，导出主流程不受影响）。
 * 本体零联网红线：这里只有 PackageManager 查询与 startActivity，触网发生在扩展进程。
 */
fun kickExtensionSync(context: Context): Boolean {
    val intent = context.packageManager.queryIntentActivities(
        Intent(ACTION_KICK_SYNC).addCategory(Intent.CATEGORY_DEFAULT), 0
    ).firstOrNull()?.let { info ->
        Intent(ACTION_KICK_SYNC)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
            .putExtra("sync_mode", "Upload")
            .putExtra("sync_range_backup", true)
    } ?: return false
    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * 写指针文件：扩展的目录继承唯一来源。直写轨时在备份位置/子目录变更与导出成功后调用，
 * 幂等重写；SAF 轨不写（扩展不支持该轨，口径一致）。
 * 指针存本体私有沙箱（filesDir）——共享存储根不再有无主的 .account-sync.json，
 * 第三方清理工具删不掉、竞品改不了（C1 缺陷修复）。扩展不经文件读它：目录继承
 * 经桥 state/list 管道由 provider 代读（扩展进程从头到尾摸不到这个文件）。
 * 纯文件 IO，本体零新增权限、零网络。
 * 注意 backupFolder 是相对内部存储根的子路径（如 Account/account），指针记
 * 绝对路径（/storage/emulated/0/Account），provider 按绝对路径分发。
 */
fun writeSyncPointer(context: Context, backupFolder: String) {
    runCatching {
        val storageRoot = android.os.Environment.getExternalStorageDirectory()
        // <root>/Account/account → 父目录即 Account/ 根（范围 B 根，范围 A = 其下 account/）
        val accountRoot = File(storageRoot, backupFolder).parentFile ?: storageRoot
        val json = JSONObject(
            linkedMapOf(
                "v" to 1,
                "accountRoot" to accountRoot.absolutePath,
                "updatedMs" to System.currentTimeMillis()
            )
        )
        val file = File(context.filesDir, ExtensionContract.POINTER_FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
        // 迁移清理：旧版指针遗留在共享存储根，删掉（C1 收尾，失败无所谓）
        runCatching { File(storageRoot, ExtensionContract.POINTER_FILE_NAME).delete() }
    }
}
