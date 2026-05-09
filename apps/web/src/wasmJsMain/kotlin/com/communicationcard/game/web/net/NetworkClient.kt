package com.communicationcard.game.web.net

import com.communicationcard.game.network.GameMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 在 [WebSocketTransport] 之上做协议层包装：
 * - 把入站 text frame 解码成 [GameMessage]
 * - 把出站 [GameMessage] 编码成 JSON 文本
 *
 * 与 Android 的 NetworkManager 等价，但只做协议层，不做心跳/重连
 * （后者放到 RoomManager / GameSyncManager 业务层处理）。
 */
class NetworkClient(serverUrl: String) {

    private val transport = WebSocketTransport(serverUrl)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val connectionState: StateFlow<WebSocketTransport.State> = transport.state

    private val _messages = MutableSharedFlow<GameMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<GameMessage> = _messages.asSharedFlow()

    init {
        scope.launch {
            transport.messages.collect { text ->
                runCatching { GameMessage.fromJson(text) }
                    .onSuccess { _messages.emit(it) }
                    .onFailure { logError("Failed to decode message: $text — ${it.message}") }
            }
        }
    }

    fun connect(onOpen: (() -> Unit)? = null) {
        transport.connect(onOpen)
    }

    fun send(message: GameMessage): Boolean = transport.send(message.toJson())

    fun close() {
        transport.close()
    }
}

@JsFun("(msg) => console.error(msg)")
private external fun logError(msg: String?)
