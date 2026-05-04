package com.communicationcard.server

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 服务端游戏管理器
 *
 * 核心职责：
 * - 游戏初始化：发牌、确定先手
 * - 动作验证：检查是否轮到该玩家、牌型是否合法
 * - 状态管理：更新手牌、计分、轮次
 * - AI逻辑：为AI玩家和断线玩家决策
 * - 回合计时：超时自动过牌
 *
 * 游戏规则：
 * - 使用两副牌（108张）
 * - 黑桃3先出
 * - 支持单张、对子、三张、炸弹、顺子
 * - 一队全部出完或达到200分结束
 */
class ServerGameManager(
    val roomManager: ServerRoomManager
) {
    companion object {
        private const val TURN_TIMEOUT_MS = 30_000L
        private const val AI_DELAY_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val turnTimers = ConcurrentHashMap<String, Job>()

    /**
     * 开始游戏
     */
    fun startGame(room: ServerRoom): ServerGameState {
        val deck = createDeck()
        val shuffled = deck.shuffled()

        val playerCount = room.players.size
        val cardsPerPlayer = shuffled.size / playerCount

        val hands = mutableMapOf<Int, MutableList<ServerCard>>()
        room.players.forEachIndexed { index, player ->
            val start = index * cardsPerPlayer
            val end = (start + cardsPerPlayer).coerceAtMost(shuffled.size)
            hands[player.seatIndex] = shuffled.subList(start, end).toMutableList()
        }

        // 找黑桃3的玩家先出
        val firstPlayer = hands.entries.find { entry ->
            entry.value.any { it.suit == "SPADE" && it.rank == "THREE" }
        }?.key ?: 0

        val gameState = ServerGameState(
            phase = "PLAYING",
            currentPlayerIndex = firstPlayer,
            hands = hands,
            lastPlayedGroup = null,
            lastPlayerId = null,
            consecutivePasses = 0,
            currentRoundScore = 0,
            teamAScore = 0,
            teamBScore = 0,
            finishOrder = mutableListOf(),
            version = 1L
        )

        room.gameState = gameState

        // 启动回合计时器
        startTurnTimer(room)

        // 如果第一个玩家是AI，自动出牌
        scope.launch {
            delay(500)
            checkAndProcessAITurn(room)
        }

        return gameState
    }

    /**
     * 处理玩家动作
     */
    fun handleAction(session: GameSession, action: PlayerAction): ActionResult {
        val room = roomManager.getRoom(session.roomId ?: "")
            ?: return ActionResult(false, "房间不存在")

        val gameState = room.gameState
            ?: return ActionResult(false, "游戏未开始")

        // 验证是否轮到该玩家
        if (session.seatIndex != gameState.currentPlayerIndex) {
            return ActionResult(false, "还没轮到你")
        }

        return when (action) {
            is PlayerAction.PlayCards -> handlePlayCards(room, action)
            is PlayerAction.Pass -> handlePass(room, action)
        }
    }

    private fun handlePlayCards(room: ServerRoom, action: PlayerAction.PlayCards): ActionResult {
        val state = room.gameState ?: return ActionResult(false, "游戏状态不存在")

        val hand = state.hands[action.playerId]
            ?: return ActionResult(false, "玩家手牌不存在")

        // 验证牌是否在手中
        val playedCards = action.cards.map { c ->
            hand.find { it.rank == c.rank && it.suit == c.suit }
                ?: return ActionResult(false, "你没有这张牌")
        }

        // 验证牌型
        val cardGroup = identifyCardGroup(playedCards)
            ?: return ActionResult(false, "无效的牌型")

        // 验证是否能压过上家
        if (state.lastPlayedGroup != null) {
            if (!canBeat(state.lastPlayedGroup!!, cardGroup)) {
                return ActionResult(false, "压不过上家")
            }
        }

        // 执行出牌
        playedCards.forEach { card -> hand.remove(card) }

        // 计算本轮得分
        val playScore = playedCards.sumOf { getCardScore(it) }
        state.currentRoundScore += playScore

        state.lastPlayedGroup = cardGroup
        state.lastPlayerId = action.playerId
        state.consecutivePasses = 0

        // 检查玩家是否出完
        var finishEvent: SerializedGameEvent? = null
        if (hand.isEmpty()) {
            state.finishOrder.add(action.playerId)
            finishEvent = SerializedGameEvent.PlayerFinished(
                action.playerId,
                state.finishOrder.size
            )
        }

        // 移动到下一个玩家
        moveToNextPlayer(room)
        state.version++

        // 重置回合计时器
        resetTurnTimer(room)

        val event = SerializedGameEvent.CardsPlayed(
            playerId = action.playerId,
            cardGroup = cardGroup.toSerialized()
        )

        // 检查游戏是否结束
        val gameResult = checkGameEnd(room)

        // 触发AI回合
        scope.launch {
            delay(AI_DELAY_MS)
            checkAndProcessAITurn(room)
        }

        return ActionResult(true, null, event, gameResult)
    }

    private fun handlePass(room: ServerRoom, action: PlayerAction.Pass): ActionResult {
        val state = room.gameState ?: return ActionResult(false, "游戏状态不存在")

        // 如果没有上家出牌，不能过
        if (state.lastPlayedGroup == null) {
            return ActionResult(false, "你是最大的，必须出牌")
        }

        state.consecutivePasses++

        // 计算还在打的玩家数
        val activePlayers = room.players.count { player ->
            state.hands[player.seatIndex]?.isNotEmpty() == true
        }

        // 如果所有其他人都过了，本轮结束
        if (state.consecutivePasses >= activePlayers - 1) {
            handleRoundEnd(room)
        }

        moveToNextPlayer(room)
        state.version++

        // 重置回合计时器
        resetTurnTimer(room)

        val event = SerializedGameEvent.PlayerPassed(action.playerId)

        // 检查游戏是否结束
        val gameResult = checkGameEnd(room)

        // 触发AI回合
        scope.launch {
            delay(AI_DELAY_MS)
            checkAndProcessAITurn(room)
        }

        return ActionResult(true, null, event, gameResult)
    }

    private fun handleRoundEnd(room: ServerRoom) {
        val state = room.gameState ?: return

        // 赢家收走本轮分数
        val winnerId = state.lastPlayerId ?: return
        val winnerTeam = room.players.find { it.seatIndex == winnerId }?.team

        if (winnerTeam == "TEAM_A") {
            state.teamAScore += state.currentRoundScore
        } else {
            state.teamBScore += state.currentRoundScore
        }

        // 重置本轮
        state.currentRoundScore = 0
        state.lastPlayedGroup = null
        state.lastPlayerId = null
        state.consecutivePasses = 0
    }

    private fun moveToNextPlayer(room: ServerRoom) {
        val state = room.gameState ?: return

        var next = (state.currentPlayerIndex + 1) % room.players.size
        var attempts = 0

        while (attempts < room.players.size) {
            val hand = state.hands[next]
            if (hand != null && hand.isNotEmpty()) {
                state.currentPlayerIndex = next
                return
            }
            next = (next + 1) % room.players.size
            attempts++
        }
    }

    private fun checkGameEnd(room: ServerRoom): SerializedGameResult? {
        val state = room.gameState ?: return null

        // 检查是否有一队全部出完
        val teamAPlayers = room.players.filter { it.team == "TEAM_A" }
        val teamBPlayers = room.players.filter { it.team == "TEAM_B" }

        val teamAFinished = teamAPlayers.all { state.hands[it.seatIndex]?.isEmpty() == true }
        val teamBFinished = teamBPlayers.all { state.hands[it.seatIndex]?.isEmpty() == true }

        if (teamAFinished) {
            // A队获胜，计算最终得分
            val loserHandScore = teamBPlayers.sumOf { player ->
                state.hands[player.seatIndex]?.sumOf { getCardScore(it) } ?: 0
            }
            state.teamAScore += loserHandScore

            return SerializedGameResult(
                winner = "TEAM_A",
                teamAScore = state.teamAScore,
                teamBScore = state.teamBScore,
                trigger = "TEAM_ALL_FINISHED"
            )
        }

        if (teamBFinished) {
            val loserHandScore = teamAPlayers.sumOf { player ->
                state.hands[player.seatIndex]?.sumOf { getCardScore(it) } ?: 0
            }
            state.teamBScore += loserHandScore

            return SerializedGameResult(
                winner = "TEAM_B",
                teamAScore = state.teamAScore,
                teamBScore = state.teamBScore,
                trigger = "TEAM_ALL_FINISHED"
            )
        }

        // 检查是否达到200分
        if (state.teamAScore >= 200) {
            return SerializedGameResult(
                winner = "TEAM_A",
                teamAScore = state.teamAScore,
                teamBScore = state.teamBScore,
                trigger = "SCORE_REACHED_200"
            )
        }

        if (state.teamBScore >= 200) {
            return SerializedGameResult(
                winner = "TEAM_B",
                teamAScore = state.teamAScore,
                teamBScore = state.teamBScore,
                trigger = "SCORE_REACHED_200"
            )
        }

        return null
    }

    /**
     * 获取玩家视角的游戏状态
     */
    fun getStateForPlayer(room: ServerRoom, playerSeatIndex: Int): SerializedGameState {
        val state = room.gameState ?: throw IllegalStateException("No game state")

        val players = room.players.map { player ->
            val hand = state.hands[player.seatIndex] ?: emptyList()
            val isMyself = player.seatIndex == playerSeatIndex

            SerializedPlayer(
                id = player.seatIndex,
                name = player.name,
                type = if (player.isAI) "AI" else "REMOTE",
                team = player.team,
                hand = if (isMyself) hand.map { it.toSerialized() } else emptyList(),
                handSize = hand.size,
                collectedScore = 0,
                hasFinished = hand.isEmpty(),
                finishOrder = state.finishOrder.indexOf(player.seatIndex).let { if (it >= 0) it + 1 else 0 },
                remoteId = player.id
            )
        }

        return SerializedGameState(
            phase = state.phase,
            currentPlayerIndex = state.currentPlayerIndex,
            players = players,
            lastPlayedGroup = state.lastPlayedGroup?.toSerialized(),
            lastPlayerId = state.lastPlayerId,
            roundWinnerId = null,
            consecutivePasses = state.consecutivePasses,
            currentRoundScore = state.currentRoundScore,
            teamAScore = state.teamAScore,
            teamBScore = state.teamBScore,
            version = state.version
        )
    }

    /**
     * 处理玩家断开连接
     */
    fun handlePlayerDisconnect(room: ServerRoom, player: ServerPlayer) {
        // AI接管，如果是当前玩家的回合，触发AI出牌
        val state = room.gameState ?: return
        if (state.currentPlayerIndex == player.seatIndex) {
            scope.launch {
                processAITurn(room, player.seatIndex)
            }
        }
    }

    // ========== AI逻辑 ==========

    private suspend fun checkAndProcessAITurn(room: ServerRoom) {
        val state = room.gameState ?: return
        val currentPlayer = room.players.find { it.seatIndex == state.currentPlayerIndex } ?: return

        if (currentPlayer.isAI || currentPlayer.isAISubstitute || !currentPlayer.isConnected) {
            processAITurn(room, currentPlayer.seatIndex)
        }
    }

    private suspend fun processAITurn(room: ServerRoom, playerIndex: Int) {
        val state = room.gameState ?: return
        val hand = state.hands[playerIndex] ?: return

        if (hand.isEmpty()) return

        delay(AI_DELAY_MS)

        val action = decideAIAction(hand, state.lastPlayedGroup, playerIndex)
        val result = when (action) {
            is PlayerAction.PlayCards -> handlePlayCards(room, action)
            is PlayerAction.Pass -> handlePass(room, action)
        }

        if (result.success) {
            // 广播给所有玩家
            room.players.forEach { player ->
                val playerState = getStateForPlayer(room, player.seatIndex)
                player.session?.send(GameActionResult(true, null, playerState))
            }

            result.event?.let { event ->
                room.players.forEach { player ->
                    player.session?.send(GameEventMessage(event))
                }
            }

            // 广播回合开始事件（除非游戏结束）
            if (result.gameResult == null) {
                val nextPlayerId = room.gameState?.currentPlayerIndex ?: 0
                room.players.forEach { player ->
                    player.session?.send(GameEventMessage(SerializedGameEvent.TurnStart(nextPlayerId)))
                }
            }

            result.gameResult?.let { gameResult ->
                room.status = RoomStatus.FINISHED
                room.players.forEach { player ->
                    player.session?.send(GameEnd(gameResult))
                }
            }
        }
    }

    private fun decideAIAction(hand: List<ServerCard>, lastPlay: ServerCardGroup?, playerId: Int): PlayerAction {
        if (lastPlay == null) {
            // 自由出牌：出最小的单张
            val smallest = hand.minByOrNull { getRankValue(it.rank) }!!
            return PlayerAction.PlayCards(playerId, listOf(smallest.toSerialized()))
        }

        // 尝试压牌
        val validPlays = findValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) {
            return PlayerAction.Pass(playerId)
        }

        // 选择最小的能压过的牌
        val bestPlay = validPlays.minByOrNull { group ->
            group.cards.sumOf { getRankValue(it.rank) }
        }!!

        return PlayerAction.PlayCards(playerId, bestPlay.cards.map { it.toSerialized() })
    }

    // ========== 回合计时器 ==========

    private fun startTurnTimer(room: ServerRoom) {
        turnTimers[room.roomId]?.cancel()

        turnTimers[room.roomId] = scope.launch {
            delay(TURN_TIMEOUT_MS)
            handleTurnTimeout(room)
        }
    }

    private fun resetTurnTimer(room: ServerRoom) {
        startTurnTimer(room)
    }

    private fun stopTurnTimer(room: ServerRoom) {
        turnTimers[room.roomId]?.cancel()
        turnTimers.remove(room.roomId)
    }

    private suspend fun handleTurnTimeout(room: ServerRoom) {
        val state = room.gameState ?: return
        val currentPlayerId = state.currentPlayerIndex

        // 广播超时
        room.players.forEach { player ->
            player.session?.send(TurnTimeout(currentPlayerId))
        }

        // 自动过牌或AI出牌
        processAITurn(room, currentPlayerId)
    }

    // ========== 牌组逻辑 ==========

    private fun createDeck(): List<ServerCard> {
        val cards = mutableListOf<ServerCard>()
        val suits = listOf("SPADE", "HEART", "DIAMOND", "CLUB")
        val ranks = listOf("THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "JACK", "QUEEN", "KING", "ACE", "TWO")

        for (deck in 0 until 4) { // 四副牌 (216张)
            for (suit in suits) {
                for (rank in ranks) {
                    cards.add(ServerCard(rank, suit, deck))
                }
            }
            // 大小王
            cards.add(ServerCard("SMALL_JOKER", "JOKER", deck))
            cards.add(ServerCard("BIG_JOKER", "JOKER", deck))
        }

        return cards
    }

    private fun identifyCardGroup(cards: List<ServerCard>): ServerCardGroup? {
        if (cards.isEmpty()) return null

        val size = cards.size
        val ranks = cards.map { it.rank }
        val uniqueRanks = ranks.toSet()

        return when {
            // 单张
            size == 1 -> ServerCardGroup(cards, "SINGLE", cards[0].rank)

            // 对子
            size == 2 && uniqueRanks.size == 1 -> ServerCardGroup(cards, "PAIR", cards[0].rank)

            // 三张
            size == 3 && uniqueRanks.size == 1 -> ServerCardGroup(cards, "TRIPLE", cards[0].rank)

            // 炸弹 (4张或以上相同)
            size >= 4 && uniqueRanks.size == 1 -> ServerCardGroup(cards, "BOMB", cards[0].rank)

            // 顺子 (5张或以上连续)
            size >= 5 && isStraight(cards) -> ServerCardGroup(cards, "STRAIGHT", cards.maxByOrNull { getRankValue(it.rank) }!!.rank)

            else -> null
        }
    }

    private fun isStraight(cards: List<ServerCard>): Boolean {
        val values = cards.map { getRankValue(it.rank) }.sorted()
        if (values.any { it >= 13 }) return false // 2和王不能顺

        for (i in 1 until values.size) {
            if (values[i] != values[i - 1] + 1) return false
        }
        return true
    }

    private fun canBeat(last: ServerCardGroup, current: ServerCardGroup): Boolean {
        // 炸弹可以压任何非炸弹
        if (current.type == "BOMB" && last.type != "BOMB") return true

        // 相同类型相同数量才能比
        if (current.type != last.type) return false
        if (current.cards.size != last.cards.size) return false

        return getRankValue(current.primaryRank) > getRankValue(last.primaryRank)
    }

    private fun findValidPlays(hand: List<ServerCard>, lastPlay: ServerCardGroup): List<ServerCardGroup> {
        val result = mutableListOf<ServerCardGroup>()

        // 根据上家牌型找对应的牌
        when (lastPlay.type) {
            "SINGLE" -> {
                hand.filter { getRankValue(it.rank) > getRankValue(lastPlay.primaryRank) }
                    .forEach { result.add(ServerCardGroup(listOf(it), "SINGLE", it.rank)) }
            }
            "PAIR" -> {
                hand.groupBy { it.rank }
                    .filter { it.value.size >= 2 && getRankValue(it.key) > getRankValue(lastPlay.primaryRank) }
                    .forEach { result.add(ServerCardGroup(it.value.take(2), "PAIR", it.key)) }
            }
            "TRIPLE" -> {
                hand.groupBy { it.rank }
                    .filter { it.value.size >= 3 && getRankValue(it.key) > getRankValue(lastPlay.primaryRank) }
                    .forEach { result.add(ServerCardGroup(it.value.take(3), "TRIPLE", it.key)) }
            }
            "BOMB" -> {
                val bombSize = lastPlay.cards.size
                hand.groupBy { it.rank }
                    .filter { it.value.size >= bombSize && getRankValue(it.key) > getRankValue(lastPlay.primaryRank) }
                    .forEach { result.add(ServerCardGroup(it.value.take(bombSize), "BOMB", it.key)) }
                // 更大的炸弹
                hand.groupBy { it.rank }
                    .filter { it.value.size > bombSize }
                    .forEach { result.add(ServerCardGroup(it.value, "BOMB", it.key)) }
            }
        }

        // 炸弹可以压任何牌
        if (lastPlay.type != "BOMB") {
            hand.groupBy { it.rank }
                .filter { it.value.size >= 4 }
                .forEach { result.add(ServerCardGroup(it.value, "BOMB", it.key)) }
        }

        return result
    }

    private fun getRankValue(rank: String): Int {
        return when (rank) {
            "THREE" -> 0
            "FOUR" -> 1
            "FIVE" -> 2
            "SIX" -> 3
            "SEVEN" -> 4
            "EIGHT" -> 5
            "NINE" -> 6
            "TEN" -> 7
            "JACK" -> 8
            "QUEEN" -> 9
            "KING" -> 10
            "ACE" -> 11
            "TWO" -> 12
            "SMALL_JOKER" -> 13
            "BIG_JOKER" -> 14
            else -> -1
        }
    }

    private fun getCardScore(card: ServerCard): Int {
        return when (card.rank) {
            "FIVE" -> 5
            "TEN", "KING" -> 10
            else -> 0
        }
    }
}

/**
 * 服务端游戏状态
 */
class ServerGameState(
    var phase: String,
    var currentPlayerIndex: Int,
    val hands: MutableMap<Int, MutableList<ServerCard>>,
    var lastPlayedGroup: ServerCardGroup?,
    var lastPlayerId: Int?,
    var consecutivePasses: Int,
    var currentRoundScore: Int,
    var teamAScore: Int,
    var teamBScore: Int,
    val finishOrder: MutableList<Int>,
    var version: Long
)

/**
 * 服务端牌
 */
data class ServerCard(
    val rank: String,
    val suit: String,
    val deckIndex: Int
) {
    fun toSerialized(): SerializedCard = SerializedCard(rank, suit, deckIndex)
}

/**
 * 服务端牌组
 */
data class ServerCardGroup(
    val cards: List<ServerCard>,
    val type: String,
    val primaryRank: String
) {
    fun toSerialized(): SerializedCardGroup = SerializedCardGroup(
        cards = cards.map { it.toSerialized() },
        type = type,
        primaryRank = primaryRank
    )
}

/**
 * 动作结果
 */
data class ActionResult(
    val success: Boolean,
    val error: String? = null,
    val event: SerializedGameEvent? = null,
    val gameResult: SerializedGameResult? = null
)
