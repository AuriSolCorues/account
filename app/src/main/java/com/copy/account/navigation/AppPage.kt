package com.copy.account.navigation

internal sealed interface AppPage {
    data object Unlock : AppPage
    data object Home : AppPage
    data object Groups : AppPage
    data object Settings : AppPage
    data object SecuritySettings : AppPage
    data object AppearanceSettings : AppPage
    data object About : AppPage
    data object Logs : AppPage
    data object BackupFiles : AppPage
    data class Detail(val accountId: String) : AppPage
    data class Edit(val accountId: String?) : AppPage
}
