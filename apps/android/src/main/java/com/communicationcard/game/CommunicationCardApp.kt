package com.communicationcard.game

import android.app.Application
import com.communicationcard.game.util.DebugLogManager
import kotlin.system.exitProcess

/**
 * 应用主类，在 onCreate 中初始化日志和崩溃处理
 */
class CommunicationCardApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化日志管理器
        DebugLogManager.init(this)
        DebugLogManager.i("App", "Application onCreate")

        // 设置全局崩溃处理器
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DebugLogManager.logUncaughtException(thread, throwable)
            } catch (_: Exception) {}
            // 调用默认处理器（让系统正常处理崩溃）
            defaultHandler?.uncaughtException(thread, throwable)
                ?: exitProcess(1)
        }
    }
}
