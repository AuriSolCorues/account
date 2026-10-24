/**
 * 职责：本体 ⇆ 扩展的数据桥契约——唯一的契约事实源（single source of truth）。
 *       位于 shared/，经 srcDir 同时编进 :app 与可选扩展模块（零依赖，编译期同源）：
 *       改任何常量，两侧同时编译红，永绝「字符串手抄镜像漂移」。
 *       契约字符串是 ABI：改语义=新增管道/新增字段，旧字段只增不改不删。
 * 架构位置：shared/platform/（平台层，双向中立）；本体 ExtensionBridgeProvider 与
 *           扩展侧消费方（如自建扩展模块的桥客户端）各自 import 本文件。
 * Python 类比：≈ 一个双方 pip 安装同一版本的 protocol 模块——方法名/键名单点定义。
 */
package com.copy.account.platform

/**
 * 桥契约 v1。call() 的 method 与 Bundle 键全部在此。
 * 版本协商：state 管道回 v 字段（当前 1）；客户端不认识的版本走 fail-safe 缺省。
 */
object BridgeContract {
    /** 版本协商字段：state 返回的契约版本号（当前唯一版本 = 1）。 */
    const val VERSION = 1

    const val AUTHORITY = "com.copy.account.extbridge"
    const val METHOD_LIST = "list"
    const val METHOD_STATE = "state"        // 平台状态快照（锁态 + 外观 + 版本），一次往返
    const val METHOD_STAT = "stat"          // extras.rel → isDir
    const val METHOD_SIZE_OF = "sizeOf"     // extras.rel → size
    const val METHOD_DELETE_LOCAL = "deleteLocal" // extras.rel
    const val METHOD_MOVE = "move"          // extras.rel + extras.to
    const val METHOD_TOUCH = "touch"        // extras.rel + extras.mtimeMs
    // 扩展请求本体「解锁后回跳扩展页」：一次性标记（同签名门禁，本体进程内状态，无 extra 伪造面）。
    // 老本体不认识此方法时 call() 折异常 → 扩展降级为跳本体但不回跳（仍可手动进扩展）。
    const val METHOD_SCHEDULE_RESUME = "scheduleResume"
    // 扩展页退后台的会话归还（显式回锁）：走 provider call 同步 Binder——ColorOS 对跨包
    // 广播投递不可信（冻结/延迟，锁定时序整条断），call 通道被数据面长期验证必达。
    const val METHOD_RELEASE_SESSION = "releaseSession"
    // 扩展页触摸心跳（v1 加法扩展）：「摸哪儿都算活动」的接线——扩展把用户交互上报给
    // 本体空闲锁（节流 1 秒/次），响应带回最新锚点+超时+锁态三件套；扩展用同一把钟
    // （uptimeMillis）本地倒计时，到点与本体 lockApp 同秒各自动作（本体无主动推送通道，
    // 「同时锁定」靠共享锚点而非消息）。老本体不认识 → call() 折异常，扩展静默降级
    // （扩展侧看门狗不武装，本体空闲锁照常）。
    const val METHOD_TOUCH_SESSION = "touchSession"

    // 扩展敏感配置托管管道（v1 加法扩展）：
    //   pushConfig：扩展保存敏感配置时推密文副本给本体托管（本体导出备份时随行）。
    //   fetchConfig：扩展启动时取回（备份恢复后本体置的待取副本）。
    // 密文全程 Keystore 加密、本体沙箱存储；老本体不认识 → call() 折异常，扩展静默降级。
    const val METHOD_PUSH_WEBDAV_CONFIG = "pushWebdavConfig"
    const val METHOD_FETCH_WEBDAV_CONFIG = "fetchWebdavConfig"

    // 扩展日志上投管道（v1 加法扩展）：扩展把自身事件写进本体操作日志（审计收编一处）。
    //   extras: KEY_LOG_TYPE / KEY_LOG_SUMMARY / KEY_LOG_DETAILS（string array，可缺省）。
    //   契约注释约定：type 建议传 "sync"；本体侧未知 type 白名单化为 "extension" 展示标签，
    //   summary/details 原样落盘（长度不裁剪——展示层负责省略，JsonlLog 轮转兜底存储）。
    //   老本体不认识 → call() 折异常，扩展静默降级（本地日志照写）。
    const val METHOD_APPEND_LOG = "appendLog"
    const val KEY_LOG_TYPE = "logType"
    const val KEY_LOG_SUMMARY = "logSummary"
    const val KEY_LOG_DETAILS = "logDetails"

    // state 管道的 Bundle 键（v1 全集）
    const val KEY_V = "v"                   // int，契约版本
    const val KEY_LOCKED = "locked"         // boolean，fail-closed 缺省 true
    const val KEY_THEME_MODE = "themeMode"  // light|system|dark
    const val KEY_THEME_ACCENT = "accentTheme"
    const val KEY_THEME_CUSTOM = "customThemeJson"

    // list/stat 等管道的通用键
    const val KEY_SCOPE = "scope"           // list: backup|other
    const val KEY_JSON = "json"
    const val KEY_REL = "rel"               // 文件管道统一相对路径键
    const val KEY_TO = "to"                 // move 目标
    const val KEY_MTIME_MS = "mtimeMs"      // touch
    const val KEY_IS_DIR = "isDir"
    const val KEY_SIZE = "size"

    // 托管管道的键（密文串；业务字段由扩展自行定义与解释）
    const val KEY_WEBDAV_URL = "webdavUrl"
    const val KEY_WEBDAV_USER = "webdavUser"
    const val KEY_WEBDAV_PASSWORD_ENC = "webdavPasswordEnc"

    // touchSession 响应三件套：扩展本地倒计时的输入（与本体 IdleLock 同源同语义）。
    const val KEY_LAST_TOUCH_MS = "lastTouchMs"   // long，锚点（本体侧 uptimeMillis 最新值）
    const val KEY_AUTO_LOCK_SEC = "autoLockSec"   // int，自动锁定秒数（0 = 用户关闭，扩展不武装）
    const val KEY_SESSION_ALIVE = "sessionAlive"  // boolean，本体解锁态（false = 扩展不武装）
}
