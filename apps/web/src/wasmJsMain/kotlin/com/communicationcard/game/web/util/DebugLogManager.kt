package com.communicationcard.game.web.util

import com.communicationcard.game.web.storage.LocalStorage

/**
 * Web 端调试日志管理器（feature_spec N6）。
 *
 * 与 Android 端 `apps/android/.../util/DebugLogManager.kt` 对齐：
 * - In-memory 环形缓冲（[MAX_LOGS] 条）
 * - localStorage 持久化（key = [STORAGE_KEY]，上限 [MAX_STORAGE_BYTES] 字节）
 * - d/i/w/e 四级 + 同步 console.log 镜像（保留 DevTools 体验）
 * - 应用启动写一行 separator，方便区分多次会话
 *
 * 单线程：wasmJs 单线程模型 → 不需要 CopyOnWriteArrayList / Mutex。
 *
 * 失败安全：localStorage 隐私模式 / 配额满都被 [LocalStorage] 内部 try-catch 吞掉
 * （静默 no-op）；内存日志依旧可用。
 */
object DebugLogManager {

    private const val MAX_LOGS = 500
    private const val STORAGE_KEY = "debug_log"
    private const val PENDING_ERRORS_KEY = "debug_log_pending_errors"  // Main.kt JS handler 的中转 key
    private const val MAX_STORAGE_BYTES = 256 * 1024  // 256 KB（与 Android 一致；localStorage 配额 ~5 MB）

    data class LogEntry(
        val time: String,
        val level: String,
        val tag: String,
        val message: String,
    ) {
        override fun toString(): String = "$time $level/$tag: $message"
    }

    private val logs = ArrayList<LogEntry>()
    private var initialized = false

    /**
     * 初始化（在 [Main] 调 [ComposeViewport] 之前一次性调用）。
     * 幂等：多次调用只执行第一次。
     */
    fun init() {
        if (initialized) return
        initialized = true
        // 加载上次会话保留的日志（如有）— localStorage 顺序追加，按行恢复
        LocalStorage.getString(STORAGE_KEY)?.let { existing ->
            existing.lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(MAX_LOGS)
                .forEach { line -> logs.add(LogEntry("", "", "", line)) }
        }
        // 写应用启动 separator（带完整日期，便于区分 session）
        val sep = "========== App Start ${nowDateString()} =========="
        addLog("I", "DebugLogManager", sep)
        // 排空全局错误 handler 在 init 之前累积的待处理错误（Main.kt 的中转 key）
        drainPendingErrors()
    }

    /**
     * 把 JS 全局错误 handler 累积到 `debug_log_pending_errors` 的 entries
     * 排到主日志，然后清空中转 key。每条形如 `{"t":..., "m":"...", "s":"..."}`。
     */
    private fun drainPendingErrors() {
        val raw = LocalStorage.getString(PENDING_ERRORS_KEY) ?: return
        if (raw.isBlank()) return
        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            // 极简 JSON 解析：避免引 kotlinx.serialization（已重，且字段固定）
            val msg = extractJsonField(line, "m") ?: line
            val stack = extractJsonField(line, "s")?.takeIf { it.isNotBlank() }
            logUncaughtException(msg, stack)
        }
        LocalStorage.remove(PENDING_ERRORS_KEY)
    }

    /** 极简字段提取：`"key":"value"`（不处理嵌套转义；够本中转 key 用）。 */
    private fun extractJsonField(json: String, key: String): String? {
        val pattern = "\"$key\":\""
        val start = json.indexOf(pattern).takeIf { it >= 0 } ?: return null
        val valueStart = start + pattern.length
        val end = json.indexOf("\"", valueStart).takeIf { it >= 0 } ?: return null
        return json.substring(valueStart, end).replace("\\\"", "\"").replace("\\n", "\n")
    }

    fun d(tag: String, message: String) = addLog("D", tag, message)
    fun i(tag: String, message: String) = addLog("I", tag, message)
    fun w(tag: String, message: String) = addLog("W", tag, message)
    fun e(tag: String, message: String) = addLog("E", tag, message)

    /** 记录全局未捕获异常（Main.kt 安装 window.onerror 后回调进来）。 */
    fun logUncaughtException(message: String, stack: String?) {
        val text = if (stack.isNullOrBlank()) message else "$message\n$stack"
        addLog("E", "GlobalErrorHandler", text)
    }

    private fun addLog(level: String, tag: String, message: String) {
        val entry = LogEntry(
            time = nowTimeString(),
            level = level,
            tag = tag,
            message = message,
        )
        logs.add(entry)
        // 环形截断
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        // 同步 console.log（保留 DevTools 体验，方便开发期 debug）
        jsConsoleLog("[$level/$tag] $message")
        // 异步落盘（每次都写；localStorage 是同步 API，无需 debounce — 写少量字符串极快）
        persistToLocalStorage()
    }

    /** 序列化全部日志到 localStorage；超过 [MAX_STORAGE_BYTES] 截断头部。 */
    private fun persistToLocalStorage() {
        val full = logs.joinToString("\n") { it.toString() }
        val truncated = if (full.length > MAX_STORAGE_BYTES) {
            full.substring(full.length - MAX_STORAGE_BYTES)
        } else {
            full
        }
        LocalStorage.setString(STORAGE_KEY, truncated)
    }

    fun getLogs(): List<LogEntry> = logs.toList()

    fun getLogsAsString(): String = logs.joinToString("\n") { it.toString() }

    fun clear() {
        logs.clear()
        LocalStorage.remove(STORAGE_KEY)
        // 加一行标记便于区分"刚清空"和"从未写入"
        addLog("I", "DebugLogManager", "Logs cleared by user")
    }
}

// === JS interop helpers (ASCII-only @JsFun bodies per Main.kt convention) ===

@JsFun("(msg) => { try { console.log(msg); } catch (e) {} }")
private external fun jsConsoleLog(msg: String)

/** HH:mm:ss.SSS — 与 Android DebugLogManager 时间格式对齐。 */
@JsFun(
    """() => {
        const d = new Date();
        const pad = (n, w) => String(n).padStart(w, '0');
        return pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' +
               pad(d.getSeconds(), 2) + '.' + pad(d.getMilliseconds(), 3);
    }""",
)
private external fun nowTimeString(): String

/** yyyy-MM-dd HH:mm:ss — 应用启动 separator 用。 */
@JsFun(
    """() => {
        const d = new Date();
        const pad = (n) => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
               ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }""",
)
private external fun nowDateString(): String
