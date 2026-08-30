package com.copy.account.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copy.account.core.security.copyToClipboard
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.delay

@Composable
internal fun rememberClock(): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    return nowMillis
}

@Composable
internal fun accountTopBarColors() = TopAppBarDefaults.topAppBarColors(
    // 每个页面使用完整的一套色板，切换主题时不混用明暗不同的顶栏和内容区。
    containerColor = LocalAccountThemePalette.current.topBar,
    titleContentColor = LocalAccountThemePalette.current.topBarText,
    navigationIconContentColor = LocalAccountThemePalette.current.topBarText,
    actionIconContentColor = LocalAccountThemePalette.current.topBarText
)

@Composable
internal fun SensitiveValueRow(label: String, value: String, masked: Boolean = false, sensitive: Boolean = masked, clearAfterSeconds: Int = 30) {
    val context = LocalContext.current
    var revealed by remember(value, masked) { mutableStateOf(!masked) }
    val displayed = if (masked && !revealed) "••••••••" else value
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(
            displayed,
            modifier = Modifier.weight(1f).clickable {
                if (masked && !revealed) revealed = true else copyToClipboard(context, value, sensitive, clearAfterSeconds)
            },
            fontWeight = if (masked && !revealed) FontWeight.Normal else FontWeight.Medium
        )
        TextButton(onClick = { copyToClipboard(context, value, sensitive, clearAfterSeconds) }) { Text("复制") }
    }
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
