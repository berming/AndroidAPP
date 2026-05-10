package com.communicationcard.game.web.net

import com.communicationcard.game.network.AddAI
import com.communicationcard.game.network.CreateRoom
import com.communicationcard.game.network.GameMessage
import com.communicationcard.game.network.JoinRoom
import com.communicationcard.game.network.LeaveRoom
import com.communicationcard.game.network.ListRooms
import com.communicationcard.game.network.PlayerReady
import com.communicationcard.game.network.RoomCreated
import com.communicationcard.game.network.RoomInfo
import com.communicationcard.game.network.RoomJoined
import com.communicationcard.game.network.RoomListResult
import com.communicationcard.game.network.RoomUpdate
import com.communicationcard.game.network.StartGameRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 房间状态管理（联网模式）。与 Android 端 RoomManager 等价。
 *
 * 订阅 [NetworkClient] 的入站消息，维护当前房间 / 房间列表 / 本地 playerId。
 */
class RoomManager(private val net: NetworkClient) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentRoom = MutableStateFlow<RoomInfo?>(null)
    val currentRoom: StateFlow<RoomInfo?> = _currentRoom.asStateFlow()

    private val _roomList = MutableStateFlow<List<RoomInfo>>(emptyList())
    val roomList: StateFlow<List<RoomInfo>> = _roomList.asStateFlow()

    private val _localPlayerId = MutableStateFlow<String?>(null)
    val localPlayerId: StateFlow<String?> = _localPlayerId.asStateFlow()

    /** 本地玩家在当前房间的座位号；-1 表示尚未坐下。 */
    val localSeatIndex: Int
        get() = _currentRoom.value?.players
            ?.find { it.id == _localPlayerId.value }
            ?.seatIndex ?: -1

    init {
        scope.launch {
            net.messages.collect { msg -> handleMessage(msg) }
        }
    }

    private fun handleMessage(message: GameMessage) {
        when (message) {
            is RoomCreated -> {
                _currentRoom.value = message.room
                _localPlayerId.value = message.room.hostId
                // 让 NetworkClient 在自动重连时恢复会话（Codex P1 on PR #59）
                net.setSessionToken(message.room.hostId)
            }
            is RoomJoined -> {
                _currentRoom.value = message.room
                _localPlayerId.value = message.playerId
                net.setSessionToken(message.playerId)
            }
            is RoomUpdate -> {
                _currentRoom.value = message.room
            }
            is RoomListResult -> {
                _roomList.value = message.rooms
            }
            else -> { /* 其他消息留给 GameSyncManager 处理 */ }
        }
    }

    fun refreshRoomList(): Boolean = net.send(ListRooms())

    fun createRoom(playerName: String, roomName: String = "", maxPlayers: Int = 6): Boolean =
        net.send(CreateRoom(playerName, roomName, maxPlayers))

    fun joinRoom(roomCode: String, playerName: String): Boolean =
        net.send(JoinRoom(roomCode, playerName))

    fun setReady(isReady: Boolean): Boolean = net.send(PlayerReady(isReady))

    fun addAI(): Boolean {
        val roomId = _currentRoom.value?.roomId ?: return false
        return net.send(AddAI(roomId))
    }

    fun startGame(): Boolean {
        val room = _currentRoom.value ?: return false
        if (room.hostId != _localPlayerId.value) return false
        return net.send(StartGameRequest(room.roomId))
    }

    fun leaveRoom(): Boolean {
        val room = _currentRoom.value ?: return false
        val ok = net.send(LeaveRoom(room.roomId))
        if (ok) {
            _currentRoom.value = null
            _localPlayerId.value = null
            // 已离开房间，后续重连不再带 token；服务端按全新会话处理
            net.setSessionToken(null)
        }
        return ok
    }
}
