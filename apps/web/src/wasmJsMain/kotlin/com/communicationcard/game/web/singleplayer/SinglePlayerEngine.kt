package com.communicationcard.game.web.singleplayer

import com.communicationcard.game.ai.AIDifficulty
import com.communicationcard.game.engine.GameEngine
import com.communicationcard.game.engine.GameEvent
import com.communicationcard.game.engine.GamePhase
import com.communicationcard.game.model.Card
import com.communicationcard.game.model.CardGroup
import com.communicationcard.game.model.Player
import com.communicationcard.game.model.PlayerType
import com.communicationcard.game.network.SerializedCard
import com.communicationcard.game.network.SerializedCardGroup
import com.communicationcard.game.network.SerializedGameResult
import com.communicationcard.game.network.SerializedGameState
import com.communicationcard.game.network.SerializedPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单机模式引擎适配器：
 *
 * - 内部用 :shared 的 [GameEngine]（与 Android 单机模式同一份代码）驱动游戏
 * - 把每次状态变化映射成 [SerializedGameState]，与联网模式共用同一套渲染层
 * - 自动驱动 AI 出牌（带短延迟，模拟人类节奏）
 *
 * 注：[GameEngine] 的多个轮次状态字段（lastPlayerId / consecutivePasses 等）目前是
 * private，Web UI 渲染用不到的字段在此暂以默认值占位；后续如需可在 :shared 增公共
 * 访问器（同步加给 Android 端）。
 */
class SinglePlayerEngine(
    private val playerCount: Int = 6,
    aiDifficulty: AIDifficulty = AIDifficulty.MEDIUM,
    /**
     * AI 出牌之间的延迟（毫秒），由 [com.communicationcard.game.web.storage.UserPreferences.gameSpeed]
     * 决定。50ms = 极速、800ms = 正常、1500ms = 慢。
     */
    private val aiDelayMs: Long = AI_TURN_DELAY_DEFAULT_MS,
) {
    private val engine = GameEngine(playerCount, aiDifficulty)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<SerializedGameState?>(null)
    val state: StateFlow<SerializedGameState?> = _state.asStateFlow()

    private val _gameEnd = MutableSharedFlow<SerializedGameResult>(replay = 1)
    val gameEnd: SharedFlow<SerializedGameResult> = _gameEnd.asSharedFlow()

    /** 人类玩家固定为座位 0（GameEngine.initializeGame 默认安排）。 */
    val humanSeatIndex: Int get() = 0

    private var version = 1L
    private var consecutivePassCount = 0

    init {
        engine.addEventListener { event ->
            when (event) {
                is GameEvent.GameEnded -> {
                    val r = event.result
                    publishState()
                    val serialized = SerializedGameResult(
                        winner = r.winner?.name,
                        teamAScore = r.teamAScore,
                        teamBScore = r.teamBScore,
                        trigger = r.trigger.name,
                    )
                    scope.launch { _gameEnd.emit(serialized) }
                }
                is GameEvent.PlayerPassed -> {
                    consecutivePassCount++
                    publishState()
                    scope.launch { driveAiIfNeeded() }
                }
                is GameEvent.RoundWon -> {
                    consecutivePassCount = 0
                    publishState()
                    scope.launch { driveAiIfNeeded() }
                }
                else -> {
                    publishState()
                    scope.launch { driveAiIfNeeded() }
                }
            }
        }
    }

    fun start() {
        engine.initializeGame()
        engine.startGame()
        publishState()
        scope.launch { driveAiIfNeeded() }
    }

    fun humanPlay(cards: List<SerializedCard>): Boolean {
        val human = engine.humanPlayer ?: return false
        val toPlay = cards.mapNotNull { ser -> human.hand.find { it.matches(ser) } }
        if (toPlay.size != cards.size) return false
        return engine.humanPlay(toPlay)
    }

    fun humanPass(): Boolean = engine.humanPass()

    /**
     * 让所有 AI 玩家依次出牌，直到轮到人类或游戏结束。
     */
    private suspend fun driveAiIfNeeded() {
        while (engine.gamePhase == GamePhase.PLAYING) {
            val current = engine.getCurrentPlayer()
            if (current.type == PlayerType.HUMAN) return
            if (current.hasFinished) {
                // 已走完的玩家会被 GameEngine 内部自动跳过；保险起见短停一下让事件循环喘口气
                delay(50)
                continue
            }
            delay(aiDelayMs)
            engine.executeAITurn()
            publishState()
        }
    }

    private fun publishState() {
        _state.value = buildSerializedState()
    }

    private fun buildSerializedState(): SerializedGameState {
        version++
        val current = runCatching { engine.getCurrentPlayer().id }.getOrDefault(0)
        val players = engine.players.map { it.toSerialized(showHand = it.type == PlayerType.HUMAN) }
        val last = engine.getLastPlay()?.toSerialized()
        return SerializedGameState(
            phase = engine.gamePhase.name,
            currentPlayerIndex = current,
            players = players,
            lastPlayedGroup = last,
            lastPlayerId = null,
            roundWinnerId = null,
            consecutivePasses = consecutivePassCount,
            currentRoundScore = 0,
            teamAScore = engine.teamA.totalCollectedScore,
            teamBScore = engine.teamB.totalCollectedScore,
            version = version,
        )
    }

    companion object {
        private const val AI_TURN_DELAY_DEFAULT_MS = 800L
    }
}

private fun Card.matches(ser: SerializedCard): Boolean =
    rank.name == ser.rank && suit.name == ser.suit && deckIndex == ser.deckIndex

private fun Card.toSerialized(): SerializedCard =
    SerializedCard(rank = rank.name, suit = suit.name, deckIndex = deckIndex)

private fun CardGroup.toSerialized(): SerializedCardGroup =
    SerializedCardGroup(
        cards = cards.map { it.toSerialized() },
        type = type.name,
        primaryRank = primaryRank.name,
    )

private fun Player.toSerialized(showHand: Boolean): SerializedPlayer = SerializedPlayer(
    id = id,
    name = name,
    type = type.name,
    team = team.name,
    hand = if (showHand) hand.map { it.toSerialized() } else emptyList(),
    handSize = hand.size,
    collectedScore = collectedScore,
    hasFinished = hasFinished,
    // 单机模式当前不追踪 finishOrder（仅用于结算 UI 友好显示）；UI 主要看 hasFinished
    finishOrder = 0,
    remoteId = null,
)
