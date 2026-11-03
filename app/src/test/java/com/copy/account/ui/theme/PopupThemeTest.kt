package com.copy.account.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupThemeTest {
    @Test
    fun builtInTheme_popupRolesInheritParentSurfaces() {
        val scheme = resolveTheme(darkTheme = true, accentTheme = "green", customThemeJson = "").colorScheme

        assertEquals(scheme.surface, scheme.surfaceContainerLow)
        assertEquals(scheme.surface, scheme.surfaceContainerHigh)
        assertEquals(scheme.surfaceVariant, scheme.surfaceContainer)
    }

    @Test
    fun themeJson_popupColorsOverrideParentSurfaces() {
        val palette = themePaletteFromJson(
            """{"version":1,"name":"test","defaultMode":"dark","colors":{"popupSurface":"#294A73","popupItemSurface":"#202A38"}}"""
        ) ?: error("主题 JSON 应解析成功")

        assertEquals(androidx.compose.ui.graphics.Color(0xFF294A73), palette.popupSurface)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF202A38), palette.popupItemSurface)
    }
}
