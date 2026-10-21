/**
 * 职责：本体 ⇆ 扩展模块的文件数据桥（可选扩展机制的数据面）——本体授权目录内的备份
 *       数据经 ContentProvider 供给同签名扩展：清单/锁态/文件操作经 call() 一次往返
 *       （JSON/标量），文件字节经 openFile 流式转发（ParcelFileDescriptor，全程不整块
 *       驻留内存）。
 *       门禁 = BRIDGE 签名权限（同 keystore 才进得来）+ 调用方包名白名单（纵深）。
 *       隔离红线不变：本体零联网；本类也是纯本地文件 IO。
 * 架构位置：本体 runtime 层（ContentProvider 必须在带 application 的模块）。
 * Python 类比：≈ 一个受限的 RPC 文件服务端——校验来路 → 按管道名分发 → 流式读写。
 */
package com.copy.account.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.copy.account.platform.BridgeContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 桥管道名与约定键的契约在 shared/platform/BridgeContract（编译期同源）。点文件名如下。 */
object BridgeFiles {
    const val POINTER = ".account-sync.json"
}

/** provider 内部异常：call() 直接抛（系统折成 RuntimeException 过桥，扩展侧捕获转文案）。 */
class BridgeException(message: String) : Exception(message)


class ExtensionBridgeProvider : ContentProvider() {

    // ===== 基座 =====

    private companion object {
        // （历史）ACTION_RELEASE 广播常量随广播通道废弃而删除；回锁走 METHOD_RELEASE_SESSION。
    }

    override fun onCreate(): Boolean {
        // （历史）扩展回锁曾走跨包广播 + RECEIVER_EXPORTED receiver——ColorOS 对后台跨包
        // 广播投递不可信（冻结/延迟），锁定时序整条断链。已废弃：改经 call()
        // METHOD_RELEASE_SESSION 同步 Binder（见下），顺带撤掉对外广播攻击面。
        return true
    }

    /**
     * 会话归还（扩展页退后台/被销毁时经 call() 同步调达）：清借出标志 + 执行归还钩子
     * （钩子 = 真 lockApp：清 DEK/撤填充桥，由 UnlockSession 注册——活会话才有权锁定）。
     * 没钩子（本体没解锁过/会话已重建空窗）时只翻单例——保守正确：locked=true 永远安全。
     * 注意 provider call 跑在 Binder 线程：锁定动作碰 Compose/主线程状态，必须 post 回主线程。
     */
    private fun handleRelease() {
        ExtensionPlatformState.releaseSession()
        val hook = ExtensionPlatformState.releaseHook
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (hook != null) main.post(hook) else {
            // 无活会话（进程刚重启/未解锁过）：翻单例 + 撤填充桥（保守等价动作）
            ExtensionPlatformState.locked = true
            AutofillBridge.onSessionLocked()
        }
    }

