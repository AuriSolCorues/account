/**
 * 职责：内置配色常量表——绿（主）与蓝（备用）两套色板的具体色值。
 * 架构位置：由 ui/theme/Theme.kt 的 resolveTheme 与默认 CompositionLocal 引用。
 * Python 类比：顶层 val ≈ 模块级常量（模块加载即单例）；Color(0xAARRGGBB) 只是十六进制色值的包装。
 */
package com.copy.account.ui.theme

import androidx.compose.ui.graphics.Color

// JPG 参考图的主色：深绿顶栏、清晰的绿色状态文字。
val AccountGreen = Color(0xFF35D28C)
val AccountGreenDark = Color(0xFF006B46)
val AccountGreenContainer = Color(0xFF1C3329)
val AccountMint = Color(0xFF9AE8BF)
val AccountBackground = Color(0xFF151817)
val AccountSurface = Color(0xFF202322)
val AccountSurfaceVariant = Color(0xFF171A19)
val AccountLightGreen = Color(0xFF00965F)
val AccountLightContainer = Color(0xFFD7F1E4)

// 备用蓝色色板，便于后续增加冷色调主题。
val AccountBlue = Color(0xFF8EB8FF)
val AccountBlueContainer = Color(0xFF294A73)
val AccountBlueBackground = Color(0xFF0F141C)
val AccountBlueSurface = Color(0xFF19212D)
val AccountBlueSurfaceVariant = Color(0xFF202A38)
val AccountLightBlue = Color(0xFF2D6CDF)
val AccountLightBlueContainer = Color(0xFFDCE8FF)
