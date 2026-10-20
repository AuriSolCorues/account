/**
 * 职责：崩溃日志钩子——进程级未捕获异常先追加进操作日志（filesDir）再交系统默认处理。
 *       安装点在 MainActivity.onCreate（Application 级单点），保证任何页面的崩溃都留痕。
 * 架构位置：runtime/ 层；MainActivity 消费。
 * Python 类比：≈ sys.excepthook 挂自定义 handler——先写文件再走默认退出流程。
 */
package com.copy.account.runtime

import android.content.Context

/** 安装全局崩溃钩子（幂等：重复安装不叠加）。必须在主线程异常发生前调用。 */
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
        // 先落盘再交还系统（同步写——进程即将死亡，异步来不及）
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
