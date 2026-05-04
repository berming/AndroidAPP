package com.communicationcard.game.network

import com.communicationcard.game.util.DebugLogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 房间管理器
 * 负责房间创建、加入、离开、状态同步
 */
class RoomManager(
    private val networkManager: NetworkManager
) {
    companion object {
        private const val TAG = "RoomManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 当前房间
    private val _currentRoom = MutableStateFlow<RoomInfo?>(null)
    val currentRoom: StateFlow<RoomInfo?> = _currentRoom.asStateFlow()

    // 房间列表（公开房间）
    private val _roomList = MutableStateFlow<List<RoomInfo>>(emptyList())
    val roomList: StateFlow<List<RoomInfo>> = _roomList.asStateFlow()

    // 本地玩家ID
    private var _localPlayerId: String? = null
    val localPlayerId: String? get() = _localPlayerId

    // 本地玩家座位号
    val localSeatIndex: Int
        get() = currentRoom.value?.players?.find { it.id == _localPlayerId }?.seatIndex ?: -1

    // 房间事件
    private val _roomEvents = MutableSharedFlow<RoomEvent>(replay = 0, extraBufferCapacity = 16)
    val roomEvents: SharedFlow<RoomEvent> = _roomEvents.asSharedFlow()

    init {
        // 监听网络消息
        scope.launch {
            networkManager.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private suspend fun handleMessage(message: GameMessage) {
        DebugLogManager.d(TAG, "handleMessage: ${message::class.simpleName}")
        when (message) {
            is RoomCreated -> {
                DebugLogManager.i(TAG, "RoomCreated: roomCode=${message.room.roomCode}, hostId=${message.room.hostId}")
                _currentRoom.value = message.room
                _localPlayerId = message.room.hostId
                // 保存为重连令牌
                networkManager.setSessionToken(message.room.hostId)
                DebugLogManager.i(TAG, "Emitting RoomEvent.RoomCreated")
                _roomEvents.emit(RoomEvent.RoomCreated(message.room))
            }
            is RoomJoined -> {
                DebugLogManager.i(TAG, "RoomJoined: roomCode=${message.room.roomCode}, playerId=${message.playerId}")
                _currentRoom.value = message.room
                _localPlayerId = message.playerId
                // 保存为重连令牌
                networkManager.setSessionToken(message.playerId)
                _roomEvents.emit(RoomEvent.JoinedRoom(message.room))
            }
            is RoomUpdate -> {
                _currentRoom.value = message.room
                _roomEvents.emit(RoomEvent.RoomUpdated(message.room))
            }
            is GameStart -> {
                _roomEvents.emit(RoomEvent.GameStarting(message.state))
            }
            is PlayerDisconnected -> {
                _roomEvents.emit(RoomEvent.PlayerLeft(message.playerId, message.playerName))
            }
            is PlayerReconnected -> {
                _roomEvents.emit(RoomEvent.PlayerRejoined(message.playerId, message.playerName))
            }
            is ErrorMessage -> {
                _roomEvents.emit(RoomEvent.Error(message.code, message.message))
            }
            is RoomListResult -> {
                _roomList.value = message.rooms
            }
            else -> { /* 其他消息由其他管理器处理 */ }
        }
    }

    /**
     * 刷新房间列表
     */
    fun refreshRoomList(): Boolean {
        return networkManager.send(ListRooms())
    }

    /**
     * 创建房间
     */
    fun createRoom(playerName: String, roomName: String = "", maxPlayers: Int = 6): Boolean {
        return networkManager.send(CreateRoom(playerName, roomName, maxPlayers))
    }

    /**
     * 加入房间
     */
    fun joinRoom(roomCode: String, playerName: String): Boolean {
        return networkManager.send(JoinRoom(roomCode, playerName))
    }

    /**
     * 离开房间
     */
    fun leaveRoom(): Boolean {
        val room = _currentRoom.value ?: return false
        val result = networkManager.send(LeaveRoom(room.roomId))
        if (result) {
            _currentRoom.value = null
            _localPlayerId = null
        }
        return result
    }

    /**
     * 设置准备状态
     */
    fun setReady(isReady: Boolean): Boolean {
        return networkManager.send(PlayerReady(isReady))
    }

    /**
     * 踢出玩家（仅房主）
     */
    fun kickPlayer(playerId: String): Boolean {
        val room = _currentRoom.value ?: return false
        if (room.hostId != _localPlayerId) return false
        return networkManager.send(KickPlayer(playerId))
    }

    /**
     * 开始游戏（仅房主）
     */
    fun startGame(): Boolean {
        val room = _currentRoom.value ?: return false
        if (room.hostId != _localPlayerId) return false
        return networkManager.send(StartGameRequest(room.roomId))
    }

    /**
     * 是否是房主
     */
    fun isHost(): Boolean {
        val room = _currentRoom.value ?: return false
        return room.hostId == _localPlayerId
    }

    /**
     * 获取本地玩家在房间中的信息
     */
    fun getLocalPlayer(): RoomPlayer? {
        val room = _currentRoom.value ?: return null
        return room.players.find { it.id == _localPlayerId }
    }

    /**
     * 获取房间中的玩家数量
     */
    fun getPlayerCount(): Int {
        return _currentRoom.value?.players?.size ?: 0
    }

    /**
     * 获取所有准备好的玩家数量
     */
    fun getReadyCount(): Int {
        return _currentRoom.value?.players?.count { it.isReady } ?: 0
    }

    /**
     * 是否可以开始游戏
     */
    fun canStartGame(): Boolean {
        val room = _currentRoom.value ?: return false
        // 房主才能开始，至少1个真人玩家（其余可由AI填充）
        if (room.hostId != _localPlayerId) return false
        val humanPlayers = room.players.filter { !it.isAI }
        if (humanPlayers.isEmpty()) return false
        // 所有真人玩家都准备好了（房主自动算准备）
        return humanPlayers.all { it.isReady || it.id == room.hostId }
    }

    /**
     * 清理资源
     */
    fun release() {
        scope.cancel()
        _currentRoom.value = null
        _localPlayerId = null
    }
}

/**
 * 房间事件
 */
sealed class RoomEvent {
    data class RoomCreated(val room: RoomInfo) : RoomEvent()
    data class JoinedRoom(val room: RoomInfo) : RoomEvent()
    data class RoomUpdated(val room: RoomInfo) : RoomEvent()
    data class PlayerLeft(val playerId: String, val playerName: String) : RoomEvent()
    data class PlayerRejoined(val playerId: String, val playerName: String) : RoomEvent()
    data class GameStarting(val state: SerializedGameState) : RoomEvent()
    data class Error(val code: Int, val message: String) : RoomEvent()
}
