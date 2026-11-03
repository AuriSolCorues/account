package com.copy.account.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val DotPlaceholderMapping = object : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = 0
    override fun transformedToOriginal(offset: Int): Int = 0
}

private fun dotPlaceholderTransformation(hasStored: Boolean) = VisualTransformation { text ->
    if (text.isEmpty() && hasStored) {
        TransformedText(AnnotatedString("••••••"), DotPlaceholderMapping)
    } else {
        TransformedText(text, OffsetMapping.Identity)
    }
}

@Composable
fun StoredPasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hasStored: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    hint: String = ""
) {
    val typing = PasswordVisualTransformation()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (value.isEmpty() && hasStored) dotPlaceholderTransformation(hasStored)
        else typing,
        supportingText = {
            Text(
                when {
                    value.isNotBlank() -> ""
                    hasStored -> "已保存；留空则不修改"
                    else -> hint
                }
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier
    )
}
