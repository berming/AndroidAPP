package com.communicationcard.server

import com.communicationcard.game.network.GameMessage
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket会话包装器
 */
class GameSession(
    val id: String,
    private val webSocketSession: WebSocketSession
) {
    var roomId: String? = null
    var playerName: String = ""
    var seatIndex: Int = -1

    val connectedAt: Long = System.currentTimeMillis()

    private val isActive = AtomicBoolean(true)

    fun isConnected(): Boolean = isActive.get() && webSocketSession.isActive

    suspend fun send(message: GameMessage) {
        if (!isConnected()) return
        try {
            val json = GameMessage.json.encodeToString(GameMessage.serializer(), message)
            webSocketSession.send(Frame.Text(json))
        } catch (e: Exception) {
            System.err.println("WebSocket send failed: ${e.message}")
            isActive.set(false)
        }
    }

    suspend fun close(reason: String = "Connection closed") {
        isActive.set(false)
        try {
            webSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (e: Exception) {
            // 已经关闭或对端已断开属预期情况，仅记录不再处理
            System.err.println("WebSocket close (already closed or peer gone): ${e.message}")
        }
    }

    suspend fun receiveMessage(): GameMessage? {
        return try {
            val frame = webSocketSession.incoming.receive()
            if (frame is Frame.Text) {
                val text = frame.readText()
                GameMessage.fromJson(text)
            } else {
                null
            }
        } catch (e: ClosedReceiveChannelException) {
            System.err.println("WebSocket channel closed: ${e.message}")
            isActive.set(false)
            null
        } catch (e: Exception) {
            System.err.println("WebSocket receive failed: ${e.message}")
            null
        }
    }
}
