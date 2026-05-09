package com.communicationcard.game.web.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 浏览器原生 WebSocket 的 Kotlin/Wasm-JS 包装。
 *
 * 设计取舍（Kotlin 1.9.24 + CMP 1.6.10）：
 * - 不依赖 kotlinx-browser:0.1（该包要求 Kotlin 2.0+）；改为 @JsFun 直接 interop
 *   —— 把 WebSocket 的对象身份留在 JS 侧，Kotlin 持一个 JsAny 句柄，所有操作通过
 *   外部函数中转。这种方式跨 Kotlin/Wasm-JS 版本最稳，回调型 API 也最简单。
 * - 不引入 Ktor 客户端（Ktor 2.3 系列对 wasmJs 支持不稳定）。
 * - 状态/消息以 Coroutines Flow 暴露，UI 层 collectAsState 即可。
 */
class WebSocketTransport(private val url: String) {

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var ws: JsAny? = null
    private var manualClose = false

    fun connect(onOpen: (() -> Unit)? = null) {
        if (_state.value == State.Connecting || _state.value == State.Connected) return
        manualClose = false
        _state.value = State.Connecting

        val socket = jsCreateWebSocket(
            url = url,
            onOpen = {
                _state.value = State.Connected
                onOpen?.invoke()
            },
            onMessage = { data ->
                _messages.tryEmit(data)
            },
            onClose = {
                _state.value = State.Disconnected
                ws = null
            },
            onError = {
                _state.value = State.Error
            },
        )
        ws = socket
    }

    fun send(text: String): Boolean {
        val socket = ws ?: return false
        return runCatching { jsWebSocketSend(socket, text) }
            .onFailure { consoleError("WebSocket send failed: ${it.message}") }
            .isSuccess
    }

    fun close() {
        manualClose = true
        ws?.let { jsWebSocketClose(it) }
        ws = null
        _state.value = State.Disconnected
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
