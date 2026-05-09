package com.communicationcard.game.web.viewmodel

import com.communicationcard.game.network.SerializedCard
import com.communicationcard.game.web.net.GameSyncManager
import com.communicationcard.game.web.net.NetworkClient
import com.communicationcard.game.web.net.RoomManager
import com.communicationcard.game.web.singleplayer.SinglePlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 应用顶层状态机。
 *
 * - 持有 [Screen] StateFlow 供 App.kt 渲染
 * - 协调单机引擎 / 联网管理器
 * - 把异步事件（房间更新、游戏状态、结算）映射到屏幕切换
 *
 * 设计原则：所有跨屏幕的状态都集中在这里；屏幕 Composable 只接收一个 Screen 数据类。
 */
class AppViewModel {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // ---------- 联网管理器（懒加载，进入联网模式时才创建） ----------
    private var net: NetworkClient? = null
    private var room: RoomManager? = null
    private var sync: GameSyncManager? = null

    // ---------- 单机引擎（懒加载） ----------
    private var single: SinglePlayerEngine? = null

    // ---------- 用户偏好（暂存内存；后续可以接 localStorage） ----------
    private var nickname = "玩家"
    private var serverUrl = defaultServerUrl()

    // ============================================================
    //  入口动作
    // ============================================================

    fun startSinglePlayer() {
        val engine = SinglePlayerEngine(playerCount = 6).also { single = it }
        engine.start()
        scope.launch {
            engine.state.collect { state ->
                if (state == null) return@collect
                _screen.value = Screen.Game(
                    state = state,
                    localSeatIndex = engine.humanSeatIndex,
                    mode = Screen.Game.Mode.SinglePlayer,
                    selectedCardIds = currentSelection(),
                )
            }
        }
        scope.launch {
            engine.gameEnd.collect { result ->
                _screen.value = Screen.Settlement(result, Screen.Game.Mode.SinglePlayer)
            }
        }
    }

    fun startMultiplayer() {
        _screen.value = Screen.Lobby(
            serverUrl = serverUrl,
            nickname = nickname,
            connectionState = com.communicationcard.game.web.net.WebSocketTransport.State.Disconnected,
            rooms = emptyList(),
        )
    }

    fun goHome() {
        net?.close()
        net = null
        room = null
        sync = null
        single = null
        _screen.value = Screen.Home
    }

    // ============================================================
    //  Lobby
    // ============================================================

    fun setServerUrl(url: String) {
        serverUrl = url
        updateLobby { it.copy(serverUrl = url) }
    }

    fun setNickname(name: String) {
        nickname = name
        updateLobby { it.copy(nickname = name) }
    }

    fun connectServer() {
        val n = NetworkClient(serverUrl).also { net = it }
        val r = RoomManager(n).also { room = it }
        val s = GameSyncManager(n).also { sync = it }

        // 监听连接状态
        scope.launch {
            n.connectionState.collect { st ->
                updateLobby { it.copy(connectionState = st) }
                if (st == com.communicationcard.game.web.net.WebSocketTransport.State.Connected) {
                    r.refreshRoomList()
                }
            }
        }

        // 监听房间列表
        scope.launch {
            r.roomList.collect { list -> updateLobby { it.copy(rooms = list) } }
        }

        // 监听 currentRoom 变化 -> 进入 Room 屏幕
        scope.launch {
            combine(r.currentRoom, r.localPlayerId) { cr, pid -> cr to pid }
                .collect { (cr, pid) ->
                    if (cr != null && pid != null) {
                        if (cr.status == com.communicationcard.game.network.RoomStatus.IN_GAME) {
                            // 房间进入对局后，等 GameSync/GameStart 推到 Game 屏幕
                        } else {
                            val isReady = cr.players.find { it.id == pid }?.isReady ?: false
                            _screen.value = Screen.Room(cr, pid, isReady)
                        }
                    }
                }
        }

        // 监听联网游戏状态 -> 进入 Game 屏幕
        scope.launch {
            sync!!.gameState.collect { state ->
                if (state == null) return@collect
                _screen.value = Screen.Game(
                    state = state,
                    localSeatIndex = sync!!.localSeatIndex.value,
                    mode = Screen.Game.Mode.Multiplayer,
                    selectedCardIds = currentSelection(),
                )
            }
        }

        // 监听游戏结束
        scope.launch {
            sync!!.gameEnd.collect { result ->
                _screen.value = Screen.Settlement(result, Screen.Game.Mode.Multiplayer)
            }
        }

        n.connect()
    }

