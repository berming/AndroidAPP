package com.communicationcard.server

import com.communicationcard.game.engine.CardRules
import com.communicationcard.game.engine.SettlementCalculator
import com.communicationcard.game.engine.SettlementCalculator.PlayerSettlementState
import com.communicationcard.game.engine.SettlementCalculator.TeamSettlementState
import com.communicationcard.game.model.Card
import com.communicationcard.game.model.CardGroup
import com.communicationcard.game.model.CardGroupType
import com.communicationcard.game.model.CardRank
import com.communicationcard.game.model.CardSuit
import com.communicationcard.game.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - 使用四副牌（216张）
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
    // 每个房间的动作互斥锁，确保同一房间的动作不会并发执行（防止 hand/state 竞态）
    private val roomMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(room: ServerRoom): Mutex =
        roomMutexes.getOrPut(room.roomId) { Mutex() }

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

        // 找黑桃3的玩家先出（使用任意有黑桃3的座位号；找不到则取最小座位号）
        val firstPlayer = hands.entries.find { entry ->
            entry.value.any { it.suit == "SPADE" && it.rank == "THREE" }
        }?.key ?: hands.keys.minOrNull() ?: 0

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
    suspend fun handleAction(session: GameSession, action: PlayerAction): ActionResult {
        val room = roomManager.getRoom(session.roomId ?: "")
            ?: return ActionResult(false, "房间不存在")

        if (room.status != RoomStatus.IN_GAME) {
            return ActionResult(false, "游戏未在进行中")
        }

        // 通过互斥锁串行化每个房间的动作，避免并发的 PlayCards/Pass + AI 行动相互踩踏
        return mutexFor(room).withLock {
            val gameState = room.gameState
                ?: return@withLock ActionResult(false, "游戏未开始")

            // 验证是否轮到该玩家
            if (session.seatIndex != gameState.currentPlayerIndex) {
                return@withLock ActionResult(false, "还没轮到你")
            }

            // 防止客户端伪造 playerId
            val actionPlayerId = when (action) {
                is PlayerAction.PlayCards -> action.playerId
                is PlayerAction.Pass -> action.playerId
            }
            if (actionPlayerId != session.seatIndex) {
                return@withLock ActionResult(false, "动作的座位号不匹配")
            }

            when (action) {
                is PlayerAction.PlayCards -> handlePlayCards(room, action)
                is PlayerAction.Pass -> handlePass(room, action)
            }
        }
    }

    private fun handlePlayCards(room: ServerRoom, action: PlayerAction.PlayCards): ActionResult {
        val state = room.gameState ?: return ActionResult(false, "游戏状态不存在")

        val hand = state.hands[action.playerId]
            ?: return ActionResult(false, "玩家手牌不存在")

        if (action.cards.isEmpty()) {
            return ActionResult(false, "未选中任何牌")
        }

        // 验证牌是否在手中（包含 deckIndex 匹配，避免多副牌中重复匹配同一张）
        val handCopy = hand.toMutableList()
        val playedCards = mutableListOf<ServerCard>()
        for (c in action.cards) {
            val idx = handCopy.indexOfFirst {
                it.rank == c.rank && it.suit == c.suit && it.deckIndex == c.deckIndex
            }
            if (idx == -1) {
                // 兼容客户端 deckIndex 不准时退化匹配 rank+suit
                val fallbackIdx = handCopy.indexOfFirst {
                    it.rank == c.rank && it.suit == c.suit
                }
                if (fallbackIdx == -1) return ActionResult(false, "你没有这张牌")
                playedCards.add(handCopy.removeAt(fallbackIdx))
            } else {
                playedCards.add(handCopy.removeAt(idx))
            }
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

        // 执行出牌：用已扣除后的 handCopy 替换 hand 内容
        hand.clear()
        hand.addAll(handCopy)

        // 计算本轮得分
        val playScore = playedCards.sumOf { getCardScore(it) }
        state.currentRoundScore += playScore

        state.lastPlayedGroup = cardGroup
        state.lastPlayerId = action.playerId
        state.consecutivePasses = 0

        // 检查玩家是否出完
        var finishEvent: SerializedGameEvent.PlayerFinished? = null
        if (hand.isEmpty() && !state.finishOrder.contains(action.playerId)) {
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

        // 触发AI回合（仅在游戏未结束时）
        if (gameResult == null) {
            scope.launch {
                delay(AI_DELAY_MS)
                checkAndProcessAITurn(room)
            }
        } else {
            stopTurnTimer(room)
        }

        return ActionResult(true, null, event, gameResult, finishEvent = finishEvent)
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

        val passEvent = SerializedGameEvent.PlayerPassed(action.playerId)
        var roundEndEvent: SerializedGameEvent.RoundWon? = null

        // 如果所有其他人都过了，本轮结束
        if (state.consecutivePasses >= activePlayers - 1) {
            roundEndEvent = handleRoundEnd(room)
            // handleRoundEnd 已将 currentPlayerIndex 设置为赢家
            // 如果赢家已走完，跳到下一位仍在打的玩家
            if (state.hands[state.currentPlayerIndex]?.isEmpty() != false) {
                moveToNextPlayer(room)
            }
        } else {
            moveToNextPlayer(room)
        }

        state.version++

        // 重置回合计时器
        resetTurnTimer(room)

        // 检查游戏是否结束
        val gameResult = checkGameEnd(room)

        // 触发AI回合（仅在游戏未结束时）
        if (gameResult == null) {
            scope.launch {
                delay(AI_DELAY_MS)
                checkAndProcessAITurn(room)
            }
        } else {
            stopTurnTimer(room)
        }

        return ActionResult(true, null, passEvent, gameResult, roundEndEvent)
    }

    private fun handleRoundEnd(room: ServerRoom): SerializedGameEvent.RoundWon? {
        val state = room.gameState ?: return null

        // 赢家收走本轮分数
        val winnerId = state.lastPlayerId ?: return null
        val winnerTeam = room.players.find { it.seatIndex == winnerId }?.team
        val score = state.currentRoundScore

        if (winnerTeam == "TEAM_A") {
            state.teamAScore += score
        } else {
            state.teamBScore += score
        }

        // 同时累加到该玩家的"已收分"（与单机版一致）
        state.playerScores[winnerId] = (state.playerScores[winnerId] ?: 0) + score

        // 赢家成为下一轮首家
        state.currentPlayerIndex = winnerId

        // 重置本轮
        state.currentRoundScore = 0
        state.lastPlayedGroup = null
        state.lastPlayerId = null
        state.consecutivePasses = 0

        return SerializedGameEvent.RoundWon(winnerId, score)
    }

    private fun moveToNextPlayer(room: ServerRoom) {
        val state = room.gameState ?: return

        // 使用游戏开始时确定的座位列表（hands 的所有键），按座位号排序
        // 这样即便 room.players 中有玩家被移除，回合推进仍然准确
        val seats = state.hands.keys.sorted()
        if (seats.isEmpty()) return

        val currentIdx = seats.indexOf(state.currentPlayerIndex)
        val startOffset = if (currentIdx >= 0) 1 else 0
        val baseIdx = if (currentIdx >= 0) currentIdx else 0

        for (offset in startOffset..seats.size) {
            val nextSeat = seats[(baseIdx + offset) % seats.size]
            if (state.hands[nextSeat]?.isNotEmpty() == true) {
                state.currentPlayerIndex = nextSeat
                return
            }
        }
    }

    private fun checkGameEnd(room: ServerRoom): SerializedGameResult? {
        val state = room.gameState ?: return null

        val teamAPlayers = room.players.filter { it.team == "TEAM_A" }
        val teamBPlayers = room.players.filter { it.team == "TEAM_B" }

        // 注意：空队不能判定为"全部出完"（vacuous truth），必须至少有一个玩家
        val teamAFinished = teamAPlayers.isNotEmpty() &&
            teamAPlayers.all { state.hands[it.seatIndex]?.isEmpty() == true }
        val teamBFinished = teamBPlayers.isNotEmpty() &&
            teamBPlayers.all { state.hands[it.seatIndex]?.isEmpty() == true }

        // === TEAM_ALL_FINISHED 触发：与单机版 SettlementCalculator 保持一致 ===
        // 赢方得分 = 赢方所有已收 + 输方未走完玩家的已收 + 输方未走完玩家的剩余手牌分
        // 输方得分 = 输方已走完玩家的已收
        if (teamAFinished) {
            val (a, b) = computeAllFinishedScores(state, winner = teamAPlayers, loser = teamBPlayers)
            state.teamAScore = a
            state.teamBScore = b
            return SerializedGameResult(
                winner = "TEAM_A",
                teamAScore = a,
                teamBScore = b,
                trigger = "TEAM_ALL_FINISHED"
            )
        }

        if (teamBFinished) {
            val (b, a) = computeAllFinishedScores(state, winner = teamBPlayers, loser = teamAPlayers)
            state.teamAScore = a
            state.teamBScore = b
            return SerializedGameResult(
                winner = "TEAM_B",
                teamAScore = a,
                teamBScore = b,
                trigger = "TEAM_ALL_FINISHED"
            )
        }

        // === SCORE_REACHED_200 触发：只算已走完玩家的"已收"分 ===
        val teamAFinishedScore = teamAPlayers
            .filter { state.hands[it.seatIndex]?.isEmpty() == true }
            .sumOf { state.playerScores[it.seatIndex] ?: 0 }
        val teamBFinishedScore = teamBPlayers
            .filter { state.hands[it.seatIndex]?.isEmpty() == true }
            .sumOf { state.playerScores[it.seatIndex] ?: 0 }

        if (teamAFinishedScore >= 200) {
            return SerializedGameResult(
                winner = "TEAM_A",
                teamAScore = teamAFinishedScore,
                teamBScore = teamBFinishedScore,
                trigger = "SCORE_REACHED_200"
            )
        }
        if (teamBFinishedScore >= 200) {
            return SerializedGameResult(
                winner = "TEAM_B",
                teamAScore = teamAFinishedScore,
                teamBScore = teamBFinishedScore,
                trigger = "SCORE_REACHED_200"
            )
        }

        return null
    }

    /**
     * TEAM_ALL_FINISHED 结算：返回 (winnerScore, loserScore)
     */
    // PR-H3 stage 3：委托给 :shared 的 SettlementCalculator。
    // 旧实现（保留三个月零回归的 15 用例之一）等价于 SettlementCalculator
    // TEAM_ALL_FINISHED 分支：赢方 = 赢方已收 + 输方未走完玩家(已收+手牌分)；
    // 输方 = 输方已走完玩家的已收。
    //
    // 调用前提：调用方（checkGameEnd）已确认有一队全员走完——即此处的 `winner`
    // 列表 isFinished=true ∀ player。SettlementCalculator.calculate 检测到
    // TEAM_ALL_FINISHED 触发后返回非 null。
    //
    // 可见性：保持 internal 让 ServerGameManagerTest 能直接覆盖
    // （CLAUDE.md 第三章；docs/regressions.md #4 #8 防回归）。
    internal fun computeAllFinishedScores(
        state: ServerGameState,
        winner: List<ServerPlayer>,
        loser: List<ServerPlayer>
    ): Pair<Int, Int> {
        val winnerTeam = winner.toSettlementState(state)
        val loserTeam = loser.toSettlementState(state)
        val result = SettlementCalculator.calculate(winnerTeam, loserTeam)
            ?: error(
                "computeAllFinishedScores 进入时应有一队全员走完——SettlementCalculator " +
                    "返回 null 表示触发条件未满足。检查 checkGameEnd 的前置条件。"
            )
        return Pair(result.teamAScore, result.teamBScore)
    }

    /** Server 队伍 → :shared 结算输入。 */
    private fun List<ServerPlayer>.toSettlementState(state: ServerGameState): TeamSettlementState =
        TeamSettlementState(
            players = map { p ->
                val hand = state.hands[p.seatIndex] ?: emptyList()
                PlayerSettlementState(
                    isFinished = hand.isEmpty(),
                    collectedScore = state.playerScores[p.seatIndex] ?: 0,
                    handScore = hand.sumOf { getCardScore(it) },
                )
            }
        )

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
                collectedScore = state.playerScores[player.seatIndex] ?: 0,
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
        if (room.status != RoomStatus.IN_GAME) return
        val state = room.gameState ?: return
        if (state.phase != "PLAYING") return
        val currentPlayer = room.players.find { it.seatIndex == state.currentPlayerIndex } ?: return

        if (currentPlayer.isAI || currentPlayer.isAISubstitute || !currentPlayer.isConnected) {
            processAITurn(room, currentPlayer.seatIndex)
        }
    }

    private suspend fun processAITurn(room: ServerRoom, playerIndex: Int) {
        if (room.status != RoomStatus.IN_GAME) return
        val state0 = room.gameState ?: return
        if (state0.phase != "PLAYING") return
        if (state0.currentPlayerIndex != playerIndex) return

        val hand0 = state0.hands[playerIndex] ?: return
        if (hand0.isEmpty()) return

        delay(AI_DELAY_MS)

        // 在临界区内决策并修改状态；广播放在锁外执行，避免慢的网络发送阻塞其他玩家动作
        var resultToBroadcast: ActionResult? = null
        var forceAdvance = false
        mutexFor(room).withLock {
            if (room.status != RoomStatus.IN_GAME) return@withLock
            val state = room.gameState ?: return@withLock
            if (state.phase != "PLAYING") return@withLock
            if (state.currentPlayerIndex != playerIndex) return@withLock
            val hand = state.hands[playerIndex] ?: return@withLock
            if (hand.isEmpty()) return@withLock

            val action = decideAIAction(hand, state.lastPlayedGroup, playerIndex)
            var result = when (action) {
                is PlayerAction.PlayCards -> handlePlayCards(room, action)
                is PlayerAction.Pass -> handlePass(room, action)
            }

            if (!result.success) {
                println("AI action failed for seat $playerIndex: ${result.error}; trying fallback")
                if (state.lastPlayedGroup != null) {
                    result = handlePass(room, PlayerAction.Pass(playerIndex))
                }
                if (!result.success && hand.isNotEmpty()) {
                    val smallest = hand.minByOrNull { getRankValue(it.rank) }!!
                    result = handlePlayCards(
                        room,
                        PlayerAction.PlayCards(playerIndex, listOf(smallest.toSerialized()))
                    )
                }
            }

            if (result.success) {
                resultToBroadcast = result
            } else {
                println("AI fallback also failed for seat $playerIndex: ${result.error}; will force-advance")
                forceAdvance = true
                moveToNextPlayer(room)
                state.version++
                resetTurnTimer(room)
            }
        }

        resultToBroadcast?.let { broadcastActionResult(room, it) }
        if (forceAdvance) {
            broadcastForceAdvance(room)
        }
    }

    /**
     * 极端兜底广播：所有AI动作都失败时，发送当前同步状态并触发下一回合
     */
    private suspend fun broadcastForceAdvance(room: ServerRoom) {
        room.players.forEach { player ->
            val playerState = getStateForPlayer(room, player.seatIndex)
            player.session?.send(GameSync(playerState))
        }
        val nextPlayerId = room.gameState?.currentPlayerIndex ?: 0
        room.players.forEach { player ->
            player.session?.send(GameEventMessage(SerializedGameEvent.TurnStart(nextPlayerId)))
        }
        scope.launch {
            delay(AI_DELAY_MS)
            checkAndProcessAITurn(room)
        }
    }

    /**
     * 广播动作结果给所有玩家（公共逻辑）
     */
    suspend fun broadcastActionResult(room: ServerRoom, result: ActionResult) {
        // 1. 广播状态更新
        room.players.forEach { player ->
            val playerState = getStateForPlayer(room, player.seatIndex)
            player.session?.send(GameActionResult(true, null, playerState))
        }

        // 2. 广播动作事件
        result.event?.let { event ->
            room.players.forEach { player ->
                player.session?.send(GameEventMessage(event))
            }
        }

        // 3. 广播玩家走完事件（如有）
        result.finishEvent?.let { event ->
            room.players.forEach { player ->
                player.session?.send(GameEventMessage(event))
            }
        }

        // 4. 广播本轮结束事件（如有）
        result.roundEndEvent?.let { event ->
            room.players.forEach { player ->
                player.session?.send(GameEventMessage(event))
            }
        }

        // 4. 广播回合开始事件（除非游戏结束）
        if (result.gameResult == null) {
            val nextPlayerId = room.gameState?.currentPlayerIndex ?: 0
            room.players.forEach { player ->
                player.session?.send(GameEventMessage(SerializedGameEvent.TurnStart(nextPlayerId)))
            }
        }

        // 5. 广播游戏结束
        result.gameResult?.let { gameResult ->
            room.status = RoomStatus.FINISHED
            stopTurnTimer(room)
            room.players.forEach { player ->
                player.session?.send(GameEnd(gameResult))
            }
            // 如果游戏结束时已无连接的真人玩家，立即清理房间避免泄漏
            if (room.players.none { it.session != null && !it.isAI }) {
                roomManager.deleteRoom(room.roomId)
                cleanupRoom(room.roomId)
            }
        }
    }

    private fun decideAIAction(hand: List<ServerCard>, lastPlay: ServerCardGroup?, playerId: Int): PlayerAction {
        if (lastPlay == null) {
            // 自由出牌：优先出对子/三张，其次出最小的单张
            val grouped = hand.groupBy { it.rank }

            // 找最小的对子
            val smallestPair = grouped.entries
                .filter { it.value.size == 2 }
                .minByOrNull { getRankValue(it.key) }
            if (smallestPair != null) {
                return PlayerAction.PlayCards(playerId, smallestPair.value.map { it.toSerialized() })
            }

            // 找最小的三张
            val smallestTriple = grouped.entries
                .filter { it.value.size == 3 }
                .minByOrNull { getRankValue(it.key) }
            if (smallestTriple != null) {
                return PlayerAction.PlayCards(playerId, smallestTriple.value.map { it.toSerialized() })
            }

            // 出最小的单张（避免拆炸弹）
            val nonBombCards = hand.filter { card ->
                grouped[card.rank]?.size?.let { it < 4 } ?: true
            }
            val smallest = (nonBombCards.ifEmpty { hand }).minByOrNull { getRankValue(it.rank) }!!
            return PlayerAction.PlayCards(playerId, listOf(smallest.toSerialized()))
        }

        // 尝试压牌
        val validPlays = findValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) {
            return PlayerAction.Pass(playerId)
        }

        // 优先选择同类型的牌（不用炸弹）
        val sameTypePlays = validPlays.filter { it.type == lastPlay.type }
        if (sameTypePlays.isNotEmpty()) {
            // 选择最小的同类型牌：先比张数（避免浪费大炸弹），再比点数
            val bestPlay = sameTypePlays.minWithOrNull(
                compareBy({ it.cards.size }, { getRankValue(it.primaryRank) })
            )!!
            return PlayerAction.PlayCards(playerId, bestPlay.cards.map { it.toSerialized() })
        }

        // 只剩炸弹可用
        val bombs = validPlays.filter { it.type == "BOMB" }
        if (bombs.isEmpty()) {
            return PlayerAction.Pass(playerId)
        }

        // 判断是否值得用炸弹
        val lastPlayValue = getRankValue(lastPlay.primaryRank)
        val smallestBomb = bombs.minByOrNull { it.cards.size * 100 + getRankValue(it.primaryRank) }!!

        // 如果上家牌太小（小于10），一般不值得用炸弹压
        // 除非：手牌很少（小于10张）或者炸弹很小（4张3/4/5）
        val shouldUseBomb = when {
            hand.size <= 10 -> true  // 手牌少，积极出牌
            lastPlayValue >= 7 -> true  // 上家牌大（10及以上），值得压
            lastPlay.type == "BOMB" -> true  // 上家是炸弹，必须用炸弹压
            smallestBomb.cards.size == 4 && getRankValue(smallestBomb.primaryRank) <= 2 -> true  // 小炸弹（4张3/4/5）可以用
            else -> false
        }

        return if (shouldUseBomb) {
            PlayerAction.PlayCards(playerId, smallestBomb.cards.map { it.toSerialized() })
        } else {
            PlayerAction.Pass(playerId)
        }
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

    /**
     * 清理房间相关的游戏资源
     */
    fun cleanupRoom(roomId: String) {
        turnTimers[roomId]?.cancel()
        turnTimers.remove(roomId)
        roomMutexes.remove(roomId)
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

    // PR-H3 stage 3：删除本地副本，直接委托给 :shared 的 CardRules。
    // 历史教训（docs/regressions.md #2）：服务端的 canBeat 与客户端的 canBeat
    // 曾出现行为不一致导致联网卡死。现在编译期保证两端共用同一份算法。
    internal fun identifyCardGroup(cards: List<ServerCard>): ServerCardGroup? {
        val sharedGroup = CardRules.identifyCardGroup(cards.map { it.toSharedCard() })
            ?: return null
        return sharedGroup.toServerCardGroup()
    }

    internal fun canBeat(last: ServerCardGroup, current: ServerCardGroup): Boolean =
        CardRules.canBeat(last.toSharedCardGroup(), current.toSharedCardGroup())

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

    // PR-H3 stage 3：委托给 :shared 的 CardRank 枚举。
    // 注：返回值与旧实现相比整体偏移 +1（旧 THREE=0；新 THREE=1）。但所有调用方
    // 只比较两个 getRankValue 结果的相对大小（current > last 之类），偏移对结果
    // 无影响。直接 .name → CardRank.valueOf 即可，不需保留旧 0..14 编码。
    internal fun getRankValue(rank: String): Int =
        runCatching { CardRank.valueOf(rank).value }.getOrDefault(-1)

    private fun getCardScore(card: ServerCard): Int =
        runCatching { CardRank.valueOf(card.rank).scoreValue }.getOrDefault(0)
}

// ============================================================
// PR-H3 stage 3：Server* ↔ :shared 类型互转
// ServerCard / ServerCardGroup 在 server 内部仍以字符串编码 rank/suit/type
// （历史遗留：与旧 protocol 一对一）。当 ServerGameManager 调用 :shared 的
// CardRules / SettlementCalculator 时，先转成 Card / CardGroup，再转回。
// 性能成本可忽略（每次出牌验证 ≤6 张牌）。
// ============================================================

internal fun ServerCard.toSharedCard(): Card =
    Card(CardRank.valueOf(rank), CardSuit.valueOf(suit), deckIndex)

internal fun ServerCardGroup.toSharedCardGroup(): CardGroup =
    CardGroup(cards.map { it.toSharedCard() }, CardGroupType.valueOf(type))

internal fun Card.toServerCard(): ServerCard =
    ServerCard(rank.name, suit.name, deckIndex)

internal fun CardGroup.toServerCardGroup(): ServerCardGroup =
    ServerCardGroup(
        cards = cards.map { it.toServerCard() },
        type = type.name,
        primaryRank = primaryRank.name,
    )

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
    var version: Long,
    // 每个玩家累计已收分（赢得回合获得的分数总和）
    val playerScores: MutableMap<Int, Int> = mutableMapOf()
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
    val gameResult: SerializedGameResult? = null,
    val roundEndEvent: SerializedGameEvent.RoundWon? = null,
    val finishEvent: SerializedGameEvent.PlayerFinished? = null
)
