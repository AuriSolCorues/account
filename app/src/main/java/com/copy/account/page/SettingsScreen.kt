/**
 * 职责：设置中枢——只列四个分组入口（安全设置 / 主题与语言 / 备份与数据 / 关于），自身不持有任何
 *       设置项与弹窗；各项的读写与弹窗都在对应二级页。
 * 架构位置：AccountApp 的 AppPage.Settings 分支；行组件来自 ui/components/Rows.kt。
 * Python 类比：≈ 路由表/目录页——本页 ≈ 索引，各分组 ≈ 被它指向的子函数。
 */
package com.copy.account.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.copy.account.BuildConfig
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.theme.AccountTheme

@Composable
internal fun SettingsScreen(
    onOpenSecurity: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit
) {
    AppListScreen("设置", onBack) {
        item { SettingsRow("安全设置", "打开", onOpenSecurity) }
        item { SettingsRow("主题与语言", "打开", onOpenAppearance) }
        item { SettingsRow("备份与数据", "打开", onOpenBackup) }
        // 版本号直接露在中枢上，不用进「关于」页也知道自己是哪一版。
        item { SettingsRow("关于", BuildConfig.VERSION_NAME, onOpenAbout) }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SettingsScreen(
            onOpenSecurity = {},
            onOpenAppearance = {},
            onOpenBackup = {},
            onOpenAbout = {},
            onBack = {}
        )
    }
}
