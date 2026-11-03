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

object BridgeFiles {
    const val POINTER = ".account-sync.json"
}

class BridgeException(message: String) : Exception(message)

class ExtensionBridgeProvider : ContentProvider() {

    private companion object {
        private const val EXTENSION_LOG_TYPE = "extension"
    }

    override fun onCreate(): Boolean {
        return true
    }

    private fun handleRelease() {
        ExtensionPlatformState.releaseSession()
        val hook = ExtensionPlatformState.releaseHook
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (hook != null) main.post(hook) else {
            ExtensionPlatformState.locked = true
            AutofillBridge.onSessionLocked()
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw BridgeException("不支持 insert")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw BridgeException("不支持 delete")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw BridgeException("不支持 update")

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?
    ): Cursor = throw BridgeException("不支持 query")

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        return when (method) {
            BridgeContract.METHOD_LIST -> bundleList(extras)
            BridgeContract.METHOD_STATE -> bundleState()
            BridgeContract.METHOD_SCHEDULE_RESUME -> {
                ExtensionPlatformState.scheduleResumeExtension(android.os.SystemClock.uptimeMillis()); Bundle.EMPTY
            }
            BridgeContract.METHOD_RELEASE_SESSION -> { handleRelease(); Bundle.EMPTY }
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
            BridgeContract.METHOD_PUSH_WEBDAV_CONFIG -> { handlePushWebdavConfig(extras); Bundle.EMPTY }
            BridgeContract.METHOD_FETCH_WEBDAV_CONFIG -> handleFetchWebdavConfig()
            BridgeContract.METHOD_APPEND_LOG -> { handleAppendLog(extras); Bundle.EMPTY }
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

    private fun handlePushWebdavConfig(extras: Bundle?) {
        val b = requireNotNull(extras) { "缺少 extras" }
        val url = requireNotNull(b.getString(BridgeContract.KEY_WEBDAV_URL)) { "缺少 url" }
        val user = requireNotNull(b.getString(BridgeContract.KEY_WEBDAV_USER)) { "缺少 user" }
        val passwordEnc = requireNotNull(b.getString(BridgeContract.KEY_WEBDAV_PASSWORD_ENC)) { "缺少 passwordEnc" }
        com.copy.account.runtime.WebDavConfigVault.store(context!!, url, user, passwordEnc)
    }

    private fun handleFetchWebdavConfig(): Bundle = bundle {
        com.copy.account.runtime.WebDavConfigVault.fetchPending(context!!)?.let { rec ->
            putString(BridgeContract.KEY_WEBDAV_URL, rec.url)
            putString(BridgeContract.KEY_WEBDAV_USER, rec.user)
            putString(BridgeContract.KEY_WEBDAV_PASSWORD_ENC, rec.passwordEnc)
        }
    }

    private val knownLogTypes = setOf(
        LogEvents.SYNC,
        LogEvents.ACCOUNT_ADD,
        LogEvents.ACCOUNT_DELETE,
        LogEvents.CRASH,
        LogEvents.EXPORT
    )

    private fun handleAppendLog(extras: Bundle?) {
        val b = requireNotNull(extras) { "缺少 extras" }
        val type = b.getString(BridgeContract.KEY_LOG_TYPE).orEmpty()
        val summary = requireNotNull(b.getString(BridgeContract.KEY_LOG_SUMMARY)) { "缺少 summary" }
        if (summary.isBlank()) return
        val details = b.getStringArrayList(BridgeContract.KEY_LOG_DETAILS) ?: arrayListOf()
        appendLog(
            context!!,
            if (type in knownLogTypes) type else EXTENSION_LOG_TYPE,
            summary,
            details
        )
    }

    private inline fun bundle(fill: android.os.Bundle.() -> Unit): Bundle = Bundle().apply(fill)

    private fun resolveRel(extras: Bundle?): File {
        val rel = requireNotNull(extras?.getString(BridgeContract.KEY_REL)) { "缺少 rel" }
        val target = File(accountRoot(), rel)
        check(isInside(target, accountRoot())) { "路径越界：$rel" }
        return target
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        val write = when (mode) {
            "r" -> false
            "w", "wt" -> true
            else -> throw BridgeException("不支持 mode：$mode")
        }
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

    private fun bundleList(extras: Bundle?): Bundle {
        val scope = extras?.getString(BridgeContract.KEY_SCOPE) ?: throw BridgeException("缺少 scope")
        val root = accountRoot()
        val arr = JSONArray()
        when (scope) {
            "backup" -> {
                val dir = File(root, "account")
                for (f in dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".acc", ignoreCase = true) }
                    .sortedByDescending { it.lastModified() }) {
                    arr.put(fileJson(f.name, f.length(), f.lastModified()))
                }
            }
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

    private fun fileJson(rel: String, size: Long, mtimeMs: Long): JSONObject =
        JSONObject().put("rel", rel).put("size", size).put("mtimeMs", mtimeMs)

    private fun bundleState(): Bundle = Bundle().apply {
        putInt(BridgeContract.KEY_V, BridgeContract.VERSION)
        putBoolean(BridgeContract.KEY_LOCKED, ExtensionPlatformState.locked)
        putString(BridgeContract.KEY_THEME_MODE, ExtensionPlatformState.themeMode)
        putString(BridgeContract.KEY_THEME_ACCENT, ExtensionPlatformState.accentTheme)
        putString(BridgeContract.KEY_THEME_CUSTOM, ExtensionPlatformState.customThemeJson)
    }

    private fun root(): File = Environment.getExternalStorageDirectory()

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

    private fun isInside(target: File, base: File): Boolean {
        val c = target.canonicalFile
        val b = base.canonicalFile
        return c != b && c.path.startsWith(b.path + File.separator)
    }
}
