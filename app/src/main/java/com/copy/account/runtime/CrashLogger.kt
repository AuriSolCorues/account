package com.copy.account.runtime

import android.content.Context

fun installCrashLogger(context: Context) {
    val appContext = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous is CrashLoggingHandler) return
    Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(appContext, previous))
}

private class CrashLoggingHandler(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            appendLog(
                appContext,
                LogEvents.CRASH,
                "软件崩溃：${throwable.javaClass.simpleName}（${thread.name}）",
                details = throwable.stackTrace.take(20).map { it.toString() }
            )
        }
        previous?.uncaughtException(thread, throwable)
    }
}
