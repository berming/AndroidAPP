package com.communicationcard.game.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 调试日志管理器
 * 内存 + 文件双重保存，崩溃后日志不会丢失
 */
object DebugLogManager {

    private const val MAX_LOGS = 500
    private const val LOG_FILE_NAME = "debug_log.txt"
    private const val MAX_FILE_SIZE = 256 * 1024 // 256KB

    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private var initialized = false

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

    /**
     * 初始化（在 Application 或第一个 Activity onCreate 中调用）
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            val dir = context.filesDir
            logFile = File(dir, LOG_FILE_NAME)

            // 如果文件超过大小限制，清空
            if (logFile!!.exists() && logFile!!.length() > MAX_FILE_SIZE) {
                logFile!!.delete()
            }

            // 加载已有日志到内存（用于显示）
            if (logFile!!.exists()) {
                try {
                    val existing = logFile!!.readText().lines().filter { it.isNotBlank() }
                    existing.takeLast(MAX_LOGS).forEach { line ->
                        logs.add(LogEntry("", "", "", line))
                    }
                } catch (e: Exception) {
                    Log.e("DebugLogManager", "Failed to load existing log", e)
                }
            }

            // 打开文件以追加模式写入
            fileWriter = FileWriter(logFile!!, true)
            initialized = true

            // 写一条标记日志
            val sep = "\n========== App Start ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} ==========\n"
            fileWriter?.write(sep)
            fileWriter?.flush()
            logs.add(LogEntry("", "I", "DebugLogManager", sep.trim()))
        } catch (e: Exception) {
            Log.e("DebugLogManager", "init failed", e)
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
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        addLog("E", tag, message, throwable)
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
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

        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }

        // 写入文件
        try {
            fileWriter?.write(entry.toString() + "\n")
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e("DebugLogManager", "write failed", e)
        }
    }

    /**
     * 记录未捕获的异常
     */
    fun logUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val msg = "UNCAUGHT EXCEPTION on thread ${thread.name}"
            addLog("E", "CrashHandler", msg, throwable)
            fileWriter?.flush()
        } catch (_: Exception) {}
    }

    fun getLogs(): List<LogEntry> = logs.toList()

    fun getLogsAsString(): String = logs.joinToString("\n") { it.toString() }

    /**
     * 从文件读取完整日志（包含上次崩溃前的）
     */
    fun getFullLogsFromFile(): String {
        return try {
            logFile?.readText() ?: getLogsAsString()
        } catch (e: Exception) {
            getLogsAsString()
        }
    }

    fun clear() {
        logs.clear()
        try {
            fileWriter?.close()
            logFile?.delete()
            logFile?.let {
                fileWriter = FileWriter(it, true)
            }
        } catch (e: Exception) {
            Log.e("DebugLogManager", "clear failed", e)
        }
    }
}
