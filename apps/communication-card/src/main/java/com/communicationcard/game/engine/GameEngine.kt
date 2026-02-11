package com.communicationcard.game.engine

import com.communicationcard.game.ai.AIDifficulty
import com.communicationcard.game.ai.AIPlayer
import com.communicationcard.game.ai.GameStateInfo
import com.communicationcard.game.model.*

/**
 * 游戏阶段
 */
enum class GamePhase {
    NOT_STARTED,    // 游戏未开始
    DEALING,        // 发牌中
    PLAYING,        // 游戏进行中
    ROUND_END,      // 一轮结束
    GAME_OVER       // 游戏结束
}

/**
 * 结算触发方式
 */
enum class SettlementTrigger {
    TEAM_ALL_FINISHED,      // 全队走完触发
    SCORE_REACHED_200       // 得分达标触发（提前结算）
}

/**
 * 游戏结果
 */
data class GameResult(
    val winner: Team?,                  // 获胜队伍，null表示平局
    val teamAScore: Int,                // A队最终得分
    val teamBScore: Int,                // B队最终得分
    val trigger: SettlementTrigger,     // 触发结算的方式
    val triggerPlayer: Player?          // 触发结算的玩家
)

/**
 * 一轮出牌记录
 */
data class PlayRecord(
    val player: Player,
    val cardGroup: CardGroup?,          // null表示过牌
    val isPassed: Boolean
)

/**
 * 游戏事件
 */
sealed class GameEvent {
    data class CardsDealt(val playerCount: Int) : GameEvent()
    data class TurnStart(val player: Player) : GameEvent()
    data class CardsPlayed(val player: Player, val cardGroup: CardGroup) : GameEvent()
    data class PlayerPassed(val player: Player) : GameEvent()
    data class RoundWon(val player: Player, val cards: List<Card>, val score: Int) : GameEvent()
    data class PlayerFinished(val player: Player, val order: Int) : GameEvent()
    data class ScoreUpdate(val teamAScore: Int, val teamBScore: Int) : GameEvent()
    data class GameEnded(val result: GameResult) : GameEvent()
    data class AICommunication(val player: Player, val message: String) : GameEvent()
}

/**
 * 游戏引擎 - 管理整个游戏流程
 */
