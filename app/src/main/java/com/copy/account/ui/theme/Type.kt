/**
 * 职责：Material3 排版（Typography）槽位——当前全用默认值，仅留日后整体替换自定义字体的入口。
 * 架构位置：AccountTheme 组装时传入 MaterialTheme。
 * Python 类比：≈ 一组命名字体样式常量（titleLarge/bodyMedium…），类似 CSS 的 font 定义集。
 */
package com.copy.account.ui.theme

import androidx.compose.material3.Typography

// 各槽位与 Material3 默认排版一致；保留入口以便日后整体替换自定义字体。
val Typography = Typography()
