package com.copy.account.page

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.BuildConfig
import com.copy.account.R
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.PrimaryButton
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.AccountTheme
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
        Text("account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (firstUse) stringResource(R.string.unlock_first_use) else stringResource(R.string.unlock_enter_password), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(42.dp))
        if (showPassword) PasswordField(stringResource(R.string.unlock_password_label), password, { password = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        if (firstUse) {
            PasswordField(stringResource(R.string.unlock_confirm_label), confirmation, { confirmation = it }, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        val errorMsgMismatch = stringResource(R.string.unlock_error_mismatch)
        val errorMsgFailed = stringResource(R.string.unlock_error_failed)
        if (showPassword) PrimaryButton(
            text = if (unlocking) stringResource(R.string.unlock_button_busy) else if (firstUse) stringResource(R.string.unlock_button_setup) else stringResource(R.string.unlock_button_unlock),
            enabled = isMasterPasswordValid(password) && !unlocking,
            onClick = {
                when {
                    !isMasterPasswordValid(password) -> error = MASTER_PASSWORD_RULE_HINT
                    firstUse && password != confirmation -> error = errorMsgMismatch
                    else -> scope.launch {
                        unlocking = true
                        error = ""
                        val ok = onUnlock(password)
                        unlocking = false
                        if (!ok) error = errorMsgFailed
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        if (!firstUse && biometricEnabled) TextActionButton(stringResource(R.string.unlock_biometric_action), onBiometricUnlock)
        if (!firstUse && !showPassword) TextActionButton(stringResource(R.string.unlock_use_password_action), onClick = { showPassword = true })
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        UnlockScreen(
            firstUse = false,
            biometricEnabled = true,
            resetKey = 0,
            onBiometricUnlock = {},
            onUnlock = { true }
        )
    }
}
