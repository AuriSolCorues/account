package com.copy.account.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.copy.account.data.config.stripJsonComments
import com.copy.account.data.model.SavedTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AccountThemePalette(
    val topBar: Color,
    val topBarText: Color,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val primaryText: Color,
    val selectedBackground: Color,
    val text: Color,
    val textMuted: Color,
    val divider: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val icon: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val disabled: Color,
    val overlay: Color,
    val popupSurface: Color? = null,
    val popupItemSurface: Color? = null
)

@Serializable
data class ThemeJsonColors(
    val topBar: String = "#006B46",
    val topBarText: String = "#FFFFFF",
    val background: String = "#151817",
    val surface: String = "#202322",
    val surfaceAlt: String = "#171A19",
    val popupSurface: String? = null,
    val popupItemSurface: String? = null,
    val primary: String = "#35D28C",
    val primaryText: String = "#082016",
    val selectedBackground: String = "#1C3329",
    val text: String = "#F0F4F1",
    val textMuted: String = "#A7B2AC",
    val divider: String = "#303733",
    val inputBackground: String = "#272B29",
    val inputBorder: String = "#4A554E",
    val icon: String = "#DCE8E0",
    val danger: String = "#FF7070",
    val warning: String = "#E2B34E",
    val success: String = "#35D28C",
    val disabled: String = "#66726B",
    val overlay: String = "#00000099"
)

@Serializable
data class ThemeJsonDefinition(
    val version: Int = 1,
    val name: String = "自定义主题",
    val defaultMode: String = "dark",
    val colors: ThemeJsonColors = ThemeJsonColors()
)

private val GreenDarkPalette = AccountThemePalette(
    topBar = AccountGreenDark, topBarText = Color.White, background = AccountBackground,
    surface = AccountSurface, surfaceAlt = AccountSurfaceVariant, primary = AccountGreen,
    primaryText = Color(0xFF082016), selectedBackground = AccountGreenContainer,
    text = Color(0xFFF0F4F1), textMuted = Color(0xFFA7B2AC), divider = Color(0xFF303733),
    inputBackground = Color(0xFF272B29), inputBorder = Color(0xFF4A554E),
    icon = Color(0xFFDCE8E0), danger = Color(0xFFFF7070), warning = Color(0xFFE2B34E),
    success = AccountGreen, disabled = Color(0xFF66726B), overlay = Color(0x99000000)
)

val LocalAccountThemePalette = staticCompositionLocalOf { GreenDarkPalette }

private val themeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

private fun parseHex(value: String): Color? = runCatching {
    val hex = value.trim().removePrefix("#")
    require(hex.length == 6 || hex.length == 8)
    require(hex.all { it.digitToIntOrNull(16) != null })
    Color((if (hex.length == 6) 0xFF000000L else 0L) or hex.toLong(16))
}.getOrNull()

private fun ThemeJsonColors.toPalette(): AccountThemePalette? {
    val values = listOf(topBar, topBarText, background, surface, surfaceAlt, primary, primaryText, selectedBackground, text, textMuted, divider, inputBackground, inputBorder, icon, danger, warning, success, disabled, overlay)
    val colors = values.map { parseHex(it) }
    if (colors.any { it == null }) return null
    val popupSurfaceColor = popupSurface?.let(::parseHex)
    val popupItemSurfaceColor = popupItemSurface?.let(::parseHex)
    if (popupSurface != null && popupSurfaceColor == null) return null
    if (popupItemSurface != null && popupItemSurfaceColor == null) return null
    val c = colors.requireNoNulls()
    return AccountThemePalette(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16], c[17], c[18], popupSurfaceColor, popupItemSurfaceColor)
}

fun parseThemeJson(source: String): ThemeJsonDefinition? = runCatching {
    val definition = themeJson.decodeFromString<ThemeJsonDefinition>(stripJsonComments(source))
    require(definition.version == 1)
    require(definition.defaultMode in setOf("dark", "light", "system"))
    require(definition.colors.toPalette() != null)
    definition
}.getOrNull()

fun themePaletteFromJson(source: String): AccountThemePalette? = parseThemeJson(source)?.colors?.toPalette()