    /** 仅满足抽象：数据桥不走表语义，一律 call()/openFile()。 */
    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw BridgeException("不支持 insert")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw BridgeException("不支持 delete")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw BridgeException("不支持 update")

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?
    ): Cursor = throw BridgeException("不支持 query")

    // ===== call()：一次往返的 JSON 方法（清单/状态/文件操作） =====

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        return when (method) {
            BridgeContract.METHOD_LIST -> bundleList(extras)
            BridgeContract.METHOD_STATE -> bundleState()
            BridgeContract.METHOD_SCHEDULE_RESUME -> {
                ExtensionPlatformState.scheduleResumeExtension(android.os.SystemClock.uptimeMillis()); Bundle.EMPTY
            }
            BridgeContract.METHOD_RELEASE_SESSION -> { handleRelease(); Bundle.EMPTY }
            // 扩展页触摸心跳：「摸哪儿都算活动」——主线程 touch 本体空闲锁（锚点在
            // IdleLock.start/touch 内同步刷新，单一事实源），响应回锚点+超时+解锁态
            // 三件套，扩展本地倒计时到点与本体 lockApp 同秒（同时锁定）。
            BridgeContract.METHOD_TOUCH_SESSION -> {
                ExtensionPlatformState.idleTouchHook?.let { hook ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post(hook)
                }
                bundle {
                    putLong(BridgeContract.KEY_LAST_TOUCH_MS, ExtensionPlatformState.idleAnchorMs)
                    putInt(BridgeContract.KEY_AUTO_LOCK_SEC, ExtensionPlatformState.idleTimeoutSec)
                    putBoolean(BridgeContract.KEY_SESSION_ALIVE, !ExtensionPlatformState.locked)
                }
            }
            // 敏感配置托管管道（METHOD_PUSH_WEBDAV_CONFIG / METHOD_FETCH_WEBDAV_CONFIG）：
            // 契约常量保留（键名协议不能改）；本体侧实现属扩展敏感配置托管，宿主暂不启用。
            BridgeContract.METHOD_STAT -> bundle { putBoolean(BridgeContract.KEY_IS_DIR, resolveRel(extras).isDirectory) }
            BridgeContract.METHOD_SIZE_OF -> bundle { putLong(BridgeContract.KEY_SIZE, resolveRel(extras).let { if (it.isFile) it.length() else 0L }) }
            BridgeContract.METHOD_DELETE_LOCAL -> { resolveRel(extras).delete(); Bundle.EMPTY }
            BridgeContract.METHOD_MOVE -> {
                val b = requireNotNull(extras) { "缺少 extras" }
                val from = resolveRel(extras)
                val to = File(accountRoot(), requireNotNull(b.getString(BridgeContract.KEY_TO)) { "缺少 to" }).also {
                    check(isInside(it, accountRoot())) { "路径越界：${it.name}" }
                }
                check(from.isFile) { "改名源不存在：${from.name}" }
                to.parentFile?.mkdirs()
                check(from.renameTo(to)) { "改名受阻：${from.name}" }
                Bundle.EMPTY
            }
            BridgeContract.METHOD_TOUCH -> {
                val f = resolveRel(extras)
                val ms = requireNotNull(extras).getLong(BridgeContract.KEY_MTIME_MS, 0L)
                if (f.isFile && ms > 0) f.setLastModified(ms)
                Bundle.EMPTY
            }
            else -> throw BridgeException("未知 method：$method")
        }
    }

    /**
     * 门禁纵深二：BRIDGE 签名权限已由系统校验（同 keystore 才能持有），这里再验
     * 调用方与本体的签名一致（不硬编码任何扩展包名——扩展由使用者放置，本体零具体字样）。
     * 判定按调用方包名缓存：签名在安装期不变，重复验签是纯浪费（每文件边界查锁 =
     * 一次 call → 200 个文件 200 次包名一致命中缓存，零 PackageManager 往返）。
     */
    @Volatile private var verifiedCaller: String? = null

    private fun checkCaller() {
        val pkg = callingPackage ?: throw BridgeException("调用方身份缺失")
        if (pkg == verifiedCaller) return
        val mine = context!!.packageManager
        check(
            mine.checkSignatures(pkg, context!!.packageName) == android.content.pm.PackageManager.SIGNATURE_MATCH
        ) { "调用方签名不符：$pkg" }
        verifiedCaller = pkg
    }

    /** Bundle 构建小助手（call 各分支共用）。 */
    private inline fun bundle(fill: android.os.Bundle.() -> Unit): Bundle = Bundle().apply(fill)

    /** extras.rel → Account/ 根下的 File（越界防御内联复用）。 */
    private fun resolveRel(extras: Bundle?): File {
        val rel = requireNotNull(extras?.getString(BridgeContract.KEY_REL)) { "缺少 rel" }
        val target = File(accountRoot(), rel)
        check(isInside(target, accountRoot())) { "路径越界：$rel" }
        return target
    }

    // ===== openFile：文件字节流式通道（相对路径白名单校验后直通） =====

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        val write = when (mode) {
            "r" -> false
            "w", "wt" -> true
            else -> throw BridgeException("不支持 mode：$mode")
        }
        // uri path 形如 /account/xxx.acc 或 /Account/子/xxx —— 相对共享存储下本体 Account/ 根解析。
        val rel = uri.path?.trim('/') ?: throw BridgeException("缺少路径")
        check(rel.isNotEmpty()) { "缺少路径" }
        val target = File(accountRoot(), rel)
        check(isInside(target, accountRoot())) { "路径越界：$rel" }
        if (write) {
            target.parentFile?.mkdirs()
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE)
        }
        check(target.isFile) { "本地缺 $rel" }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // ===== 分发实现 =====

    /** 清单：scope=backup|other → JSON 数组（rel/size/mtimeMs）。目录不存在 = 空数组。 */
    private fun bundleList(extras: Bundle?): Bundle {
        val scope = extras?.getString(BridgeContract.KEY_SCOPE) ?: throw BridgeException("缺少 scope")
        val root = accountRoot()
        val arr = JSONArray()
        when (scope) {
            // 范围 A：account/ 下 .acc 平面清单（mtime 降序，与本体备份页同序）。
            "backup" -> {
                val dir = File(root, "account")
                for (f in dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".acc", ignoreCase = true) }
                    .sortedByDescending { it.lastModified() }) {
                    arr.put(fileJson(f.name, f.length(), f.lastModified()))
                }
            }
            // 范围 B：Account/ 递归 → 清单（跳过 account/ 子树与 account-plain-* 明文导出；
            // rel 一律 '/' 分隔）。清单口径与本体备份页一致。
            "other" -> {
                val rows = buildList {
                    root.walkTopDown()
                        .onEnter { dir -> dir == root || dir.name != "account" }
                        .filter { it.isFile }
                        .forEach { file ->
                            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                            if (!rel.substringAfterLast('/').startsWith("account-plain-")) add(rel to file)
                        }
                }.sortedByDescending { (_, f) -> f.lastModified() }
                for ((rel, f) in rows) arr.put(fileJson(rel, f.length(), f.lastModified()))
            }
            else -> throw BridgeException("未知 scope：$scope")
        }
        return Bundle().apply { putString(BridgeContract.KEY_JSON, arr.toString()) }
    }

    /** 清单记录的统一 JSON 形态（rel/size/mtimeMs 三键，与桥契约消费方对齐）。 */
    private fun fileJson(rel: String, size: Long, mtimeMs: Long): JSONObject =
        JSONObject().put("rel", rel).put("size", size).put("mtimeMs", mtimeMs)

    /**
     * 平台状态快照（state 管道）：契约版本 + 锁态 + 外观三值一次往返带回。
     * locked 取自进程内存单例——本体进程死亡即归 true（fail-closed），
     * 不再有磁盘文件的「残留上次解锁态」弱点。
     */
    private fun bundleState(): Bundle = Bundle().apply {
        putInt(BridgeContract.KEY_V, BridgeContract.VERSION)
        putBoolean(BridgeContract.KEY_LOCKED, ExtensionPlatformState.locked)
        putString(BridgeContract.KEY_THEME_MODE, ExtensionPlatformState.themeMode)
        putString(BridgeContract.KEY_THEME_ACCENT, ExtensionPlatformState.accentTheme)
        putString(BridgeContract.KEY_THEME_CUSTOM, ExtensionPlatformState.customThemeJson)
    }

    // ===== 路径解析 =====

    private fun root(): File = Environment.getExternalStorageDirectory()

    /**
     * Account/ 根 = 指针文件里的 accountRoot（目录继承的锁死单出口）。
     * 指针存本体私有沙箱 filesDir（C1：共享存储根不再有可被第三方删改的无主文件）；
     * 首次读取时自动迁移：旧版指针在共享存储根 → 搬进沙箱 + 删源。
     * 指针缺失/损坏/目录已消失 = 异常（扩展侧提示「请先在主体中导出一次备份」）。
     */
    private fun accountRoot(): File {
        val sandbox = File(context!!.filesDir, BridgeFiles.POINTER)
        if (!sandbox.isFile) {
            val legacy = File(root(), BridgeFiles.POINTER)
            if (legacy.isFile) {
                runCatching {
                    sandbox.parentFile?.mkdirs()
                    legacy.copyTo(sandbox, overwrite = true)
                    legacy.delete()
                }
            }
        }
        check(sandbox.isFile) { "指针文件缺失：请先在主体中导出一次备份" }
        val path = runCatching {
            JSONObject(sandbox.readText()).getString("accountRoot")
        }.getOrNull() ?: throw BridgeException("指针文件损坏：请先在主体中导出一次备份")
        return File(path)
    }

    /** 越界防御：target 必须严格位于 base 之下（canonical 比对，防 ../ 穿越）。 */
    private fun isInside(target: File, base: File): Boolean {
        val c = target.canonicalFile
        val b = base.canonicalFile
        return c != b && c.path.startsWith(b.path + File.separator)
    }
}
