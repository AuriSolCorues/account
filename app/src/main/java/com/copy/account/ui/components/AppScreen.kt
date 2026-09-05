/**
 * 职责：二级页通用骨架——Scaffold + 主题色顶栏 + 可选「‹ 返回」+ 右部 actions。
 * 架构位置：Settings/Groups/Backup/Detail/Edit 页统一套用；Home 顶栏非标、不用它。
 * Python 类比：≈ 一个页面基类/装饰器——每个二级页只写 content，窗口骨架这里包办。
 */
package com.copy.account.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.copy.account.ui.theme.LocalAccountThemePalette

/** 二级页骨架：Scaffold + 主题色顶栏 + 可选「‹ 返回」+ 右部 actions。Home（无返回、顶栏非标）不适用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    // Scaffold 是页面脚手架：自动摆顶栏/内容区，content 收到的 PaddingValues 即
    // 「扣除顶栏后的可用区域边距」；不做内边距内容会被顶栏盖住。
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(
            colors = accountTopBarColors(),
            title = { Text(title, color = LocalAccountThemePalette.current.topBarText) },
            navigationIcon = {
                if (onBack != null) {
                    TextActionButton("‹ 返回", onBack, textColor = LocalAccountThemePalette.current.topBarText)
                }
            },
            actions = actions
        )
    }, content = content)
}
