package com.communicationcard.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat

fun main() {
    println("=== Starting Server ===")

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        println("=== Configuring Server ===")

        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(60)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        println("=== WebSockets installed ===")

        val roomManager = ServerRoomManager()
        val gameManager = ServerGameManager(roomManager)
        val sessions = ConcurrentHashMap<String, GameSession>()

        routing {
            get("/") {
                println("=== GET / received ===")
                call.respondText("OK", ContentType.Text.Plain)
            }

            get("/health") {
                println("=== GET /health received ===")
                call.respondText("Healthy", ContentType.Text.Plain)
            }

            webSocket("/game") {
                val sessionId = UUID.randomUUID().toString().take(8)
                println("=== WebSocket /game connected: $sessionId ===")

                val session = GameSession(sessionId, this)
                sessions[sessionId] = session

                try {
                    handleSession(session, roomManager, gameManager, sessions)
                } catch (e: ClosedReceiveChannelException) {
                    println("=== WebSocket closed: $sessionId ===")
                } catch (e: Exception) {
                    println("=== WebSocket error: ${e.message} ===")
                    e.printStackTrace()
                } finally {
                    handleDisconnect(session, roomManager, gameManager, sessions)
                }
            }
        }

        println("=== Routing configured ===")
        println("=== Server ready on port 8080 ===")
    }.start(wait = true)
}

private suspend fun handleSession(
    session: GameSession,
    roomManager: ServerRoomManager,
    gameManager: ServerGameManager,
    sessions: ConcurrentHashMap<String, GameSession>
) {
    while (true) {
        val message = session.receiveMessage() ?: break
        handleMessage(session, message, roomManager, gameManager, sessions)
    }
}

private suspend fun handleMessage(
    session: GameSession,
    message: GameMessage,
    roomManager: ServerRoomManager,
    gameManager: ServerGameManager,
    sessions: ConcurrentHashMap<String, GameSession>
) {
    when (message) {
        is CreateRoom -> handleCreateRoom(session, message, roomManager)
        is JoinRoom -> handleJoinRoom(session, message, roomManager)
        is LeaveRoom -> handleLeaveRoom(session, roomManager, gameManager)
        is PlayerReady -> handlePlayerReady(session, message, roomManager)
        is StartGameRequest -> handleStartGame(session, roomManager, gameManager)
        is KickPlayer -> handleKickPlayer(session, message, roomManager)
        is AddAI -> handleAddAI(session, roomManager)
        is ListRooms -> handleListRooms(session, roomManager)
        is GameAction -> handleGameAction(session, message, gameManager)
        is TextChatMessage -> handleTextChat(session, message, roomManager)
        is QuickChatMessage -> handleQuickChat(session, message, roomManager)
        is Heartbeat -> session.send(Heartbeat(System.currentTimeMillis()))
        is Reconnect -> handleReconnect(session, message, roomManager, gameManager, sessions)
        else -> session.send(ErrorMessage(400, "未知消息类型"))
    }
}

private suspend fun handleCreateRoom(
    session: GameSession,
    message: CreateRoom,
    roomManager: ServerRoomManager
) {
    val room = roomManager.createRoom(session, message.playerName, message.roomName, message.maxPlayers)
    session.send(RoomCreated(room.toRoomInfo()))
}

private suspend fun handleJoinRoom(
    session: GameSession,
    message: JoinRoom,
    roomManager: ServerRoomManager
) {
    val result = roomManager.joinRoom(session, message.roomCode.uppercase(), message.playerName)
    result.fold(
        onSuccess = { room ->
            session.send(RoomJoined(room.toRoomInfo(), session.id))
            broadcastToRoom(room, RoomUpdate(room.toRoomInfo()), excludeId = session.id)
        },
        onFailure = { error ->
            session.send(ErrorMessage(400, error.message ?: "加入失败"))
        }
    )
}

