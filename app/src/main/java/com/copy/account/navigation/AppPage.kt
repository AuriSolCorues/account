package com.copy.account.navigation

/** 页面路由定义；仅由 AccountApp 组装并驱动。 */
internal sealed interface AppPage {
    data object Unlock : AppPage
    data object Home : AppPage
    data object Groups : AppPage
    data object Settings : AppPage
    data object BackupFiles : AppPage
    data class Detail(val accountId: String) : AppPage
    data class Edit(val accountId: String?) : AppPage
}
