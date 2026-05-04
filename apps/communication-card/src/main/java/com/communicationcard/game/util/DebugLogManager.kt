package com.communicationcard.game.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 调试日志管理器
 * 将日志保存在内存中，方便查看
 */
object DebugLogManager {

    private const val MAX_LOGS = 500
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class LogEntry(
        val time: String,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: String? = null
    ) {
        override fun toString(): String {
            val base = "$time $level/$tag: $message"
            return if (throwable != null) "$base\n$throwable" else base
        }
    }

    fun d(tag: String, message: String) {
        addLog("D", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        addLog("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        addLog("W", tag, message, throwable)
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        addLog("E", tag, message, throwable)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    private fun addLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            time = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.stackTraceToString()
        )
        logs.add(entry)

        // 保持日志数量限制
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
    }

    fun getLogs(): List<LogEntry> = logs.toList()

    fun getLogsAsString(): String {
        return logs.joinToString("\n") { it.toString() }
    }

    fun clear() {
        logs.clear()
    }
}
