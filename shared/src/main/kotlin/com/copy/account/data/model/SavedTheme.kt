/** 职责：自定义主题的数据模型（纯 data class，零依赖）——本体主题编辑与 shared-ui 主题引擎共用。
 *  架构位置：随 ui/theme 整包从本体 Models.kt 迁入共享源码集（设计稿 sync-plugin-plan.md §3），包名不变。 */
package com.copy.account.data.model

import kotlinx.serialization.Serializable

/** 自定义主题（id/名称/JSONC 原文）；本体设置存储序列化与主题引擎共用。 */
@Serializable
data class SavedTheme(val id: String, val name: String, val json: String)
