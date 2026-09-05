package com.copy.account.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme

/** 文字按钮基座。 */
@Composable
internal fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color = Color.Unspecified,
    textStyle: TextStyle? = null
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (textStyle == null) Text(text, color = textColor) else Text(text, color = textColor, style = textStyle)
    }
}

/** 实心主按钮（解锁等主 CTA）；文字/危险按钮见 TextActionButton/DangerButton。 */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
}

/** 无显隐的密码输入（解锁/备份/改密）；需要显隐/掩码编辑的用 FieldTextBox(hidden=true)。 */
@Composable
internal fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = modifier
    )
}

/** 文本输入基座；隐藏型字段仅追加显隐尾按钮，不提供清空动作。 */
@Composable
internal fun FieldTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    hidden: Boolean = false,
    mask: Char = '•',
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    var revealed by remember(value, hidden) { mutableStateOf(false) }
    val visualTransformation = if (hidden && !revealed) PasswordVisualTransformation(mask = mask) else VisualTransformation.None
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle.copy(color = textColor),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        modifier = modifier.height(48.dp).onFocusChanged { if (it.isFocused) onFocused() },
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                trailingIcon = if (hidden) {
                    {
                        TextActionButton(
                            text = if (revealed) "隐藏" else "显示",
                            onClick = { revealed = !revealed },
                            textColor = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null,
                contentPadding = OutlinedTextFieldDefaults.contentPadding(
                    start = 10.dp,
                    top = 2.dp,
                    end = 10.dp,
                    bottom = 2.dp
                )
            )
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun TextActionButtonPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        TextActionButton("文字操作", onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun FieldTextBoxPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        FieldTextBox("hunter2", onValueChange = {}, hidden = true)
    }
}