fun defaultThemePresets(): List<SavedTheme> = listOf(
    SavedTheme("default-dark", "默认深色", """{
      "version": 1,
      "name": "默认深色",
      "defaultMode": "dark",
      "colors": {
        "topBar": "#006B46",
        "topBarText": "#FFFFFF",
        "background": "#151817",
        "surface": "#202322",
        "surfaceAlt": "#171A19",
        "popupSurface": null,
        "popupItemSurface": null,
        "primary": "#35D28C",
        "primaryText": "#082016",
        "selectedBackground": "#1C3329",
        "text": "#F0F4F1",
        "textMuted": "#A7B2AC",
        "divider": "#303733",
        "inputBackground": "#272B29",
        "inputBorder": "#4A554E",
        "icon": "#DCE8E0",
        "danger": "#FF7070",
        "warning": "#E2B34E",
        "success": "#35D28C",
        "disabled": "#66726B",
        "overlay": "#00000099"
      }
    }"""),
    SavedTheme("default-light", "默认浅色", """{
      "version": 1,
      "name": "默认浅色",
      "defaultMode": "light",
      "colors": {
        "topBar": "#00965F",
        "topBarText": "#FFFFFF",
        "background": "#F4F5F4",
        "surface": "#FFFFFF",
        "surfaceAlt": "#EDF1EE",
        "popupSurface": null,
        "popupItemSurface": null,
        "primary": "#008A58",
        "primaryText": "#FFFFFF",
        "selectedBackground": "#D7F1E4",
        "text": "#25312B",
        "textMuted": "#68766E",
        "divider": "#DDE4DF",
        "inputBackground": "#F1F4F2",
        "inputBorder": "#B9C7BF",
        "icon": "#426052",
        "danger": "#D94E58",
        "warning": "#A66A00",
        "success": "#008A58",
        "disabled": "#8A958E",
        "overlay": "#00000055"
      }
    }"""),
    SavedTheme("example-blue", "示例蓝色", """{
      "version": 1,
      "name": "示例蓝色",
      "defaultMode": "dark",
      "colors": {
        "topBar": "#245AA9",
        "topBarText": "#FFFFFF",
        "background": "#101722",
        "surface": "#19212D",
        "surfaceAlt": "#202A38",
        "popupSurface": null,
        "popupItemSurface": null,
        "primary": "#69A1FF",
        "primaryText": "#10233D",
        "selectedBackground": "#294A73",
        "text": "#F0F4FC",
        "textMuted": "#AAB8CC",
        "divider": "#344254",
        "inputBackground": "#202A38",
        "inputBorder": "#53657C",
        "icon": "#D5E5FF",
        "danger": "#FF7070",
        "warning": "#E2B34E",
        "success": "#69A1FF",
        "disabled": "#74849A",
        "overlay": "#00000099"
      }
    }""")
)

private fun AccountThemePalette.toScheme(darkTheme: Boolean): ColorScheme {
    val popup = popupSurface ?: surface
    val popupItem = popupItemSurface ?: surfaceAlt
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = primaryText, primaryContainer = selectedBackground,
        onPrimaryContainer = text, secondary = success, tertiary = warning,
        background = background, surface = surface, surfaceVariant = surfaceAlt,
        onBackground = text, onSurface = text, onSurfaceVariant = textMuted,
        error = danger, onError = Color.White, outline = inputBorder, outlineVariant = divider,
        surfaceContainerLowest = surface, surfaceContainerLow = popup, surfaceContainer = popupItem,
        surfaceContainerHigh = popup, surfaceContainerHighest = popup
    )
}

data class ResolvedTheme(
    val palette: AccountThemePalette,
    val colorScheme: androidx.compose.material3.ColorScheme
)

fun resolveTheme(darkTheme: Boolean, accentTheme: String, customThemeJson: String): ResolvedTheme {
    val customPalette = themePaletteFromJson(customThemeJson)
    val palette = customPalette ?: when {
        accentTheme == "blue" && darkTheme -> AccountThemePalette(
            AccountBlueContainer, Color.White, AccountBlueBackground, AccountBlueSurface,
            AccountBlueSurfaceVariant, AccountBlue, Color(0xFF10233D), AccountBlueContainer,
            Color(0xFFF0F4FC), Color(0xFFAAB8CC), Color(0xFF344254), AccountBlueSurfaceVariant,
            Color(0xFF53657C), Color(0xFFD5E5FF), Color(0xFFFF7070), Color(0xFFE2B34E),
            AccountBlue, Color(0xFF74849A), Color(0x99000000)
        )
        accentTheme == "blue" -> AccountThemePalette(
            AccountLightBlue, Color.White, Color(0xFFF5F7FB), Color.White,
            Color(0xFFEEF2F8), AccountLightBlue, Color.White, AccountLightBlueContainer,
            Color(0xFF1C2738), Color(0xFF566579), Color(0xFFD8E1ED), Color(0xFFEEF2F8),
            Color(0xFF9AAACC), Color(0xFF34548A), Color(0xFFD94E58), Color(0xFFA66A00),
            AccountLightBlue, Color(0xFF8392A8), Color(0x55000000)
        )
        darkTheme -> GreenDarkPalette
        else -> AccountThemePalette(
            AccountLightGreen, Color.White, Color(0xFFF4F5F4), Color.White,
            Color(0xFFF8FAF8), AccountLightGreen, Color.White, AccountLightContainer,
            Color(0xFF193327), Color(0xFF52665C), Color(0xFFDDE4DF), Color(0xFFF1F4F2),
            Color(0xFFB9C7BF), Color(0xFF426052), Color(0xFFD94E58), Color(0xFFA66A00),
            AccountLightGreen, Color(0xFF8A958E), Color(0x55000000)
        )
    }
    return ResolvedTheme(palette, palette.toScheme(darkTheme))
}

@Composable
fun AccountTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentTheme: String = "green",
    customThemeJson: String = "",
    content: @Composable () -> Unit
) {
    val resolved = resolveTheme(darkTheme, accentTheme, customThemeJson)

    MaterialTheme(
        colorScheme = resolved.colorScheme,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(LocalAccountThemePalette provides resolved.palette) { content() }
        }
    )
}

fun resolveDarkTheme(themeMode: String, isSystemDark: Boolean): Boolean = when (themeMode) {
    "light" -> false
    "system" -> isSystemDark
    else -> true
}
