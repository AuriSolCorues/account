package com.copy.account.sync.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copy.account.sync.config.WebDavPreset
import com.copy.account.sync.config.WEBDAV_PRESETS
import com.copy.account.sync.navigation.SyncPage
import com.copy.account.sync.runtime.SYNC_FEEDBACK_MILLIS
import com.copy.account.sync.runtime.SyncSession
import com.copy.account.sync.data.MODE_TEST_CONNECTION
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.StoredPasswordTextField
import com.copy.account.ui.components.TextActionButton
import kotlinx.coroutines.delay

@Composable
internal fun SyncConnectionScreen(session: SyncSession, onOpenPage: (SyncPage) -> Unit) {
    val configState = session.configState
    var selectedPreset by remember { mutableStateOf<WebDavPreset?>(null) }

    LaunchedEffect(configState.message) {
        if (configState.message.isNotBlank()) {
            delay(SYNC_FEEDBACK_MILLIS)
            configState.clearMessage()
        }
    }

    AppListScreen("网盘连接配置", onBack = { onOpenPage(SyncPage.Home) }) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    WEBDAV_PRESETS.forEach { preset ->
                        TextButton(onClick = {
                            selectedPreset = preset
                            if (preset.defaultUrl.isNotBlank()) configState.url = preset.defaultUrl
                        }) {
                            Text(
                                preset.name,
                                color = if (selectedPreset?.name == preset.name) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = configState.url,
                    onValueChange = { configState.url = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = configState.user,
                    onValueChange = { configState.user = it },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                StoredPasswordTextField(
                    value = configState.password,
                    onValueChange = { configState.password = it },
                    hasStored = configState.hasStoredPassword,
                    label = "应用密码",
                    hint = selectedPreset?.passwordHint ?: "",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = { session.runSync(MODE_TEST_CONNECTION, rangeBackup = false) },
                        enabled = !session.busy && configState.url.isNotBlank() && configState.user.isNotBlank() &&
                                (configState.password.isNotBlank() || configState.hasStoredPassword)
                    ) {
                        Text(if (session.busy && session.busyKind == "test") "测试中…" else "测试连接")
                    }
                    TextButton(onClick = { configState.save() }, enabled = configState.configured) {
                        Text("保存")
                    }
                }
                if (session.busy && session.busyKind == "test") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (configState.message.isNotBlank()) {
                    Text(
                        configState.message,
                        color = if (configState.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
