package com.communicationcard.game.engine

import com.communicationcard.game.model.*
import com.communicationcard.game.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 多人游戏引擎适配器
 *
 * 设计原则：与GameEngine提供相同的公共API，使UI代码可以无缝切换
 *
 * 重要：所有状态读取都直接从 gameSyncManager.gameState 获取，
 * 避免缓存导致的竞态条件问题。
 */
class MultiplayerGameEngine(
    private val gameSyncManager: GameSyncManager,
    private val localPlayerId: Int
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 事件监听器
    private val eventListeners = mutableListOf<(GameEvent) -> Unit>()

    init {
        observeEvents()
    }

    private fun observeEvents() {
        scope.launch {
            gameSyncManager.gameEvents.collect { event ->
                // 直接从当前状态构建事件，避免使用缓存
                val gameEvent = deserializeEvent(event)
                eventListeners.forEach { it(gameEvent) }
            }
        }

        scope.launch {
            gameSyncManager.gameEnd.collect { result ->
                val gameResult = GameResult(
                    winner = when (result.winner) {
                        "TEAM_A" -> Team.TEAM_A
                        "TEAM_B" -> Team.TEAM_B
                        else -> null
                    },
                    teamAScore = result.teamAScore,
                    teamBScore = result.teamBScore,
                    trigger = if (result.trigger == "TEAM_ALL_FINISHED")
                        SettlementTrigger.TEAM_ALL_FINISHED
                    else
                        SettlementTrigger.SCORE_REACHED_200,
                    triggerPlayer = null
                )
                eventListeners.forEach { it(GameEvent.GameEnded(gameResult)) }
            }
        }

        // 监听状态变化，通知UI刷新
        scope.launch {
            gameSyncManager.gameState.filterNotNull().collect { _ ->
                // 状态变化时通知监听器刷新（通过发送一个刷新事件）
                eventListeners.forEach { it(GameEvent.StateRefresh) }
            }
        }
    }

    // ========== 公共API (与GameEngine兼容) ==========

    // 直接从当前状态读取玩家列表
    val players: List<Player>
        get() = gameSyncManager.gameState.value?.players?.map { deserializePlayer(it) } ?: emptyList()

    val gamePhase: GamePhase
        get() = gameSyncManager.gameState.value?.phase?.let { GamePhase.valueOf(it) } ?: GamePhase.NOT_STARTED

    val teamA: TeamInfo
        get() = TeamInfo(Team.TEAM_A, players.filter { it.team == Team.TEAM_A })

    val teamB: TeamInfo
        get() = TeamInfo(Team.TEAM_B, players.filter { it.team == Team.TEAM_B })

    fun addEventListener(listener: (GameEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (GameEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun getCurrentPlayer(): Player? {
        val state = gameSyncManager.gameState.value ?: return null
        return players.find { it.id == state.currentPlayerIndex }
    }

    fun getLastPlay(): CardGroup? {
        return gameSyncManager.gameState.value?.lastPlayedGroup?.let { deserializeCardGroup(it) }
    }

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
        player.setHandSizeOverride(sp.handSize)
        player.setHasFinishedOverride(sp.hasFinished)
        player.setCollectedScoreOverride(sp.collectedScore)
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
            type = CardGroupType.valueOf(scg.type)
        )
    }

    private fun deserializeEvent(event: SerializedGameEvent): GameEvent {
        // 直接从当前状态获取玩家列表，确保数据最新
        val currentPlayers = players

        return when (event) {
            is SerializedGameEvent.CardsDealt -> GameEvent.CardsDealt(event.playerCount)
            is SerializedGameEvent.TurnStart -> {
                val player = currentPlayers.find { it.id == event.playerId } ?: currentPlayers.first()
                GameEvent.TurnStart(player)
            }
            is SerializedGameEvent.CardsPlayed -> {
                val player = currentPlayers.find { it.id == event.playerId } ?: currentPlayers.first()
                GameEvent.CardsPlayed(player, deserializeCardGroup(event.cardGroup))
            }
            is SerializedGameEvent.PlayerPassed -> {
                val player = currentPlayers.find { it.id == event.playerId } ?: currentPlayers.first()
                GameEvent.PlayerPassed(player)
            }
            is SerializedGameEvent.RoundWon -> {
                val player = currentPlayers.find { it.id == event.playerId } ?: currentPlayers.first()
                GameEvent.RoundWon(player, emptyList(), event.score)
            }
            is SerializedGameEvent.PlayerFinished -> {
                val player = currentPlayers.find { it.id == event.playerId } ?: currentPlayers.first()
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
