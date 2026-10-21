/**
 * 职责：成型密码输入件——打字即变点（官方 PasswordVisualTransformation），无显示按钮；
 *       可选「已存占位」：输入为空且已存密码时显示固定圆点串（与焦点无关，placeholder 做不到）。
 *       空输入→圆点占位走折叠 OffsetMapping（原文空、显示变长，光标落点全折回 0，防越界崩溃）；
 *       有输入→官方 PasswordVisualTransformation（系统成型实现，自带头像圆点样式）。
 * 架构位置：:app 源集（shared/src/main/kotlin 挂 app sourceSets）；纯 UI 件，无业务依赖。
 * Python 类比：≈ 一个带掩码的输入框组件——默认掩码打字，空值且有存档时显示假面占位。
 */
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

/** 空输入圆点占位的偏移映射：原文长度 0，光标任何位置都合法地折回 0（Identity 会越界崩溃）。 */
private val DotPlaceholderMapping = object : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = 0
    override fun transformedToOriginal(offset: Int): Int = 0
}

/** 圆点占位的视觉变换：空文本显示固定圆点串，否则原样透传。 */
private fun dotPlaceholderTransformation(hasStored: Boolean) = VisualTransformation { text ->
    if (text.isEmpty() && hasStored) {
        TransformedText(AnnotatedString("••••••"), DotPlaceholderMapping)
    } else {
        TransformedText(text, OffsetMapping.Identity)
    }
}

/**
 * 密码输入框（无显示按钮）：打字即变点；hasStored 且输入为空时显示圆点占位（保存后立即可见）。
 *
 * @param hasStored 已存过密码（占位显示条件之一；值本身不参与表单）
 * @param hint      未存任何密码时的引导文案（走 supportingText）
 */
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
        // 打字掩码 + 无联想键盘（密码键盘样式）；无 trailingIcon（无显示按钮，用户定案）
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier
    )
}
