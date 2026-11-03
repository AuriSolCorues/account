package com.copy.account.sync.config

internal data class WebDavPreset(
    val name: String,
    val defaultUrl: String,
    val passwordHint: String
)

internal val WEBDAV_PRESETS = listOf(
    WebDavPreset(
        name = "坚果云",
        defaultUrl = "https://dav.jianguoyun.com/dav/",
        passwordHint = "坚果云需使用「应用密码」（网页端 账户信息→安全选项 生成），非登录密码"
    ),
    WebDavPreset(
        name = "自定义",
        defaultUrl = "",
        passwordHint = "输入该网盘的应用密码"
    )
)
