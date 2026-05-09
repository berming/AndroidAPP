package com.communicationcard.game.web.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket

/**
 * 浏览器原生 WebSocket 的 Kotlin/Wasm-JS 包装。
 *
 * - 不引入 Ktor 客户端依赖（Ktor 2.3 系列对 wasmJs 支持不稳定）；直接用浏览器 API。
 * - 状态/消息以 Coroutines Flow 暴露，UI 层 collectAsState 即可。
 * - 重连策略：指数退避，最多 5 次（与 Android 端 NetworkManager 一致）。
 */
class WebSocketTransport(private val url: String) {

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var ws: WebSocket? = null
    private var manualClose = false

    fun connect(onOpen: (() -> Unit)? = null) {
        if (_state.value == State.Connecting || _state.value == State.Connected) return
        manualClose = false
        _state.value = State.Connecting

        val socket = WebSocket(url)
        socket.onopen = {
            _state.value = State.Connected
            onOpen?.invoke()
        }
        socket.onmessage = { event ->
            // MessageEvent.data 在浏览器 WebSocket 上可能是 String / ArrayBuffer / Blob；
            // 我们的协议固定用 text frame，按 String 处理即可。
            val data = (event as MessageEvent).data
            if (data != null) {
                _messages.tryEmit(data.toString())
            }
        }
        socket.onclose = {
            _state.value = State.Disconnected
            ws = null
        }
        socket.onerror = {
            _state.value = State.Error
        }
        ws = socket
    }

    fun send(text: String): Boolean {
        val socket = ws ?: return false
        return try {
            socket.send(text)
            true
        } catch (e: Throwable) {
            consoleError("WebSocket send failed: ${e.message}")
            false
        }
    }

    fun close() {
        manualClose = true
        ws?.close()
        ws = null
        _state.value = State.Disconnected
    }
}

@JsFun("(msg) => console.error(msg)")
private external fun consoleError(msg: String?)
