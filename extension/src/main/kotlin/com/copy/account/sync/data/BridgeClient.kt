package com.copy.account.sync.data

import android.content.Context
import android.net.Uri
import com.copy.account.platform.BridgeContract
import org.json.JSONArray
import java.io.OutputStream

internal data class SyncFileRecord(val rel: String, val size: Long, val mtimeMs: Long)

internal data class PlatformState(
    val locked: Boolean,
    val themeMode: String,
    val accentTheme: String,
    val customThemeJson: String,
    val unreachable: Boolean = false
)

internal data class TouchSessionReply(
    val lastTouchMs: Long,
    val autoLockSec: Int,
    val sessionAlive: Boolean,
)

internal class BridgeClient(context: Context) {

    private val resolver = context.contentResolver
    private val authority = Uri.parse("content://" + BridgeContract.AUTHORITY)

    object Scope {
        const val BACKUP = "backup"
        const val OTHER = "other"
    }

    private fun call(method: String, arg: String? = null, extras: android.os.Bundle? = null): android.os.Bundle =
        resolver.call(authority, method, arg, extras) ?: error("数据桥无响应（$method）——请确认主应用已安装且同签名")

    private fun bundleOf(vararg pairs: Pair<String, Any?>): android.os.Bundle =
        android.os.Bundle().apply { pairs.forEach { (k, v) -> when (v) {
            is String -> putString(k, v)
            is Long -> putLong(k, v)
            is Int -> putInt(k, v)
            is Boolean -> putBoolean(k, v)
            else -> error("不支持的 Bundle 类型：${v?.javaClass}")
        } } }

    fun list(scope: String): List<SyncFileRecord> {
        val arr = JSONArray(
            call(BridgeContract.METHOD_LIST, extras = bundleOf(BridgeContract.KEY_SCOPE to scope))
                .getString(BridgeContract.KEY_JSON) ?: "[]"
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SyncFileRecord(o.getString("rel"), o.getLong("size"), o.getLong("mtimeMs"))
        }
    }

    fun isLocked(): Boolean = platformState().let { it.locked || it.unreachable }

    fun scheduleResumeExtension() {
        runCatching { call(BridgeContract.METHOD_SCHEDULE_RESUME) }
    }

    fun releaseSession() {
        runCatching { call(BridgeContract.METHOD_RELEASE_SESSION) }
    }

    fun touchSession(): TouchSessionReply? = runCatching {
        val b = call(BridgeContract.METHOD_TOUCH_SESSION)
        TouchSessionReply(
            lastTouchMs = b.getLong(BridgeContract.KEY_LAST_TOUCH_MS, 0L),
            autoLockSec = b.getInt(BridgeContract.KEY_AUTO_LOCK_SEC, 0),
            sessionAlive = b.getBoolean(BridgeContract.KEY_SESSION_ALIVE, false)
        )
    }.getOrNull()

    fun pushWebdavConfig(url: String, user: String, passwordEnc: String) {
        runCatching {
            call(
                BridgeContract.METHOD_PUSH_WEBDAV_CONFIG,
                extras = bundleOf(
                    BridgeContract.KEY_WEBDAV_URL to url,
                    BridgeContract.KEY_WEBDAV_USER to user,
                    BridgeContract.KEY_WEBDAV_PASSWORD_ENC to passwordEnc
                )
            )
        }
    }

    fun fetchWebdavConfig(): Triple<String, String, String>? = runCatching {
        val b = call(BridgeContract.METHOD_FETCH_WEBDAV_CONFIG)
        if (!b.getBoolean("pending", false)) return null
        Triple(
            b.getString(BridgeContract.KEY_WEBDAV_URL) ?: return null,
            b.getString(BridgeContract.KEY_WEBDAV_USER) ?: return null,
            b.getString(BridgeContract.KEY_WEBDAV_PASSWORD_ENC) ?: return null
        )
    }.getOrNull()

    fun platformState(): PlatformState = runCatching {
        val b = call(BridgeContract.METHOD_STATE)
        val v = b.getInt(BridgeContract.KEY_V, 0)
        val recognized = v == BridgeContract.VERSION
        PlatformState(
            locked = b.getBoolean(BridgeContract.KEY_LOCKED, true),
            themeMode = if (recognized) b.getString(BridgeContract.KEY_THEME_MODE) ?: "system" else "system",
            accentTheme = if (recognized) b.getString(BridgeContract.KEY_THEME_ACCENT) ?: "green" else "green",
            customThemeJson = if (recognized) b.getString(BridgeContract.KEY_THEME_CUSTOM) ?: "" else "",
            unreachable = !recognized
        )
    }.getOrElse {
        PlatformState(locked = true, themeMode = "system", accentTheme = "green", customThemeJson = "", unreachable = true)
    }

    fun readBytes(rel: String): ByteArray =
        resolver.openInputStream(contentUri(rel))?.use { it.readBytes() } ?: error("本地缺 $rel")

    fun writeStream(rel: String): OutputStream =
        resolver.openOutputStream(contentUri(rel), "w") ?: error("本地写失败 $rel")

    fun isDirectory(rel: String): Boolean =
        call(BridgeContract.METHOD_STAT, extras = bundleOf(BridgeContract.KEY_REL to rel))
            .getBoolean(BridgeContract.KEY_IS_DIR, false)

    fun sizeOf(rel: String): Long =
        call(BridgeContract.METHOD_SIZE_OF, extras = bundleOf(BridgeContract.KEY_REL to rel))
            .getLong(BridgeContract.KEY_SIZE, 0L)

    fun deleteLocal(rel: String) = call(BridgeContract.METHOD_DELETE_LOCAL, extras = bundleOf(BridgeContract.KEY_REL to rel))

    fun move(fromRel: String, toRel: String) =
        call(BridgeContract.METHOD_MOVE, extras = bundleOf(BridgeContract.KEY_REL to fromRel, BridgeContract.KEY_TO to toRel))

    fun touchLocal(rel: String, mtimeMs: Long) =
        call(BridgeContract.METHOD_TOUCH, extras = bundleOf(BridgeContract.KEY_REL to rel, BridgeContract.KEY_MTIME_MS to mtimeMs))

    private fun contentUri(rel: String): Uri =
        Uri.Builder().scheme("content").authority(BridgeContract.AUTHORITY).path("/$rel").build()
}
