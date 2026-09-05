package com.copy.account.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.security.copyToClipboard
import com.copy.account.ui.theme.AccountTheme
import java.security.SecureRandom

/** 复用单一实例：滑块每一步都会重新生成，免逐次播种。 */
private val generatorRandom = SecureRandom()

/** 按勾选的字符类生成随机密码；每类至少出现一次，其余从合并字符池随机取。 */
internal fun generatePassword(length: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean): String {
    val pools = buildList {
        if (upper) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        if (lower) add("abcdefghijklmnopqrstuvwxyz")
        if (digits) add("0123456789")
        if (symbols) add("\$%&^*()[].@#_!")
    }
    if (pools.isEmpty() || length <= 0) return ""
    val random = generatorRandom
    val all = pools.joinToString("")
    val result = CharArray(length)
    pools.forEachIndexed { i, pool -> if (i < length) result[i] = pool[random.nextInt(pool.length)] }
    for (i in pools.size until length) result[i] = all[random.nextInt(all.length)]
    for (i in result.indices.reversed()) {
        val j = random.nextInt(i + 1)
        val tmp = result[i]; result[i] = result[j]; result[j] = tmp
    }
    return String(result)
}

/** 随机密码生成面板：长度滑块与字符类开关；主钮「复制并填入」= 写入剪贴板 + 填入目标字段。 */
@Composable
internal fun RandomPasswordGeneratorSheet(onDismiss: () -> Unit, onFill: (String) -> Unit, clipboardClearSeconds: Int = 30) {
    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RandomPasswordGeneratorItems(onFill, clipboardClearSeconds)
    }
}

/** 面板内容（不含弹层容器），运行时与 IDE 预览共用，避免两份排版漂移。 */
@Composable
private fun RandomPasswordGeneratorItems(onFill: (String) -> Unit, clipboardClearSeconds: Int = 30) {
    var length by remember { mutableIntStateOf(16) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    val generated = remember(length, upper, lower, digits, symbols) { generatePassword(length, upper, lower, digits, symbols) }
    val context = LocalContext.current
    SheetTitleRow("随机密码生成器")
    Text(generated, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
    Text("长度：$length", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(value = length.toFloat(), onValueChange = { length = it.toInt().coerceIn(4, 20) }, valueRange = 4f..20f, steps = 15)
    GeneratorToggle("大写字母（A-Z）", upper) { upper = it }
    GeneratorToggle("小写字母（a-z）", lower) { lower = it }
    GeneratorToggle("数字（0-9）", digits) { digits = it }
    GeneratorToggle("特殊符号（\$%&^*()[].@#_!）", symbols) { symbols = it }
    PrimaryButton(
        "复制并填入",
        onClick = {
            copyToClipboard(context, generated, sensitive = true, clearAfterSeconds = clipboardClearSeconds)
            onFill(generated)
        },
        enabled = generated.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
}

/** 生成器内的开关行（仅面板内部使用）。 */
@Composable
private fun GeneratorToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 页面化预览：ModalBottomSheet 在预览窗不弹层，这里按「遮罩 + 底部圆角面板」画成品，
 * Design/Split 视图即可直接查看。运行时仍走 RandomPasswordGeneratorSheet 的真弹层。
 */
@Preview(name = "随机密码生成器面板", widthDp = 411, heightDp = 700, showBackground = true)
@Composable
private fun RandomPasswordGeneratorSheetDockPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SheetPagePreview(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RandomPasswordGeneratorItems(onFill = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneratorTogglePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        GeneratorToggle("大写字母（A-Z）", true, {})
    }
}
