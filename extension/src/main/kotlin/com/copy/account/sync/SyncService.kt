package com.copy.account.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.copy.account.sync.config.KeystoreStringVault
import com.copy.account.sync.config.WebDavConfigState
import com.copy.account.sync.data.BridgeClient
import com.copy.account.sync.data.DEFAULT_REMOTE_DIR
import com.copy.account.sync.data.EXTRA_MODE
import com.copy.account.sync.data.EXTRA_RANGE_BACKUP
import com.copy.account.sync.data.RunOutcome
import com.copy.account.sync.data.SyncLog
import com.copy.account.sync.data.SyncMode
import com.copy.account.sync.data.SyncRange
import com.copy.account.sync.data.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SyncService : Service() {

    companion object {
        @Volatile private var kickBusy = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val params = intent?.extras
        if (params == null || !params.containsKey(EXTRA_MODE)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (kickBusy) {
            stopSelf()
            return START_NOT_STICKY
        }
        kickBusy = true
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = WebDavConfigState(appContext, CoroutineScope(Dispatchers.IO), KeystoreStringVault())
                runBlocking { config.loadInitial() }
                val password = config.resolvePassword()
                when {
                    config.url.isBlank() || config.user.isBlank() || password.isBlank() -> {
                        SyncLog.sessionEnd(appContext, "网盘连接未配置（请先完成连接配置）", failed = true)
                        config.recordSyncResult("失败——网盘连接未配置")
                    }
                    else -> runCatching {
                        SyncWorker(
                            context = appContext,
                            bridge = BridgeClient(appContext),
                            url = config.url,
                            user = config.user,
                            password = password,
                            remoteDir = DEFAULT_REMOTE_DIR,
                            range = if (params.getBoolean(EXTRA_RANGE_BACKUP, true)) SyncRange.Backup else SyncRange.OtherFiles,
                            modeName = params.getString(EXTRA_MODE) ?: SyncMode.Upload.name,
                            planOnly = false,
                            keepLatest = config.keepLatest,
                            progressHook = { _, _, _, _ -> }                         ).run()
                    }.onSuccess { outcome ->
                        (outcome as? RunOutcome.Summary)?.let {
                            val s = it.summary.summary
                            config.recordSyncResult(if (s.startsWith("失败于")) "失败——$s" else s)
                        }
                    }.onFailure {
                        SyncLog.error(appContext, "跳板同步：${it.message ?: it.javaClass.simpleName}")
                        config.recordSyncResult("失败——${it.message ?: it.javaClass.simpleName}")
                    }
                }
            } finally {
                kickBusy = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
}