    fun createRoom() {
        room?.createRoom(playerName = nickname)
    }

    fun joinRoom(code: String) {
        room?.joinRoom(roomCode = code, playerName = nickname)
    }

    fun refreshRooms() {
        room?.refreshRoomList()
    }

    // ============================================================
    //  Room
    // ============================================================

    fun toggleReady() {
        val current = (_screen.value as? Screen.Room) ?: return
        val newReady = !current.isReady
        room?.setReady(newReady)
        _screen.value = current.copy(isReady = newReady)
    }

    fun startGame() {
        room?.startGame()
    }

    fun leaveRoom() {
        room?.leaveRoom()
        _screen.value = Screen.Lobby(
            serverUrl = serverUrl,
            nickname = nickname,
            connectionState = net?.connectionState?.value
                ?: com.communicationcard.game.web.net.WebSocketTransport.State.Disconnected,
            rooms = emptyList(),
        )
        room?.refreshRoomList()
    }

    // ============================================================
    //  Game
    // ============================================================

    fun playCards(cards: List<SerializedCard>) {
        when (val s = _screen.value) {
            is Screen.Game -> when (s.mode) {
                Screen.Game.Mode.Multiplayer -> sync?.playCards(cards)
                Screen.Game.Mode.SinglePlayer -> single?.humanPlay(cards)
            }
            else -> Unit
        }
    }

    fun pass() {
        when (val s = _screen.value) {
            is Screen.Game -> when (s.mode) {
                Screen.Game.Mode.Multiplayer -> sync?.pass()
                Screen.Game.Mode.SinglePlayer -> single?.humanPass()
            }
            else -> Unit
        }
    }

    fun hint() {
        // TODO: 实现提示功能（调 CardRules.findValidPlays 选最优）
    }

    fun leaveGame() {
        goHome()
    }

    // ============================================================
    //  Settlement
    // ============================================================

    fun playAgain() {
        when ((_screen.value as? Screen.Settlement)?.mode) {
            Screen.Game.Mode.SinglePlayer -> startSinglePlayer()
            Screen.Game.Mode.Multiplayer -> _screen.value = Screen.Lobby(
                serverUrl = serverUrl,
                nickname = nickname,
                connectionState = net?.connectionState?.value
                    ?: com.communicationcard.game.web.net.WebSocketTransport.State.Disconnected,
                rooms = emptyList(),
            )
            null -> Unit
        }
    }

    // ============================================================
    //  helpers
    // ============================================================

    private fun currentSelection(): Set<String> = (_screen.value as? Screen.Game)?.selectedCardIds ?: emptySet()

    private inline fun updateLobby(transform: (Screen.Lobby) -> Screen.Lobby) {
        val current = _screen.value as? Screen.Lobby ?: return
        _screen.value = transform(current)
    }
}

/** 默认服务器：当前页面 host 的 ws:// 端口 8080 路径 /game。 */
private fun defaultServerUrl(): String {
    val proto = currentProtocol()
    val host = currentHost()
    val wsScheme = if (proto == "https:") "wss" else "ws"
    val hostPart = host.ifBlank { "localhost" }
    return "$wsScheme://$hostPart:8080/game"
}

@JsFun("() => window.location.protocol")
private external fun currentProtocol(): String

@JsFun("() => window.location.hostname")
private external fun currentHost(): String
