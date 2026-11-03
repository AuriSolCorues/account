package com.copy.account.sync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class SyncKickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        if (extras != null && extras.containsKey("sync_mode")) {
            val service = Intent(this, SyncService::class.java).putExtras(extras)
            runCatching { startService(service) }
        }
        finish()
    }
}
