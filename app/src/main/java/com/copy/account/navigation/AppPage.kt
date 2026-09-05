/**
 * 职责：全应用唯一的页面路由定义——7 个页面各是一个分支，没有别的导航机制。
 * 架构位置：AccountApp 持有单个 var page: AppPage?，when(page) 渲染对应 page/ 屏幕，
 *           BackHandler 据此把二级页的返回键映射回上级页。
 * Python 类比：sealed interface ≈「带数据的 Enum」——成员既可以是 data object（无字段，
 *           ≈ Enum 成员）也可以是 data class（带参数，≈ 每个成员各挂一个 dataclass）；
 *           外部无法新增分支，when 穷尽匹配后编译器强制覆盖所有成员（≈ match + mypy 穷尽检查）。
 */
package com.copy.account.navigation

/** 页面路由定义；仅由 AccountApp 组装并驱动。 */
internal sealed interface AppPage {
    // data object：无字段单例成员；下面 Detail/Edit 则带 accountId 参数。
    data object Unlock : AppPage
    data object Home : AppPage
    data object Groups : AppPage
    data object Settings : AppPage
    data object BackupFiles : AppPage
    data class Detail(val accountId: String) : AppPage
    data class Edit(val accountId: String?) : AppPage
}
