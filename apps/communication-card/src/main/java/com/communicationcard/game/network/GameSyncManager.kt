package com.communicationcard.game.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 游戏状态同步管理器
 *
 * 职责：
 * - 监听NetworkManager的游戏消息
 * - 维护游戏状态Flow（供UI订阅）
 * - 发送玩家动作到服务器
 * - 管理回合计时器显示
 *
 * 数据流：
 * - gameState: 当前游戏状态
 * - gameEvents: 游戏事件（出牌、过牌、完成等）
 * - actionResults: 动作执行结果
 * - turnTimeRemaining: 回合剩余时间
 * - gameEnd: 游戏结束结果
 */
class GameSyncManager(
    private val networkManager: NetworkManager
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 当前游戏状态
    private val _gameState = MutableStateFlow<SerializedGameState?>(null)
    val gameState: StateFlow<SerializedGameState?> = _gameState.asStateFlow()

    // 游戏事件
    private val _gameEvents = MutableSharedFlow<SerializedGameEvent>(replay = 0, extraBufferCapacity = 16)
    val gameEvents: SharedFlow<SerializedGameEvent> = _gameEvents.asSharedFlow()

    // 动作结果
    private val _actionResults = MutableSharedFlow<GameActionResult>(replay = 0, extraBufferCapacity = 8)
    val actionResults: SharedFlow<GameActionResult> = _actionResults.asSharedFlow()

    // 回合超时
    private val _turnTimeouts = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
    val turnTimeouts: SharedFlow<Int> = _turnTimeouts.asSharedFlow()

    // 游戏结束
    private val _gameEnd = MutableSharedFlow<SerializedGameResult>(replay = 1)
    val gameEnd: SharedFlow<SerializedGameResult> = _gameEnd.asSharedFlow()

    // 本地玩家座位号
    var localSeatIndex: Int = -1
        private set

    // 回合计时器
    private val _turnTimeRemaining = MutableStateFlow(30)
    val turnTimeRemaining: StateFlow<Int> = _turnTimeRemaining.asStateFlow()

    private var turnTimerJob: Job? = null

    init {
        observeMessages()
    }

    private fun observeMessages() {
        scope.launch {
            networkManager.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private suspend fun handleMessage(message: GameMessage) {
        when (message) {
            is GameStart -> {
                applyState(message.state)
                if (localSeatIndex < 0) {
                    findLocalSeatIndex(message.state)
                }
                startTurnTimer()
            }

            is GameSync -> {
                applyState(message.state)
                if (localSeatIndex < 0) {
                    findLocalSeatIndex(message.state)
                }
                resetTurnTimer()
            }

            is ReconnectSuccess -> {
                message.state?.let {
                    applyState(it)
                    if (localSeatIndex < 0) {
                        findLocalSeatIndex(it)
                    }
                    resetTurnTimer()
                }
            }

            is GameActionResult -> {
                _actionResults.emit(message)
                if (message.success && message.state != null) {
                    applyState(message.state)
                    resetTurnTimer()
                }
            }

            is GameEventMessage -> {
                _gameEvents.emit(message.event)
            }

            is TurnTimeout -> {
                _turnTimeouts.emit(message.playerId)
            }

            is GameEnd -> {
                _gameEnd.emit(message.result)
                stopTurnTimer()
            }

            else -> { /* 其他消息由其他管理器处理 */ }
        }
    }

    /**
     * 应用新状态（带版本号校验，丢弃过期更新）
     */
    private fun applyState(newState: SerializedGameState) {
        val current = _gameState.value
        if (current != null && newState.version < current.version) {
            // 过期状态丢弃
            return
        }
        _gameState.value = newState
    }

    private fun findLocalSeatIndex(state: SerializedGameState) {
        // 本地玩家是hand不为空的那个
        val localPlayer = state.players.find { it.hand.isNotEmpty() }
        localSeatIndex = localPlayer?.id ?: -1
    }

    /**
     * 发送出牌动作
     */
    fun playCards(cards: List<SerializedCard>) {
        if (localSeatIndex < 0) return

        val action = PlayerAction.PlayCards(localSeatIndex, cards)
        networkManager.send(GameAction(action))
    }

    /**
     * 发送过牌动作
     */
    fun pass() {
        if (localSeatIndex < 0) return

        val action = PlayerAction.Pass(localSeatIndex)
        networkManager.send(GameAction(action))
    }

    /**
     * 请求完整状态同步
     */
    fun requestSync() {
        // 通过发送心跳触发状态同步
        networkManager.send(Heartbeat(System.currentTimeMillis()))
    }

    /**
     * 检查是否轮到本地玩家
     */
    fun isMyTurn(): Boolean {
        val state = _gameState.value
        val currentIdx = state?.currentPlayerIndex
        val result = currentIdx == localSeatIndex
        // 调试：如果返回false但看起来应该是自己的回合
        if (!result && localSeatIndex >= 0) {
            println("DEBUG isMyTurn: currentPlayerIndex=$currentIdx, localSeatIndex=$localSeatIndex, result=$result")
        }
        return result
    }

    /**
     * 获取本地玩家手牌
     */
    fun getMyHand(): List<SerializedCard> {
        val state = _gameState.value ?: return emptyList()
        return state.players.find { it.id == localSeatIndex }?.hand ?: emptyList()
    }

    /**
     * 获取当前出牌
     */
    fun getLastPlay(): SerializedCardGroup? {
        return _gameState.value?.lastPlayedGroup
    }

    /**
     * 检查是否可以过牌
     */
    fun canPass(): Boolean {
        return _gameState.value?.lastPlayedGroup != null
    }

    // ========== 回合计时器 ==========

    private fun startTurnTimer() {
        turnTimerJob?.cancel()
        _turnTimeRemaining.value = 30

        turnTimerJob = scope.launch {
            while (_turnTimeRemaining.value > 0) {
                delay(1000)
                _turnTimeRemaining.value = _turnTimeRemaining.value - 1
            }
        }
    }

    private fun resetTurnTimer() {
        _turnTimeRemaining.value = 30
        startTurnTimer()
    }

    private fun stopTurnTimer() {
        turnTimerJob?.cancel()
        turnTimerJob = null
    }

    /**
     * 设置初始游戏状态（从Intent传入）
     */
    fun setInitialState(state: SerializedGameState, seatIndex: Int) {
        _gameState.value = state
        localSeatIndex = seatIndex
        startTurnTimer()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopTurnTimer()
        scope.cancel()
    }
}
