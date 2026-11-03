package com.copy.account.platform

object BridgeContract {
    const val VERSION = 1

    const val AUTHORITY = "com.copy.account.extbridge"
    const val METHOD_LIST = "list"
    const val METHOD_STATE = "state"
    const val METHOD_STAT = "stat"
    const val METHOD_SIZE_OF = "sizeOf"
    const val METHOD_DELETE_LOCAL = "deleteLocal"
    const val METHOD_MOVE = "move"
    const val METHOD_TOUCH = "touch"
    const val METHOD_SCHEDULE_RESUME = "scheduleResume"
    const val METHOD_RELEASE_SESSION = "releaseSession"
    const val METHOD_TOUCH_SESSION = "touchSession"

    const val METHOD_PUSH_WEBDAV_CONFIG = "pushWebdavConfig"
    const val METHOD_FETCH_WEBDAV_CONFIG = "fetchWebdavConfig"

    const val METHOD_APPEND_LOG = "appendLog"
    const val KEY_LOG_TYPE = "logType"
    const val KEY_LOG_SUMMARY = "logSummary"
    const val KEY_LOG_DETAILS = "logDetails"

    const val KEY_V = "v"
    const val KEY_LOCKED = "locked"
    const val KEY_THEME_MODE = "themeMode"
    const val KEY_THEME_ACCENT = "accentTheme"
    const val KEY_THEME_CUSTOM = "customThemeJson"

    const val KEY_SCOPE = "scope"
    const val KEY_JSON = "json"
    const val KEY_REL = "rel"
    const val KEY_TO = "to"
    const val KEY_MTIME_MS = "mtimeMs"
    const val KEY_IS_DIR = "isDir"
    const val KEY_SIZE = "size"

    const val KEY_WEBDAV_URL = "webdavUrl"
    const val KEY_WEBDAV_USER = "webdavUser"
    const val KEY_WEBDAV_PASSWORD_ENC = "webdavPasswordEnc"

    const val KEY_LAST_TOUCH_MS = "lastTouchMs"
    const val KEY_AUTO_LOCK_SEC = "autoLockSec"
    const val KEY_SESSION_ALIVE = "sessionAlive"
}
