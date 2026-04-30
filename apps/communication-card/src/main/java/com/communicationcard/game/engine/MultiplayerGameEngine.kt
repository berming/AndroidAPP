package com.communicationcard.game.engine

import com.communicationcard.game.model.*
import com.communicationcard.game.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 多人游戏引擎适配器
 * 提供与GameEngine相同的公共API，但底层通过网络同步
 */
class MultiplayerGameEngine(
    private val gameSyncManager: GameSyncManager,
    private val localPlayerId: Int
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 事件监听器
    private val eventListeners = mutableListOf<(GameEvent) -> Unit>()

    // 本地状态缓存
    private var cachedPlayers: List<Player> = emptyList()
    private var cachedGamePhase: GamePhase = GamePhase.NOT_STARTED
    private var cachedLastPlay: CardGroup? = null

    init {
        observeState()
        observeEvents()
    }

    private fun observeState() {
        scope.launch {
            gameSyncManager.gameState.filterNotNull().collect { state ->
                updateFromState(state)
            }
        }
    }

    private fun observeEvents() {
        scope.launch {
            gameSyncManager.gameEvents.collect { event ->
                val gameEvent = deserializeEvent(event)
                eventListeners.forEach { it(gameEvent) }
            }
        }

        scope.launch {
            gameSyncManager.gameEnd.collect { result ->
                val gameResult = GameResult(
                    winner = if (result.winner == "TEAM_A") Team.TEAM_A else Team.TEAM_B,
                    teamAScore = result.teamAScore,
                    teamBScore = result.teamBScore,
                    trigger = if (result.trigger == "TEAM_ALL_FINISHED")
                        SettlementTrigger.TEAM_ALL_FINISHED
                    else
                        SettlementTrigger.SCORE_REACHED_200
                )
                eventListeners.forEach { it(GameEvent.GameEnded(gameResult)) }
            }
        }
    }

    private fun updateFromState(state: SerializedGameState) {
        cachedPlayers = state.players.map { deserializePlayer(it) }
        cachedGamePhase = GamePhase.valueOf(state.phase)
        cachedLastPlay = state.lastPlayedGroup?.let { deserializeCardGroup(it) }
    }

    // ========== 公共API (与GameEngine兼容) ==========

    val players: List<Player>
        get() = cachedPlayers

    val gamePhase: GamePhase
        get() = cachedGamePhase

    val teamA: TeamInfo
        get() = TeamInfo(Team.TEAM_A, cachedPlayers.filter { it.team == Team.TEAM_A })

    val teamB: TeamInfo
        get() = TeamInfo(Team.TEAM_B, cachedPlayers.filter { it.team == Team.TEAM_B })

    fun addEventListener(listener: (GameEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (GameEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun getCurrentPlayer(): Player? {
        val state = gameSyncManager.gameState.value ?: return null
        return cachedPlayers.getOrNull(state.currentPlayerIndex)
    }

    fun getLastPlay(): CardGroup? = cachedLastPlay

    fun isMyTurn(): Boolean = gameSyncManager.isMyTurn()

    fun getMyHand(): List<Card> {
        val serializedHand = gameSyncManager.getMyHand()
        return serializedHand.map { deserializeCard(it) }
    }

    /**
     * 出牌 - 发送到服务器
     */
    fun humanPlay(cards: List<Card>): Boolean {
        if (!isMyTurn()) return false
        if (cards.isEmpty()) return false

        val serializedCards = cards.map { serializeCard(it) }
        gameSyncManager.playCards(serializedCards)
        return true // 乐观响应，实际结果通过事件返回
    }

    /**
     * 过牌 - 发送到服务器
     */
    fun humanPass(): Boolean {
        if (!isMyTurn()) return false
        if (!gameSyncManager.canPass()) return false

        gameSyncManager.pass()
        return true
    }

    /**
     * 获取有效出牌（本地计算用于UI提示）
     */
    fun getValidPlaysForHuman(): List<CardGroup> {
        val hand = getMyHand()
        val lastPlay = getLastPlay()
        return CardRules.findValidPlays(hand, lastPlay)
    }

    fun canHumanPass(): Boolean = gameSyncManager.canPass()

    // ========== 状态查询 ==========

    val currentRoundScore: Int
        get() = gameSyncManager.gameState.value?.currentRoundScore ?: 0

    val teamAScore: Int
        get() = gameSyncManager.gameState.value?.teamAScore ?: 0

    val teamBScore: Int
        get() = gameSyncManager.gameState.value?.teamBScore ?: 0

    val turnTimeRemaining: StateFlow<Int>
        get() = gameSyncManager.turnTimeRemaining

    // ========== 序列化/反序列化 ==========

    private fun deserializePlayer(sp: SerializedPlayer): Player {
        val player = Player(
            id = sp.id,
            name = sp.name,
            type = PlayerType.valueOf(sp.type),
            team = Team.valueOf(sp.team),
            remoteId = sp.remoteId
        )
        player.setInitialHand(sp.hand.map { deserializeCard(it) })
        return player
    }

    private fun deserializeCard(sc: SerializedCard): Card {
        return Card(
            rank = CardRank.valueOf(sc.rank),
            suit = CardSuit.valueOf(sc.suit),
            deckIndex = sc.deckIndex
        )
    }

    private fun deserializeCardGroup(scg: SerializedCardGroup): CardGroup {
        return CardGroup(
            cards = scg.cards.map { deserializeCard(it) },
            type = CardGroupType.valueOf(scg.type),
            primaryRank = CardRank.valueOf(scg.primaryRank)
        )
    }

    private fun deserializeEvent(event: SerializedGameEvent): GameEvent {
        return when (event) {
            is SerializedGameEvent.CardsDealt -> GameEvent.CardsDealt(event.playerCount)
            is SerializedGameEvent.TurnStart -> {
                val player = cachedPlayers.getOrNull(event.playerId) ?: cachedPlayers.first()
                GameEvent.TurnStart(player)
            }
            is SerializedGameEvent.CardsPlayed -> {
                val player = cachedPlayers.getOrNull(event.playerId) ?: cachedPlayers.first()
                GameEvent.CardsPlayed(player, deserializeCardGroup(event.cardGroup))
            }
            is SerializedGameEvent.PlayerPassed -> {
                val player = cachedPlayers.getOrNull(event.playerId) ?: cachedPlayers.first()
                GameEvent.PlayerPassed(player)
            }
            is SerializedGameEvent.RoundWon -> {
                val player = cachedPlayers.getOrNull(event.playerId) ?: cachedPlayers.first()
                GameEvent.RoundWon(player, emptyList(), event.score)
            }
            is SerializedGameEvent.PlayerFinished -> {
                val player = cachedPlayers.getOrNull(event.playerId) ?: cachedPlayers.first()
                GameEvent.PlayerFinished(player, event.order)
            }
            is SerializedGameEvent.ScoreUpdate -> {
                GameEvent.ScoreUpdate(event.teamAScore, event.teamBScore)
            }
        }
    }

    private fun serializeCard(card: Card): SerializedCard {
        return SerializedCard(
            rank = card.rank.name,
            suit = card.suit.name,
            deckIndex = card.deckIndex
        )
    }

    /**
     * 释放资源
     */
    fun release() {
        eventListeners.clear()
        scope.cancel()
    }
}
