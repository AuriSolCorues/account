package com.copy.account.feature.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.copy.account.core.crypto.isMasterPasswordValid
import kotlinx.coroutines.launch

@Composable
internal fun UnlockScreen(
    firstUse: Boolean,
    biometricEnabled: Boolean,
    resetKey: Int,
    onBiometricUnlock: () -> Unit,
    onUnlock: suspend (String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    var password by remember(resetKey) { mutableStateOf("") }
    var confirmation by remember(resetKey) { mutableStateOf("") }
    var error by remember(resetKey) { mutableStateOf("") }
    var showPassword by remember(resetKey, biometricEnabled) { mutableStateOf(firstUse || !biometricEnabled) }
    var unlocking by remember(resetKey) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("账号本子", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (firstUse) "首次使用 · 设置主密码" else "输入主密码以解锁", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(42.dp))
        if (showPassword) OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("主密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        if (firstUse) {
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次输入主密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        if (showPassword) androidx.compose.material3.Button(onClick = {
            when {
                !isMasterPasswordValid(password) -> error = "主密码长度需为 4-20 个字符"
                firstUse && password != confirmation -> error = "两次输入的主密码不一致"
                else -> scope.launch {
                    // PBKDF2 在主密码校验里较慢，放到后台执行，避免阻塞输入框和按钮动画。
                    unlocking = true
                    error = ""
                    val ok = onUnlock(password)
                    unlocking = false
                    if (!ok) error = "无法解锁"
                }
            }
        }, enabled = isMasterPasswordValid(password) && !unlocking, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(if (unlocking) "解锁中…" else if (firstUse) "设置并进入" else "解锁")
        }
        if (!firstUse && biometricEnabled) TextButton(onClick = onBiometricUnlock) { Text("◉ 使用指纹 / 面容解锁") }
        if (!firstUse && !showPassword) TextButton(onClick = { showPassword = true }) { Text("使用主密码") }
    }
}
