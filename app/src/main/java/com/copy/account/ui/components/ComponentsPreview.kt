package com.copy.account.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.data.model.initialAccounts
import com.copy.account.ui.theme.AccountTheme

/** 组件外观集中预览：打开本文件的 Design/Split 视图即可查看全部 UI 组件。 */
@Preview(showBackground = true)
@Composable
private fun CommonComponentsPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            SettingsHeader("设置行")
            SettingsRow("自动锁定", "5 分钟")
            SettingsSwitchRow("允许截图", true, {})
            SettingsHeader("编辑与复制行")
            AccountFieldItem("自定义字段", "hunter2", hidden = true, onValueChange = {}, onDelete = {})
            SensitiveValueRow("密码", "hunter2", masked = true, sensitive = true, clearAfterSeconds = 30)
            SettingsHeader("面板行")
            ActionSheetRow("普通操作") {}
            ActionSheetRow("删除账号", color = MaterialTheme.colorScheme.error) {}
            ActionSheetRow("取消", muted = true) {}
            GeneratorToggle("大写字母（A-Z）", true, {})
            SettingsHeader("空态")
            EmptyState("暂无账号")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FormComponentsPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            SettingsHeader("表单行")
            SwitchRow("两步验证", true, {})
            SwitchRow("两步验证（带副标题）", false, {}, subtitle = "已配置 · 自动显示在动态密码分组")
            PasswordField("hunter2", {}, label = { Text("密码") })
            PasswordField("hunter2", {}, label = { Text("自定义掩码") }, mask = '*')
            SettingsHeader("卡片与危险按钮")
            SurfaceCard {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("surface 卡片", modifier = Modifier.weight(1f))
                    Text("值", color = MaterialTheme.colorScheme.primary)
                }
            }
            DangerButton("删除账号", onClick = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountPreviewSheetPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        AccountPreviewSheet(account = initialAccounts[1], clipboardClearSeconds = 30, onDismiss = {}, onEdit = {}, onDetail = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountActionSheetPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        AccountActionSheet(account = initialAccounts[1], onDismiss = {}, onEdit = {}, onDelete = {}, onTemplateNew = {}, onCopyAll = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun RandomPasswordGeneratorSheetPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        RandomPasswordGeneratorSheet(onDismiss = {}, onFill = {})
    }
}
