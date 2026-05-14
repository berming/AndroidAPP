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
 * - 支持单张、对子、三张、炸弹（不支持顺子，详见 docs/game_rules.md）
 * - 一队全部出完或达到200分结束
 */
class ServerGameManager(
    val roomManager: ServerRoomManager
) {
    companion object {
        private const val TURN_TIMEOUT_MS = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val turnTimers = ConcurrentHashMap<String, Job>()
    // 每个房间的动作互斥锁，确保同一房间的动作不会并发执行（防止 hand/state 竞态）
    private val roomMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(room: ServerRoom): Mutex =
        roomMutexes.getOrPut(room.roomId) { Mutex() }

    /**
     * 暴露给 admin 模块 [com.communicationcard.server.admin.SnapshotBuilder] 用：
     * 在 admin 路由处理路径内拿一份房间的 immutable snapshot。
     *
     * 该函数复用同一把 [mutexFor] 锁，因此在 admin 路由短暂持锁期间，
     * 游戏关键路径（[handleAction]）会被阻塞——但 snapshot 构造是
     * O(players × hand size) 纳秒级，可接受。
     *
     * 严格约束（CLAUDE.md 约束 9）：调用方 block 内**不得**做 IO / 网络发送 /
     * SQLite 写；只允许做 defensive copy。
     */
    internal suspend fun <T> withRoomLock(room: ServerRoom, block: suspend () -> T): T =
        mutexFor(room).withLock { block() }

    /**
     * 游戏结束钩子（pr-reviewer PR #61 P2 #5 重构）。
     *
     * 拆为 2 个 hook 严格对应 CLAUDE.md 约束 9 "锁内 capture + 锁外 enqueue" 的正例：
     *
     * 1. [gameEndCaptureProvider]：**在 mutexFor(room).withLock 内**被调用。
     *    实现只允许做 immutable 值拷贝（如 `GameRecord.capture(room, result)`），
     *    返回一个 opaque 快照对象给上层
     * 2. [gameEndConsumer]：**在锁外**被调用，参数是上一步返回的快照。
     *    可以做 `Channel.trySend` 等"非阻塞但非纯内存"的操作
     *
     * 拆开的好处：哪怕 [gameEndConsumer] 实现忘记非阻塞（如改成 SQLite 同步写），
     * 也只阻塞调用线程而非整个房间的 mutex；约束 9 的契约从"约定"变成"结构性保证"。
     *
     * Captured 类型在这一层是 `Any?`，由 admin 模块自由解释（实际是 `GameRecord`），
     * 避免 server 主模块依赖 admin 类型。
     */
    @Volatile
    var gameEndCaptureProvider: ((ServerRoom, SerializedGameResult) -> Any?)? = null

    /**
     * 见 [gameEndCaptureProvider]：锁外消费 capture 出的快照。
     */
    @Volatile
    var gameEndConsumer: ((Any) -> Unit)? = null

    /**
     * PR 5d：每个游戏内动作 / 事件触发时调用。admin 模块的 GameHistoryStore
     * 用这个钩子按 roomId 在内存里累积事件，游戏结束时连同 GameRecord 一并
     * 写入 game_events 表（FK 1:N → games）。
     *
     * 调用时机契约（Codex P2 修复后）：
     * - **在 mutexFor(room).withLock 内**被调用——保证多个并发动作的事件按
     *   动作顺序触发；listener 内的 seq 计数器（GameHistoryStore.recordEvent
     *   的 AtomicInteger）严格反映出牌顺序
     * - 实际调用点：handlePlayCards / handlePass 的返回前（**不**在 broadcast
     *   阶段调，因为 broadcast 跑在锁外、session.send 可 suspend 让其他动作
     *   抢先）
     * - 同 [gameEndCaptureProvider] / [gameEndConsumer] 契约：非 suspend、
     *   只能 immutable 拷贝 + trySend
     * - **不可** 递归 [withRoomLock]（Mutex 非可重入会死锁）
     */
    @Volatile
    var gameEventListener: ((ServerRoom, SerializedGameEvent) -> Unit)? = null

    /**
     * 当前 AI 出牌前的"思考"延迟（毫秒）。读 room/player 状态：
     * - 玩家被 AI 接管（isAISubstitute=true）→ 用 player.takeoverAiDelayMs（feature_spec G38）
     * - 否则（补位 AI）→ 用 room.serverAiDelayMs（feature_spec G37）
     * 找不到玩家时回退到房间默认值。
     */
    /**
     * 在 AI 决策延迟（effectiveAiDelayMs）结束后，决定是否把控制权让回给人类玩家。
     *
     * 规则：玩家不是 AI、未被托管（isAISubstitute=false）、且已连接 → 让出控制权（return true）。
     * 防 Codex P2 (PR #53)：玩家在延迟期间点"我回来了"时 AI 仍代打的 race。
     *
     * 提取为函数便于单元测试 + 让 processAITurn 的关键逻辑可读。
     */
    internal fun shouldYieldToHumanPlayer(player: ServerPlayer): Boolean =
        !player.isAI && !player.isAISubstitute && player.isConnected

    internal fun effectiveAiDelayMs(room: ServerRoom, seatIndex: Int): Long {
        val player = room.players.find { it.seatIndex == seatIndex }
        val ms = if (player?.isAISubstitute == true) player.takeoverAiDelayMs else room.serverAiDelayMs
        // 防御性 clamp，避免被恶意 / 旧客户端推到 0 / 负 / 无穷大
        return ms.coerceIn(
            com.communicationcard.game.network.GameMessage.AI_DELAY_MIN_MULTIPLAYER_MS,
            com.communicationcard.game.network.GameMessage.AI_DELAY_MAX_MS
        ).toLong()
    }

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
            // pr-reviewer/Codex PR #64 P2 修复：在锁内重新校验 status。
            // 锁外的 line 193 预检 + 锁内的转换为 FINISHED（在 handlePlayCards /
            // handlePass 内）共同构成正确性边界——锁外 check 可能拿到陈旧 IN_GAME，
            // 锁内再 check 一次才能拒绝那种"等锁期间游戏已结束"的请求。
            if (room.status != RoomStatus.IN_GAME) {
                return@withLock ActionResult(false, "游戏未在进行中")
            }

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
        state.lastActionAt = System.currentTimeMillis()  // PR 3: ROOM_STUCK 告警基准

        // 重置回合计时器
        resetTurnTimer(room)

        val event = SerializedGameEvent.CardsPlayed(
            playerId = action.playerId,
            cardGroup = cardGroup.toSerialized()
        )

        // 检查游戏是否结束
        val gameResult = checkGameEnd(room)

        // pr-reviewer/Codex PR #64 P2 修复：room.status 必须在锁内随 gameResult 一起
        // 设为 FINISHED。原来由锁外的 broadcastActionResult 设，但 capture 锁 → 释放
        // → 锁外 consumer 这段窗口内，下一个 handleAction（锁外预检 status==IN_GAME
        // 通过）会拿到 mutex 后操作已结束游戏，触发额外 event 或重复 end handling。
        if (gameResult != null) {
            room.status = RoomStatus.FINISHED
        }

        // PR 5d + Codex P2 修复：listener 在锁内按动作顺序触发（broadcast 在锁外
        // 跑可能因 session.send suspend 而被另一个 action 抢先调 listener，造成
        // game_events.seq 与实际出牌顺序倒置）。在锁内、按"广播会发生的顺序"调用
        // listener：CardsPlayed → PlayerFinished → TurnStart
        gameEventListener?.let { l ->
            try { l(room, event) } catch (_: Throwable) { /* ignore */ }
            finishEvent?.let { fe ->
                try { l(room, fe) } catch (_: Throwable) { /* ignore */ }
            }
            if (gameResult == null) {
                try {
                    l(room, SerializedGameEvent.TurnStart(state.currentPlayerIndex))
                } catch (_: Throwable) { /* ignore */ }
            }
        }

        // 触发AI回合（仅在游戏未结束时）
        if (gameResult == null) {
            scope.launch {
                val nextSeat = room.gameState?.currentPlayerIndex ?: 0
                delay(effectiveAiDelayMs(room, nextSeat))
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
        state.lastActionAt = System.currentTimeMillis()  // PR 3: ROOM_STUCK 告警基准

        // 重置回合计时器
        resetTurnTimer(room)

        // 检查游戏是否结束
        val gameResult = checkGameEnd(room)

        // pr-reviewer/Codex PR #64 P2 修复：锁内立即 mark FINISHED，详见
        // handlePlayCards 同名修复块的注释。
        if (gameResult != null) {
            room.status = RoomStatus.FINISHED
        }

        // PR 5d + Codex P2 修复：listener 在锁内按动作顺序触发（详见 handlePlayCards
        // 同名注释）。顺序：PlayerPassed → RoundWon → TurnStart
        gameEventListener?.let { l ->
            try { l(room, passEvent) } catch (_: Throwable) { /* ignore */ }
            roundEndEvent?.let { re ->
                try { l(room, re) } catch (_: Throwable) { /* ignore */ }
            }
            if (gameResult == null) {
                try {
                    l(room, SerializedGameEvent.TurnStart(state.currentPlayerIndex))
                } catch (_: Throwable) { /* ignore */ }
            }
        }

        // 触发AI回合（仅在游戏未结束时）
        if (gameResult == null) {
            scope.launch {
                val nextSeat = room.gameState?.currentPlayerIndex ?: 0
                delay(effectiveAiDelayMs(room, nextSeat))
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

    internal suspend fun checkAndProcessAITurn(room: ServerRoom) {
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

        delay(effectiveAiDelayMs(room, playerIndex))

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

            // Codex P2 (PR #53)：玩家可能在 effectiveAiDelayMs 期间点"我回来了"
            // (ToggleAITakeover(false))；此时 isAISubstitute=false 但回合未推进。
            // 这里若不重检，AI 仍会代打——破坏 G34/G35 即时收回控制权的语义。
            val player = room.players.find { it.seatIndex == playerIndex }
            if (player != null && shouldYieldToHumanPlayer(player)) {
                return@withLock
            }

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
                state.lastActionAt = System.currentTimeMillis()  // PR 3: force-advance 也算 unstuck
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
            val nextSeat = room.gameState?.currentPlayerIndex ?: 0
            delay(effectiveAiDelayMs(room, nextSeat))
            checkAndProcessAITurn(room)
        }
    }

    /**
     * 广播动作结果给所有玩家（公共逻辑）。
     *
     * 注意：gameEventListener 不在这里调用——已搬到 handlePlayCards / handlePass
     * 内（锁内），由 [gameEventListener] docstring 上的 Codex P2 修复保证按
     * 动作顺序触发，避免并发 broadcast suspend 让较晚的动作先调 listener。
     *
     * gameEnd 钩子（[gameEndCaptureProvider] + [gameEndConsumer]）仍在这里调：
     * captureProvider 在 mutexFor(room).withLock 内调（拍 immutable 快照），
     * consumer 在锁外调（拿快照做 trySend 等非阻塞操作）。pr-reviewer PR #61
     * P2 #5 拆分后的契约。
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
            val turnStart = SerializedGameEvent.TurnStart(nextPlayerId)
            room.players.forEach { player ->
                player.session?.send(GameEventMessage(turnStart))
            }
        }

        // 5. 广播游戏结束
        result.gameResult?.let { gameResult ->
            // PR 2 admin 监控：拍 GameRecord 必须在锁内（防 finishOrder / hands /
            // playerScores 的 MutableMap/MutableList 与 handleDisconnect /
            // handlePlayerLeave 并发读时 ConcurrentModificationException）。
            //
            // pr-reviewer PR #61 P2 #5 重构：把"锁内 capture + 锁外 enqueue"做成
            // 两个独立 hook。即便未来 consumer 实现忘记非阻塞，也只会阻塞 broadcast
            // 这一根线程，不会卡住房间 mutex（CLAUDE.md 约束 9 正例）。
            val captured: Any? = try {
                mutexFor(room).withLock { gameEndCaptureProvider?.invoke(room, gameResult) }
            } catch (e: Throwable) {
                System.err.println("gameEndCaptureProvider throw (ignored): ${e.message}")
                null
            }
            if (captured != null) {
                try {
                    gameEndConsumer?.invoke(captured)
                } catch (e: Throwable) {
                    System.err.println("gameEndConsumer throw (ignored): ${e.message}")
                }
            }

            // pr-reviewer/Codex PR #64 P2 修复：room.status 现在由 handlePlayCards /
            // handlePass 在锁内立即设置为 FINISHED；broadcast 不再 touch status，
            // 否则锁外 status 转换会产生 check-then-mutate 窗口。
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
        //
        // 阈值历史：旧 getRankValue 是 0-based（THREE=0..BIG_JOKER=14）。
        // PR-H3 stage 3 委托给 :shared 的 CardRank.value（1-based，THREE=1..BIG_JOKER=15）
        // 后，这两条绝对阈值都需要 +1 才能保持原意（Codex P2 在 PR #39 指出）：
        //   旧 lastPlayValue >= 7  → TEN(7)+    新 >= 8  → TEN(8)+
        //   旧 <= 2 → THREE/FOUR/FIVE(0/1/2)    新 <= 3 → THREE/FOUR/FIVE(1/2/3)
        val shouldUseBomb = when {
            hand.size <= 10 -> true  // 手牌少，积极出牌
            lastPlayValue >= 8 -> true  // 上家牌大（10及以上），值得压
            lastPlay.type == "BOMB" -> true  // 上家是炸弹，必须用炸弹压
            smallestBomb.cards.size == 4 && getRankValue(smallestBomb.primaryRank) <= 3 -> true  // 小炸弹（4张3/4/5）可以用
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
    val playerScores: MutableMap<Int, Int> = mutableMapOf(),
    // PR 2: 本局开始时间（epoch ms）。admin GameHistoryStore 入库时记录 duration_ms
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    // PR 3: 最近一次玩家动作的时间（epoch ms）。admin AlertRule.ROOM_STUCK 检查
    // (now - lastActionAt > 5 min) 触发"房间卡死"告警。
    // 每次 handleAction 成功路径会刷新；handleAction 失败 / 自动 AI 接管 不刷新——
    // AI 卡了也算卡。
    @Volatile var lastActionAt: Long = System.currentTimeMillis(),
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
