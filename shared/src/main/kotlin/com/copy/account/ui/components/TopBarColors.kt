/**
 * 职责：通用顶栏取色（零业务，纯主题）——AppScreen 与本体 HomeScreen 等页面共用。
 * 架构位置：shared-ui（自本体 UiCommon.kt 迁入，包名不变，import 零改）。
 * Python 类比：≈ 一个读全局样式的小工厂函数。
 */
package com.copy.account.ui.components

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.copy.account.ui.theme.LocalAccountThemePalette

@Composable
internal fun accountTopBarColors() = TopAppBarDefaults.topAppBarColors(
    // 每个页面使用完整的一套色板，切换主题时不混用明暗不同的顶栏和内容区。
    containerColor = LocalAccountThemePalette.current.topBar,
    titleContentColor = LocalAccountThemePalette.current.topBarText,
    navigationIconContentColor = LocalAccountThemePalette.current.topBarText,
    actionIconContentColor = LocalAccountThemePalette.current.topBarText
)
