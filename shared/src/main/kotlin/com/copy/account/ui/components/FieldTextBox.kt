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
import com.copy.account.ui.theme.AccountTheme

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

@Composable
internal fun TopBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, color = com.copy.account.ui.theme.LocalAccountThemePalette.current.topBarText)
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
}

@Composable
internal fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showPasswordToggle: Boolean = false
) {
    var revealed by remember(showPasswordToggle) { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (showPasswordToggle && revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = if (showPasswordToggle) {
            { TextActionButton(if (revealed) "隐藏" else "显示", onClick = { revealed = !revealed }) }
        } else null,
        modifier = modifier
    )
}

@Composable
internal fun FieldTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    hidden: Boolean = false,
    mask: Char = '•',
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    var revealed by remember(hidden) { mutableStateOf(false) }
    val maskedTransformation = remember(mask) { PasswordVisualTransformation(mask = mask) }
    val visualTransformation = if (hidden && !revealed) maskedTransformation else VisualTransformation.None
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
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
    AccountTheme(darkTheme = false) {
        TextActionButton("文字操作", onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun FieldTextBoxPreview() {
    AccountTheme(darkTheme = false) {
        FieldTextBox("hunter2", onValueChange = {}, hidden = true)
    }
}
