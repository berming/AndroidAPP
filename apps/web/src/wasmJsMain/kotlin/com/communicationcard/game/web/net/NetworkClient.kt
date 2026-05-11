package com.communicationcard.game.web.net

import com.communicationcard.game.network.GameMessage
import com.communicationcard.game.network.Reconnect
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
 * 与 Android 的 NetworkManager 等价。
 *
 * **会话恢复（Codex P1 on PR #59）**：[WebSocketTransport] 的指数退避重连只
 * 重建 TCP/WS 通道；服务端 `handleReconnect` 仅在收到 `sys.reconnect` 消息时
 * 才恢复 room/player 会话。这里在每次 onOpen 自动发 [Reconnect] 让重连真正
 * 恢复活跃房间/游戏，而不是变成"看似连上但服务端不认识的孤儿连接"。
 *
 * 谁负责 set token：
 * - [RoomManager] 收到 `RoomCreated` / `RoomJoined` 时调 [setSessionToken]
 * - [RoomManager] 收到 leaveRoom / 用户回主屏时调 `setSessionToken(null)`
 * 首次连接时 token=null，跳过发送 → 服务端按全新会话处理（正常路径）。
 */
class NetworkClient(serverUrl: String) {

    private val transport = WebSocketTransport(serverUrl)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val connectionState: StateFlow<WebSocketTransport.State> = transport.state

    private val _messages = MutableSharedFlow<GameMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<GameMessage> = _messages.asSharedFlow()

    /**
     * 服务端给这位玩家的 sessionId（= [com.communicationcard.game.network.RoomJoined.playerId]
     * 或 [com.communicationcard.game.network.RoomCreated.room.hostId]）。
     * 仅 in-memory；刷新页面后丢失（与 Android 行为一致）。
     */
    private var sessionToken: String? = null

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
        // 用本地变量固化 onOpen 让 wasmJs/Compose smart-cast 链路稳定
        // （regressions #12 模式：函数引用宁可包成 lambda）
        val externalOpen = onOpen
        transport.connect(onOpen = {
            // 每次 onOpen（含初次连接 + 自动重连）都先尝试恢复会话
            sessionToken?.let { token ->
                transport.send(Reconnect(sessionToken = token).toJson())
            }
            externalOpen?.invoke()
        })
    }

    /**
     * 设置 sessionToken；下一次 reconnect 后的 onOpen 会自动发 [Reconnect] 让
     * 服务端恢复 room/player 会话。null 表示当前不在房间，重连不带 token
     * （服务端按全新会话处理）。
     */
    fun setSessionToken(token: String?) {
        sessionToken = token
    }

    fun send(message: GameMessage): Boolean = transport.send(message.toJson())

    fun close() {
        transport.close()
    }
}

@JsFun("(msg) => console.error(msg)")
private external fun logError(msg: String?)