private suspend fun handleLeaveRoom(
    session: GameSession,
    roomManager: ServerRoomManager,
    gameManager: ServerGameManager
) {
    val roomId = session.roomId
    val room = roomManager.leaveRoom(session)
    if (room != null) {
        broadcastToRoom(room, RoomUpdate(room.toRoomInfo()))
    } else if (roomId != null) {
        // 房间已删除，清理游戏管理器中的资源
        gameManager.cleanupRoom(roomId)
    }
}

private suspend fun handlePlayerReady(
    session: GameSession,
    message: PlayerReady,
    roomManager: ServerRoomManager
) {
    val room = roomManager.setReady(session, message.isReady)
    if (room != null) {
        broadcastToRoom(room, RoomUpdate(room.toRoomInfo()))
    }
}

private suspend fun handleStartGame(
    session: GameSession,
    roomManager: ServerRoomManager,
    gameManager: ServerGameManager
) {
    val room = roomManager.getRoom(session.roomId ?: "") ?: run {
        session.send(ErrorMessage(404, "房间不存在"))
        return
    }

    if (room.hostId != session.id) {
        session.send(ErrorMessage(403, "只有房主可以开始游戏"))
        return
    }

    if (!roomManager.canStartGame(room)) {
        session.send(ErrorMessage(400, "无法开始游戏：需要至少1名真人玩家且全部准备"))
        return
    }

    // 先用AI填满到6人，再开始游戏
    roomManager.fillWithAI(room, 6)

    // 填充后再次校验（最少6人才能玩）
    if (room.players.size < 6) {
        session.send(ErrorMessage(400, "玩家数量不足，无法开始游戏"))
        return
    }

    room.status = RoomStatus.IN_GAME
    val gameState = gameManager.startGame(room)

    // 先广播房间状态变化（IN_GAME），让客户端看到队伍信息
    broadcastToRoom(room, RoomUpdate(room.toRoomInfo()))

    room.players.forEach { player ->
        val playerState = gameManager.getStateForPlayer(room, player.seatIndex)
        player.session?.send(GameStart(playerState))
    }

    // 广播初始回合开始
    val firstPlayerId = gameState.currentPlayerIndex
    broadcastToRoom(room, GameEventMessage(SerializedGameEvent.TurnStart(firstPlayerId)))

    println("Game started in room ${room.roomCode}, first player: $firstPlayerId")
}

private suspend fun handleKickPlayer(
    session: GameSession,
    message: KickPlayer,
    roomManager: ServerRoomManager
) {
    val result = roomManager.kickPlayer(session, message.playerId)
    result.fold(
        onSuccess = { room ->
            val kickedPlayer = room.players.find { it.id == message.playerId }
            kickedPlayer?.session?.send(ErrorMessage(403, "你已被踢出房间"))
            kickedPlayer?.session?.close("Kicked from room")
            broadcastToRoom(room, RoomUpdate(room.toRoomInfo()))
        },
        onFailure = { error ->
            session.send(ErrorMessage(400, error.message ?: "踢人失败"))
        }
    )
}

private suspend fun handleAddAI(
    session: GameSession,
    roomManager: ServerRoomManager
) {
    val result = roomManager.addAI(session)
    result.fold(
        onSuccess = { room ->
            broadcastToRoom(room, RoomUpdate(room.toRoomInfo()))
        },
        onFailure = { error ->
            session.send(ErrorMessage(400, error.message ?: "添加AI失败"))
        }
    )
}

private suspend fun handleListRooms(
    session: GameSession,
    roomManager: ServerRoomManager
) {
    val rooms = roomManager.listWaitingRooms()
    session.send(RoomListResult(rooms))
}

private suspend fun handleGameAction(
    session: GameSession,
    message: GameAction,
    gameManager: ServerGameManager
) {
    val result = gameManager.handleAction(session, message.action)

    if (result.success) {
        val room = gameManager.roomManager.getRoom(session.roomId ?: "") ?: return
        gameManager.broadcastActionResult(room, result)
    } else {
        session.send(GameActionResult(false, result.error))
    }
}

