package com.communicationcard.game.web.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 浏览器原生 WebSocket 的 Kotlin/Wasm-JS 包装。
 *
 * 设计取舍（Kotlin 1.9.24 + CMP 1.6.10）：
 * - 不依赖 kotlinx-browser:0.1（该包要求 Kotlin 2.0+）；改为 @JsFun 直接 interop
 *   —— 把 WebSocket 的对象身份留在 JS 侧，Kotlin 持一个 JsAny 句柄，所有操作通过
 *   外部函数中转。这种方式跨 Kotlin/Wasm-JS 版本最稳，回调型 API 也最简单。
 * - 不引入 Ktor 客户端（Ktor 2.3 系列对 wasmJs 支持不稳定）。
 * - 状态/消息以 Coroutines Flow 暴露，UI 层 collectAsState 即可。
 *
 * 自动重连（2026-05-10 新增）：非主动 close 触发时按指数退避自动 reconnect。
 * 防御场景：4G/WiFi 切换、移动 NAT 超时、服务端短暂重启。**不**保护 Chrome 本地
 * 状态损坏类的永久 refused（每次 retry 仍会 refused，只是 5 次后停下不再耗电）。
 * 详见 [docs/regressions.md] "Chrome 本地状态损坏 → ERR_CONNECTION_REFUSED" 条目。
 */
class WebSocketTransport(private val url: String) {

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var ws: JsAny? = null
    private var manualClose = false

    // 自动重连：内部 scope，close() 时 cancel；onOpen 回调记下来供重连复用
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastOnOpen: (() -> Unit)? = null
    private var retryAttempt = 0
    private var retryJob: Job? = null

    fun connect(onOpen: (() -> Unit)? = null) {
        if (_state.value == State.Connecting || _state.value == State.Connected) return
        manualClose = false
        // 首次或新调用方传入的 onOpen 取代旧的；null 时保留以便后续 reconnect 复用
        if (onOpen != null) lastOnOpen = onOpen
        doConnect()
    }

    private fun doConnect() {
        _state.value = State.Connecting
        val socket = jsCreateWebSocket(
            url = url,
            onOpen = {
                _state.value = State.Connected
                retryAttempt = 0  // 成功 → 重置退避计数
                lastOnOpen?.invoke()
            },
            onMessage = { data ->
                _messages.tryEmit(data)
            },
            onClose = {
                _state.value = State.Disconnected
                ws = null
                if (!manualClose) scheduleReconnect()
            },
            onError = {
                _state.value = State.Error
                // onError 通常后跟 onClose；reconnect 在 onClose 触发，避免双调度
            },
        )
        ws = socket
    }

    /**
     * 指数退避 reconnect：1s / 2s / 4s / 8s / 16s（capped）；最多 [MAX_RETRIES] 次。
     * 失败超过上限 → 状态停留 Error/Disconnected，等待 UI 引导用户手动 close+connect。
     */
    private fun scheduleReconnect() {
        if (retryAttempt >= MAX_RETRIES) {
            consoleError("WebSocket reconnect: max retries ($MAX_RETRIES) reached, giving up")
            return
        }
        retryJob?.cancel()
        val backoff = (BASE_BACKOFF_MS shl retryAttempt).coerceAtMost(MAX_BACKOFF_MS)
        retryAttempt++
        retryJob = scope.launch {
            delay(backoff)
            if (!manualClose && _state.value == State.Disconnected) {
                doConnect()
            }
        }
    }

    fun send(text: String): Boolean {
        val socket = ws ?: return false
        return runCatching { jsWebSocketSend(socket, text) }
            .onFailure { consoleError("WebSocket send failed: ${it.message}") }
            .isSuccess
    }

    fun close() {
        manualClose = true
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        ws?.let { jsWebSocketClose(it) }
        ws = null
        _state.value = State.Disconnected
    }

    companion object {
        /** 重连退避基础间隔（ms）。第 N 次：base shl (N-1)，capped 到 [MAX_BACKOFF_MS]。 */
        private const val BASE_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 16_000L
        /** 最大重连次数。退避序列总和约 31 秒，超过仍未连上即放弃。 */
        private const val MAX_RETRIES = 5
    }
}

// ---------- JS interop ----------
// 不引用 org.w3c.dom 包；Kotlin 1.9.24 wasmJs 标准库中这部分由 kotlinx-browser 提供，
// 而 kotlinx-browser:0.1 要求 Kotlin 2.0+。统一改为 @JsFun 是绕开该约束最稳的方式。

@JsFun(
    """(url, onOpen, onMessage, onClose, onError) => {
        const ws = new WebSocket(url);
        ws.onopen = () => onOpen();
        ws.onmessage = (e) => onMessage(typeof e.data === 'string' ? e.data : '');
        ws.onclose = () => onClose();
        ws.onerror = () => onError();
        return ws;
    }""",
)
private external fun jsCreateWebSocket(
    url: String,
    onOpen: () -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
    onError: () -> Unit,
): JsAny

@JsFun("(ws, data) => ws.send(data)")
private external fun jsWebSocketSend(ws: JsAny, data: String)

@JsFun("(ws) => ws.close()")
private external fun jsWebSocketClose(ws: JsAny)

@JsFun("(msg) => console.error(msg)")
private external fun consoleError(msg: String?)
