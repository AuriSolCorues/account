package com.copy.account.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copy.account.core.crypto.totpCode
import com.copy.account.data.model.Account
import java.security.SecureRandom

/** 账号底部速览面板：快速复制字段与 2FA，点击空白或下滑关闭。 */
@Composable
internal fun AccountPreviewSheet(account: Account, clipboardClearSeconds: Int, onDismiss: () -> Unit, onEdit: () -> Unit, onDetail: () -> Unit, maskChar: Char = '•') {
    val nowMillis = rememberClock()
    AppBottomSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(account.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDetail) { Text("详情") }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
        Spacer(Modifier.height(12.dp))
        SensitiveValueRow("用户名", account.username, clearAfterSeconds = clipboardClearSeconds, mask = maskChar)
        SensitiveValueRow("密码", account.password, masked = true, sensitive = true, clearAfterSeconds = clipboardClearSeconds, mask = maskChar)
        if (account.hasTotp) SensitiveValueRow("动态密码", totpCode(account, nowMillis), sensitive = true, clearAfterSeconds = clipboardClearSeconds)
        account.customFields.forEach { field -> SensitiveValueRow(field.label, field.value, masked = field.hidden, sensitive = field.hidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
        Spacer(Modifier.height(16.dp))
    }
}

/** 长按账号的操作面板：编辑、删除、模板新建与复制全部内容。 */
@Composable
internal fun AccountActionSheet(
    account: Account,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTemplateNew: () -> Unit,
    onCopyAll: () -> Unit
) {
    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onDismiss(); onEdit() }, modifier = Modifier.weight(1f)) { Text("编辑") }
            DangerButton("删除", onClick = { onDismiss(); onDelete() }, modifier = Modifier.weight(1f))
        }
        ActionSheetRow("作为模板新建账号") { onDismiss(); onTemplateNew() }
        ActionSheetRow("复制账号全部内容") { onDismiss(); onCopyAll() }
        ActionSheetRow("取消", muted = true) { onDismiss() }
        Spacer(Modifier.height(8.dp))
    }
}

/** 按用户勾选的字符类生成随机密码；每类至少出现一次，其余从合并字符池随机取。 */
internal fun generatePassword(length: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean): String {
    val pools = buildList {
        if (upper) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        if (lower) add("abcdefghijklmnopqrstuvwxyz")
        if (digits) add("0123456789")
        if (symbols) add("\$%&^*()[].@#_!")
    }
    if (pools.isEmpty() || length <= 0) return ""
    val random = SecureRandom()
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

/** 随机密码生成面板：长度滑块与字符类开关，复制并填入当前密码框。 */
@Composable
internal fun RandomPasswordGeneratorSheet(onDismiss: () -> Unit, onFill: (String) -> Unit) {
    var length by remember { mutableIntStateOf(16) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    val generated = remember(length, upper, lower, digits, symbols) { generatePassword(length, upper, lower, digits, symbols) }
    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("随机密码生成器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(generated, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
        Text("长度：$length", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = length.toFloat(), onValueChange = { length = it.toInt().coerceIn(4, 20) }, valueRange = 4f..20f, steps = 15)
        GeneratorToggle("大写字母（A-Z）", upper) { upper = it }
        GeneratorToggle("小写字母（a-z）", lower) { lower = it }
        GeneratorToggle("数字（0-9）", digits) { digits = it }
        GeneratorToggle("特殊符号（\$%&^*()[].@#_!）", symbols) { symbols = it }
        Button(onClick = { onFill(generated) }, enabled = generated.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("复制并填入") }
        Spacer(Modifier.height(8.dp))
    }
}

/** 生成器内的开关行。 */
@Composable
internal fun GeneratorToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
