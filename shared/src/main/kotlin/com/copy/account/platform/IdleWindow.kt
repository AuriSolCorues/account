package com.copy.account.platform

internal fun idleExpired(nowMs: Long, anchorMs: Long, timeoutMs: Long): Boolean =
    timeoutMs > 0 && nowMs - anchorMs >= timeoutMs