private suspend fun handleTextChat(
    session: GameSession,
    message: TextChatMessage,
    roomManager: ServerRoomManager
) {
    val room = roomManager.getRoom(session.roomId ?: "") ?: return

    val chatMessage = TextChatMessage(
        senderId = session.id,
        senderName = session.playerName,
        text = message.text,
        timestamp = System.currentTimeMillis(),
        isTeamOnly = message.isTeamOnly
    )

    if (message.isTeamOnly) {
        val senderPlayer = room.players.find { it.id == session.id }
        room.players
            .filter { it.team == senderPlayer?.team }
            .forEach { it.session?.send(chatMessage) }
    } else {
        broadcastToRoom(room, chatMessage)
    }
}

private suspend fun handleQuickChat(
    session: GameSession,
    message: QuickChatMessage,
    roomManager: ServerRoomManager
) {
    val room = roomManager.getRoom(session.roomId ?: "") ?: return

    val chatMessage = QuickChatMessage(
        senderId = session.id,
        senderName = session.playerName,
        quickType = message.quickType,
        timestamp = System.currentTimeMillis()
    )

    broadcastToRoom(room, chatMessage)
}

private suspend fun handleReconnect(
    session: GameSession,
    message: Reconnect,
    roomManager: ServerRoomManager,
    gameManager: ServerGameManager,
    sessions: ConcurrentHashMap<String, GameSession>
) {
    val oldSessionId = message.sessionToken
    val room = roomManager.getRoomByPlayer(oldSessionId)

    if (room != null) {
        val player = room.players.find { it.id == oldSessionId }
        if (player != null) {
            player.session = session
            player.isConnected = true
            // 重连后人类玩家不再是AI代打
            player.isAISubstitute = false
            session.roomId = room.roomId
            session.playerName = player.name
            session.seatIndex = player.seatIndex

            // 注意：保持 player.id 不变（与原session一致），sessions 里两个键都映射到新 session
            // 这样 playerToRoom (key=player.id) 仍然有效
            sessions[session.id] = session

            val state = if (room.status == RoomStatus.IN_GAME) {
                gameManager.getStateForPlayer(room, player.seatIndex)
            } else null

            session.send(ReconnectSuccess(state))
            // 重新发送当前房间信息，确保客户端 UI 同步
            session.send(RoomUpdate(room.toRoomInfo()))
            broadcastToRoom(room, PlayerReconnected(player.id, player.name), excludeId = session.id)

            println("Player ${player.name} reconnected to room ${room.roomCode}")
            return
        }
    }

    session.send(ErrorMessage(404, "未找到之前的会话"))
}

private suspend fun handleDisconnect(
    session: GameSession,
    roomManager: ServerRoomManager,
    gameManager: ServerGameManager,
    sessions: ConcurrentHashMap<String, GameSession>
) {
    sessions.remove(session.id)

    val room = roomManager.getRoom(session.roomId ?: "") ?: return
    val player = room.players.find { it.id == session.id } ?: return

    player.isConnected = false
    player.session = null

    broadcastToRoom(room, PlayerDisconnected(player.id, player.name))

    if (room.status == RoomStatus.IN_GAME) {
        player.isAISubstitute = true
        gameManager.handlePlayerDisconnect(room, player)
    } else if (room.status == RoomStatus.WAITING) {
        // 等待状态下，断线即视为离开
        val updatedRoom = roomManager.leaveRoom(session)
        if (updatedRoom != null) {
            broadcastToRoom(updatedRoom, RoomUpdate(updatedRoom.toRoomInfo()))
        } else {
            // 房间已清理
            gameManager.cleanupRoom(room.roomId)
        }
    }

    println("Player ${player.name} disconnected from room ${room.roomCode}")
}

private suspend fun broadcastToRoom(
    room: ServerRoom,
    message: GameMessage,
    excludeId: String? = null
) {
    room.players.forEach { player ->
        if (player.id != excludeId && player.session != null) {
            player.session?.send(message)
        }
    }
}
