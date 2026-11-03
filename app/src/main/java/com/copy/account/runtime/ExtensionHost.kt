package com.copy.account.runtime

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

object ExtensionContract {
    const val ACTION_SETTINGS = "com.copy.account.action.SYNC_SETTINGS"
    const val POINTER_FILE_NAME = ".account-sync.json"
}

fun resolveExtensionSettingsIntent(context: Context): Intent? =
    context.packageManager.queryIntentActivities(
        Intent(ExtensionContract.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
        0
    ).firstOrNull()?.let { info ->
        Intent(ExtensionContract.ACTION_SETTINGS)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

fun launchExtensionSettings(context: Context): Boolean {
    val intent = resolveExtensionSettingsIntent(context) ?: return false
    ExtensionPlatformState.borrowSession(android.os.SystemClock.uptimeMillis())
    return runCatching { context.startActivity(intent) }.isSuccess
}

const val ACTION_KICK_SYNC = "com.copy.account.action.SYNC_KICK"

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

fun writeSyncPointer(context: Context, backupFolder: String) {
    runCatching {
        val storageRoot = android.os.Environment.getExternalStorageDirectory()
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
        runCatching { File(storageRoot, ExtensionContract.POINTER_FILE_NAME).delete() }
    }
}
