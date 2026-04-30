package com.communicationcard.server

import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.encodeToString
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

    private val isActive = AtomicBoolean(true)

    fun isConnected(): Boolean = isActive.get() && webSocketSession.isActive

    suspend fun send(message: GameMessage) {
        if (!isConnected()) return
        try {
            val json = MessageSerializer.json.encodeToString(GameMessage.serializer(), message)
            webSocketSession.send(Frame.Text(json))
        } catch (e: Exception) {
            isActive.set(false)
        }
    }

    suspend fun close(reason: String = "Connection closed") {
        isActive.set(false)
        try {
            webSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (e: Exception) {
            // Already closed
        }
    }

    suspend fun receiveMessage(): GameMessage? {
        return try {
            val frame = webSocketSession.incoming.receive()
            if (frame is Frame.Text) {
                val text = frame.readText()
                MessageSerializer.fromJson(text)
            } else {
                null
            }
        } catch (e: ClosedReceiveChannelException) {
            isActive.set(false)
            null
        } catch (e: Exception) {
            null
        }
    }
}
