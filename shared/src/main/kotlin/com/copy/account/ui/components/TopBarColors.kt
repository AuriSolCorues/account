package com.copy.account.ui.components

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.copy.account.ui.theme.LocalAccountThemePalette

@Composable
internal fun accountTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = LocalAccountThemePalette.current.topBar,
    titleContentColor = LocalAccountThemePalette.current.topBarText,
    navigationIconContentColor = LocalAccountThemePalette.current.topBarText,
    actionIconContentColor = LocalAccountThemePalette.current.topBarText
)