class GameEngine(
    private val playerCount: Int = 6,
    private val aiDifficulty: AIDifficulty = AIDifficulty.MEDIUM
) {
    // 玩家列表
    private val _players = mutableListOf<Player>()
    val players: List<Player> get() = _players.toList()

    // 队伍信息
    lateinit var teamA: TeamInfo
        private set
    lateinit var teamB: TeamInfo
        private set

    // 牌组
    private val deck = Deck()

    // 游戏状态
    var gamePhase: GamePhase = GamePhase.NOT_STARTED
        private set

    // 当前轮次
    private var currentPlayerIndex: Int = 0
    private var lastPlayedGroup: CardGroup? = null
    private var lastPlayerId: Int? = null
    private var roundWinnerId: Int? = null
    private var consecutivePasses: Int = 0

    // 当前轮次出的所有牌（用于计算轮次得分）
    private val currentRoundCards = mutableListOf<Card>()

    // AI决策引擎
    private val aiPlayer = AIPlayer(aiDifficulty)

    // 事件监听器
    private val eventListeners = mutableListOf<(GameEvent) -> Unit>()

    // 走完顺序
    private var finishOrder = 0

    // 人类玩家
    var humanPlayer: Player? = null
        private set

    /**
     * 添加事件监听器
     */
    fun addEventListener(listener: (GameEvent) -> Unit) {
        eventListeners.add(listener)
    }

    /**
     * 发送事件
     */
    private fun emitEvent(event: GameEvent) {
        eventListeners.forEach { it(event) }
    }

    /**
     * 初始化游戏
     */
    fun initializeGame() {
        require(playerCount in listOf(6, 8, 10, 12)) { "玩家数量必须是6、8、10或12人" }

        _players.clear()

        // 创建玩家：交替分配到两队
        // 玩家0是人类玩家，属于A队
        val teamAPlayers = mutableListOf<Player>()
        val teamBPlayers = mutableListOf<Player>()

        for (i in 0 until playerCount) {
            val team = if (i % 2 == 0) Team.TEAM_A else Team.TEAM_B
            val type = if (i == 0) PlayerType.HUMAN else PlayerType.AI
            val name = if (i == 0) "你" else "玩家${i + 1}"

            val player = Player(
                id = i,
                name = name,
                type = type,
                team = team
            )
            _players.add(player)

            if (team == Team.TEAM_A) {
                teamAPlayers.add(player)
            } else {
                teamBPlayers.add(player)
            }

            if (type == PlayerType.HUMAN) {
                humanPlayer = player
            }
        }

        teamA = TeamInfo(Team.TEAM_A, teamAPlayers)
        teamB = TeamInfo(Team.TEAM_B, teamBPlayers)

        gamePhase = GamePhase.NOT_STARTED
    }

    /**
     * 开始新游戏
     */
    fun startGame() {
        // 重置所有玩家状态
        _players.forEach { it.reset() }
        finishOrder = 0

        // 发牌
        gamePhase = GamePhase.DEALING
        val hands = deck.deal(playerCount)

        for (i in 0 until playerCount) {
            _players[i].setInitialHand(hands[i])
        }

        emitEvent(GameEvent.CardsDealt(playerCount))

        // 随机选择首家
        currentPlayerIndex = (0 until playerCount).random()
        lastPlayedGroup = null
        lastPlayerId = null
        roundWinnerId = null
        consecutivePasses = 0
        currentRoundCards.clear()

        gamePhase = GamePhase.PLAYING
        emitEvent(GameEvent.TurnStart(_players[currentPlayerIndex]))
    }

    /**
     * 获取当前玩家
     */
    fun getCurrentPlayer(): Player = _players[currentPlayerIndex]

    /**
     * 获取上一手牌
     */
    fun getLastPlay(): CardGroup? = lastPlayedGroup

    /**
     * 人类玩家出牌
     */
    fun humanPlay(cards: List<Card>): Boolean {
        val player = getCurrentPlayer()
        if (player.type != PlayerType.HUMAN) return false
        if (player.hasFinished) return false

        // 验证牌型
        val cardGroup = CardRules.identifyCardGroup(cards) ?: return false

        // 验证是否能压过上家
        if (!CardRules.canBeat(lastPlayedGroup, cardGroup)) return false

        // 验证玩家是否有这些牌
        if (!player.hasCards(cards)) return false

        // 执行出牌
        executePlay(player, cardGroup)
        return true
    }

    /**
     * 人类玩家过牌
     */
    fun humanPass(): Boolean {
        val player = getCurrentPlayer()
        if (player.type != PlayerType.HUMAN) return false

        // 如果没有上家出牌（自由出牌），不能过牌
        if (lastPlayedGroup == null) return false

        executePass(player)
        return true
    }

    /**
     * AI执行回合
     */
    fun executeAITurn(): CardGroup? {
        val player = getCurrentPlayer()
        if (player.type != PlayerType.AI) return null
        if (player.hasFinished) {
            moveToNextPlayer()
            return null
        }

        val gameStateInfo = buildGameStateInfo()
        val decision = aiPlayer.makeDecision(player, lastPlayedGroup, gameStateInfo)

        if (decision != null) {
            executePlay(player, decision)
        } else {
            // AI过牌，但如果是自由出牌必须出牌
            if (lastPlayedGroup == null) {
                // 强制出最小的牌
                val validPlays = CardRules.findValidPlays(player.hand, null)
                val smallestPlay = validPlays.minByOrNull { it.primaryRank.value }
                if (smallestPlay != null) {
                    executePlay(player, smallestPlay)
                    return smallestPlay
                }
            }
            executePass(player)
        }

        return decision
    }

    /**
     * 执行出牌
     */
    private fun executePlay(player: Player, cardGroup: CardGroup) {
        // 从手牌中移除
        player.playCards(cardGroup.cards)

        // 更新状态
        lastPlayedGroup = cardGroup
        lastPlayerId = player.id
        roundWinnerId = player.id
        consecutivePasses = 0
        currentRoundCards.addAll(cardGroup.cards)

        emitEvent(GameEvent.CardsPlayed(player, cardGroup))

        // 检查玩家是否走完
        if (player.hasFinished) {
            finishOrder++
            emitEvent(GameEvent.PlayerFinished(player, finishOrder))

            // 检查结算条件
            if (checkSettlement()) {
                return
            }
        }

        moveToNextPlayer()
    }

    /**
     * 执行过牌
     */
    private fun executePass(player: Player) {
        consecutivePasses++
        emitEvent(GameEvent.PlayerPassed(player))

        // 检查是否所有人都过牌（轮次结束）
        val activePlayers = _players.count { !it.hasFinished }
        if (consecutivePasses >= activePlayers - 1) {
            endRound()
            return
        }

        moveToNextPlayer()
    }

    /**
     * 轮次结束
     */
    private fun endRound() {
        val winner = _players.find { it.id == roundWinnerId }
        if (winner != null) {
            // 赢家收取所有牌
            winner.collectCards(currentRoundCards.toList())
            val roundScore = CardRules.calculateScore(currentRoundCards)

            emitEvent(GameEvent.RoundWon(winner, currentRoundCards.toList(), roundScore))
            emitEvent(GameEvent.ScoreUpdate(
                teamA.finishedPlayersScore,
                teamB.finishedPlayersScore
            ))

            // 检查结算条件
            if (checkSettlement()) {
                return
            }

            // 赢家成为下一轮首家
            currentPlayerIndex = winner.id
        }

        // 重置轮次状态
        lastPlayedGroup = null
        lastPlayerId = null
        consecutivePasses = 0
        currentRoundCards.clear()

        // 如果当前玩家已走完，移到下一个未走完的玩家
        if (_players[currentPlayerIndex].hasFinished) {
            moveToNextPlayer()
        } else {
            emitEvent(GameEvent.TurnStart(_players[currentPlayerIndex]))
        }
    }

    /**
     * 移动到下一个玩家
     */
    private fun moveToNextPlayer() {
        var attempts = 0
        do {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerCount
            attempts++
        } while (_players[currentPlayerIndex].hasFinished && attempts < playerCount)

        if (attempts < playerCount) {
            emitEvent(GameEvent.TurnStart(_players[currentPlayerIndex]))
        }
    }

    /**
     * 检查结算条件
     * @return true 如果游戏结束
     */
    private fun checkSettlement(): Boolean {
        // 触发方式1：全队走完
        if (teamA.allFinished || teamB.allFinished) {
            settleGame(SettlementTrigger.TEAM_ALL_FINISHED)
            return true
        }

        // 触发方式2：已走完玩家已收分≥200
        if (teamA.finishedPlayersScore >= 200) {
            settleGame(SettlementTrigger.SCORE_REACHED_200)
            return true
        }
        if (teamB.finishedPlayersScore >= 200) {
            settleGame(SettlementTrigger.SCORE_REACHED_200)
            return true
        }

        return false
    }

    /**
     * 结算游戏
     */
    private fun settleGame(trigger: SettlementTrigger) {
        gamePhase = GamePhase.GAME_OVER

        val (teamAScore, teamBScore) = when (trigger) {
            SettlementTrigger.TEAM_ALL_FINISHED -> {
                calculateTeamAllFinishedScores()
            }
            SettlementTrigger.SCORE_REACHED_200 -> {
                calculateScoreReachedScores()
            }
        }

        val winner = when {
            teamAScore >= 200 && teamBScore >= 200 -> {
                if (teamAScore > teamBScore) Team.TEAM_A
                else if (teamBScore > teamAScore) Team.TEAM_B
                else null  // 平局
            }
            teamAScore >= 200 -> Team.TEAM_A
            teamBScore >= 200 -> Team.TEAM_B
            teamAScore > teamBScore -> Team.TEAM_A
            teamBScore > teamAScore -> Team.TEAM_B
            else -> null  // 平局
        }

        val result = GameResult(
            winner = winner,
            teamAScore = teamAScore,
            teamBScore = teamBScore,
            trigger = trigger,
            triggerPlayer = null
        )

        emitEvent(GameEvent.GameEnded(result))
    }

    /**
     * 计算全队走完时的得分
     * 走完队得分 = 本队已走完玩家已收分 + 对方未走完玩家已收分 + 对方未走完玩家手牌分
     * 未走完队得分 = 本队已走完玩家已收分（如无人走完则为0）
     */
    private fun calculateTeamAllFinishedScores(): Pair<Int, Int> {
        return if (teamA.allFinished) {
            val teamAScore = teamA.finishedPlayersScore +
                    teamB.unfinishedCollectedScore +
                    teamB.unfinishedHandScore
            val teamBScore = teamB.finishedPlayersScore
            Pair(teamAScore, teamBScore)
        } else {
            val teamBScore = teamB.finishedPlayersScore +
                    teamA.unfinishedCollectedScore +
                    teamA.unfinishedHandScore
            val teamAScore = teamA.finishedPlayersScore
            Pair(teamAScore, teamBScore)
        }
    }

    /**
     * 计算得分达标时的得分
     * 该队立即判定胜出
     * 对方得分为其已走完玩家的已收分
     */
    private fun calculateScoreReachedScores(): Pair<Int, Int> {
        return if (teamA.finishedPlayersScore >= 200) {
            Pair(teamA.finishedPlayersScore, teamB.finishedPlayersScore)
        } else {
            Pair(teamA.finishedPlayersScore, teamB.finishedPlayersScore)
        }
    }

    /**
     * 构建游戏状态信息（供AI使用）
     */
    private fun buildGameStateInfo(): GameStateInfo {
        // 构建从当前玩家开始的出牌顺序
        val playerOrder = mutableListOf<Int>()
        var idx = currentPlayerIndex
        for (i in 0 until playerCount) {
            playerOrder.add(_players[idx].id)
            idx = (idx + 1) % playerCount
        }

        return GameStateInfo(
            currentPlayerId = currentPlayerIndex,
            lastPlayerId = lastPlayerId,
            lastPlayerHandSize = lastPlayerId?.let { _players[it].handSize } ?: 0,
            currentRoundScore = CardRules.calculateScore(currentRoundCards),
            teamAFinishedScore = teamA.finishedPlayersScore,
            teamBFinishedScore = teamB.finishedPlayersScore,
            teamAAllFinished = teamA.allFinished,
            teamBAllFinished = teamB.allFinished,
            playerTeams = _players.associate { it.id to it.team },
            playerHandSizes = _players.associate { it.id to it.handSize },
            playerOrder = playerOrder,
            teamATotalScore = teamA.totalCollectedScore,
            teamBTotalScore = teamB.totalCollectedScore
        )
    }

    /**
     * 获取人类玩家可出的牌
     */
    fun getValidPlaysForHuman(): List<CardGroup> {
        val human = humanPlayer ?: return emptyList()
        return CardRules.findValidPlays(human.hand, lastPlayedGroup)
    }

    /**
     * 检查人类玩家是否可以过牌
     */
    fun canHumanPass(): Boolean {
        return lastPlayedGroup != null
    }

    /**
     * 获取玩家信息
     */
    fun getPlayerInfo(playerId: Int): Player? = _players.find { it.id == playerId }

    /**
     * 获取当前轮次的最大牌（领先玩家ID和牌组）
     */
    fun getCurrentWinningPlay(): Pair<Int, CardGroup>? {
        val winnerId = roundWinnerId ?: return null
        val winningCards = lastPlayedGroup ?: return null
        return Pair(winnerId, winningCards)
    }

    /**
     * 获取当前轮次的总分
     */
    fun getCurrentRoundScore(): Int {
        return CardRules.calculateScore(currentRoundCards)
    }
}
