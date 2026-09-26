/**
 * 职责：关于页——应用版本号（VERSION_NAME + VERSION_CODE）、包名与开源仓库入口。
 *       唯一副作用是「开源地址」行点开系统浏览器，不改动任何应用状态。
 *       URL 本身刻意不在界面显示（见该行注释），只作为点击目标存在。
 * 架构位置：AccountApp 的 AppPage.About 分支（由设置中枢进入）；行组件复用 ui/components/Rows.kt。
 * Python 类比：≈ 纯展示的常量表 + 一个外链；版本号是编译期内联的 BuildConfig 常量，release 开 R8 也不受影响。
 */
package com.copy.account.page

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.theme.AccountTheme

/** 开源仓库地址。改动只需动这一处；与 git remote 的 owner/repo 保持一致。 */
private const val PROJECT_URL = "https://github.com/AuriSolCorues/account"

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AppListScreen("关于", onBack) {
        item { SettingsRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
        item { SettingsRow("包名", BuildConfig.APPLICATION_ID) }
        item {
            // 值刻意只写「打开」两字，**不显示 URL**：URL 有 37 字符，塞进设置行的值位会换行成
            // 一长条竖排文字（真发生过，见 Rows.kt 的 SettingsRow 注释）。要展示完整 URL 得改成
            // 「标题 + 副行」两行布局，不能直接把 URL 塞回 value。
            SettingsRow("开源地址", "打开", onClick = {
                // 刻意不写 intent.resolveActivity() 判断：targetSdk 37 的包可见性过滤会让它恒为
                // null（除非 Manifest 补 <queries>），照着写「更稳妥」的代码反而静默失效。
                // startActivity 本身不受可见性过滤影响；runCatching 兜住没装浏览器的设备。
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
            })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        AboutScreen(onBack = {})
    }
}
