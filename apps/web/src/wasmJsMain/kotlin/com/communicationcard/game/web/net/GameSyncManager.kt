package com.communicationcard.game.web.net

import com.communicationcard.game.network.GameAction
import com.communicationcard.game.network.GameActionResult
import com.communicationcard.game.network.GameEnd
import com.communicationcard.game.network.GameEventMessage
import com.communicationcard.game.network.GameMessage
import com.communicationcard.game.network.GameStart
import com.communicationcard.game.network.GameSync
import com.communicationcard.game.network.PlayerAction
import com.communicationcard.game.network.ReconnectSuccess
import com.communicationcard.game.network.SerializedCard
import com.communicationcard.game.network.SerializedGameEvent
import com.communicationcard.game.network.SerializedGameResult
import com.communicationcard.game.network.SerializedGameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 游戏状态同步管理器（联网模式）。与 Android 端 GameSyncManager 等价。
 *
 * - 持有当前 [SerializedGameState]
 * - 转发动作 / 事件 / 结束消息给 UI
 * - 带版本号校验，丢弃过期状态（避免乱序更新覆盖最新状态）
 */
class GameSyncManager(private val net: NetworkClient) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _gameState = MutableStateFlow<SerializedGameState?>(null)
    val gameState: StateFlow<SerializedGameState?> = _gameState.asStateFlow()

    private val _events = MutableSharedFlow<SerializedGameEvent>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<SerializedGameEvent> = _events.asSharedFlow()

    private val _actionResults = MutableSharedFlow<GameActionResult>(replay = 0, extraBufferCapacity = 8)
    val actionResults: SharedFlow<GameActionResult> = _actionResults.asSharedFlow()

    private val _gameEnd = MutableSharedFlow<SerializedGameResult>(replay = 1)
    val gameEnd: SharedFlow<SerializedGameResult> = _gameEnd.asSharedFlow()

    /** 本地玩家座位号；服务器在 GameStart 时通过"哪个玩家手牌不为空"反推。 */
    private val _localSeatIndex = MutableStateFlow(-1)
    val localSeatIndex: StateFlow<Int> = _localSeatIndex.asStateFlow()

    init {
        scope.launch {
            net.messages.collect { msg -> handleMessage(msg) }
        }
    }

    private suspend fun handleMessage(message: GameMessage) {
        when (message) {
            is GameStart -> {
                applyState(message.state)
                if (_localSeatIndex.value < 0) findLocalSeat(message.state)
            }
            is GameSync -> {
                applyState(message.state)
                if (_localSeatIndex.value < 0) findLocalSeat(message.state)
            }
            is GameActionResult -> {
                _actionResults.emit(message)
                if (message.success && message.state != null) applyState(message.state!!)
            }
            is GameEventMessage -> _events.emit(message.event)
            is GameEnd -> _gameEnd.emit(message.result)
            is ReconnectSuccess -> {
                // Codex P1 on PR #59：自动重连后服务端推回当前 game state（如果重连
                // 时正在游戏中）。state 为 null 表示玩家在大厅或房间内但游戏未开始，
                // 由 RoomManager 的 RoomUpdate 路径恢复 UI；此处仅恢复游戏中状态。
                message.state?.let { newState ->
                    applyState(newState)
                    if (_localSeatIndex.value < 0) findLocalSeat(newState)
                }
            }
            else -> { /* 房间消息由 RoomManager 处理 */ }
        }
    }

    private fun applyState(newState: SerializedGameState) {
        val current = _gameState.value
        // 版本号校验：旧的不要覆盖新的
        if (current != null && newState.version < current.version) return
        _gameState.value = newState
    }

    private fun findLocalSeat(state: SerializedGameState) {
        val mine = state.players.find { it.hand.isNotEmpty() }
        _localSeatIndex.value = mine?.id ?: -1
    }

    /** 当前是否轮到自己。 */
    fun isMyTurn(): Boolean {
        val s = _gameState.value ?: return false
        return s.currentPlayerIndex == _localSeatIndex.value
    }

    fun playCards(cards: List<SerializedCard>): Boolean {
        val seat = _localSeatIndex.value
        if (seat < 0) return false
        return net.send(GameAction(PlayerAction.PlayCards(seat, cards)))
    }

    fun pass(): Boolean {
        val seat = _localSeatIndex.value
        if (seat < 0) return false
        return net.send(GameAction(PlayerAction.Pass(seat)))
    }
}
