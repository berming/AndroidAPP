package com.communicationcard.server

import com.communicationcard.game.network.SerializedGameResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ServerGameManager 单元测试 —— 关键路径强制 TDD（CLAUDE.md 第三章）。
 *
 * 防回归覆盖（详见 docs/regressions.md）：
 *  - #1 CardSuit 枚举字符串：identifyCardGroup 输入 "SPADE" / "HEART" 等大写值，
 *       与 :shared 枚举大写一致
 *  - #2 游戏卡死 L1：canBeat 比较不同张数炸弹的优先级
 *  - #4 已收分硬编码 0：computeAllFinishedScores 必须从 state.playerScores 读
 *  - #8 结算公式漏算："输方未走完玩家已收分"必须计入赢方
 *
 * **PR-H3 起 server 端的 canBeat / identifyCardGroup / computeAllFinishedScores
 * 全部委托给 :shared 的 CardRules + SettlementCalculator。本测试现在覆盖的是
 * Server* 类型 ↔ :shared 类型的转换 + 业务行为，而不是逻辑本身**。逻辑由 :shared
 * 的 CardRulesTest（~30 用例）+ SettlementCalculatorTest（15 用例）保证。
 *
 * 即便如此，测试还有价值：
 *  - 防回归 #1：rank/suit 字符串约定（如 "SPADE" vs "spade"）必须与 :shared 枚举
 *    名字一致，否则 CardRank.valueOf 会抛 IllegalArgumentException
 *  - 防回归 #4 #8：computeAllFinishedScores 的输入构建（playerScores、hand 求和）
 *    在 server 端独立于 :shared，错误仍会在此层被发现
 *  - 防止 server 端意外重新引入 canBeat 等逻辑副本（约束 1 漂移源头）
 *
 * Codex 在 PR #39 指出 `decideAIAction` 里有两条**绝对**阈值
 * （`lastPlayValue >= 7` 和 `getRankValue(...) <= 2`），需要随 getRankValue
 * 返回值整体 +1（PR-H3 stage 3 委托给 :shared CardRank 后从 0-based 变成
 * 1-based）一起 +1 才能保持原意。已修：阈值改为 `>= 8` 和 `<= 3`。
 * `decideAIAction` 是 private，未来如果对 AI 阈值做单元测试，可放宽到 internal
 * 单独覆盖；本 PR 仅文档化此约束。
 */
class ServerGameManagerTest {

    // ServerGameManager 需要 ServerRoomManager；测试中我们不调任何依赖 roomManager
    // 的方法，所以直接给一个最小实例。
    private val gm = ServerGameManager(ServerRoomManager())

    // ============================================================
    //  identifyCardGroup
    // ============================================================

    @Test
    fun identifyCardGroup_emptyList_returnsNull() {
        assertNull(gm.identifyCardGroup(emptyList()))
    }

    @Test
    fun identifyCardGroup_singleCard_returnsSingle() {
        val g = gm.identifyCardGroup(listOf(card("SEVEN")))
        assertNotNull(g)
        assertEquals("SINGLE", g.type)
        assertEquals("SEVEN", g.primaryRank)
    }

    @Test
    fun identifyCardGroup_pairSameRank_returnsPair() {
        val g = gm.identifyCardGroup(listOf(card("NINE", "SPADE"), card("NINE", "HEART")))
        assertEquals("PAIR", g!!.type)
    }

    @Test
    fun identifyCardGroup_threeSameRank_returnsTriple() {
        val g = gm.identifyCardGroup(
            listOf(card("QUEEN", "SPADE"), card("QUEEN", "HEART"), card("QUEEN", "CLUB")),
        )
        assertEquals("TRIPLE", g!!.type)
    }

    @Test
    fun identifyCardGroup_fourSameRank_returnsBomb() {
        val g = gm.identifyCardGroup(
            listOf(card("SEVEN", "SPADE"), card("SEVEN", "HEART"), card("SEVEN", "CLUB"), card("SEVEN", "DIAMOND")),
        )
        assertEquals("BOMB", g!!.type)
    }

    @Test
    fun identifyCardGroup_fiveSameRank_returnsBomb() {
        val cards = (0..4).map { card("FIVE", "SPADE", deckIndex = it) }
        val g = gm.identifyCardGroup(cards)
        assertEquals("BOMB", g!!.type)
        assertEquals(5, g.cards.size)
    }

    @Test
    fun identifyCardGroup_fiveConsecutive_returnsNull_straightRemoved() {
        // 沟通牌不再支持顺子；服务端委托给 :shared/CardRules，与之等价
        val cards = listOf(card("FIVE"), card("SIX"), card("SEVEN"), card("EIGHT"), card("NINE"))
        assertNull(gm.identifyCardGroup(cards))
    }

    @Test
    fun identifyCardGroup_fourConsecutive_returnsNull() {
        val cards = listOf(card("FIVE"), card("SIX"), card("SEVEN"), card("EIGHT"))
        assertNull(gm.identifyCardGroup(cards))
    }

    /**
     * 防回归 #1（CardSuit 枚举不匹配）：用 :shared 大写值"SPADE/HEART/CLUB/DIAMOND"
     * 应正常识别，对小写"spade"则不应能错误生成有效组（这条由 client 协议层
     * 校验，不在本测试范围 —— 但记录以避免未来无意宽松）。
     */
    @Test
    fun identifyCardGroup_acceptsUppercaseSuit_regressions1() {
        val g = gm.identifyCardGroup(listOf(card("EIGHT", "SPADE"), card("EIGHT", "HEART")))
        assertEquals("PAIR", g!!.type)
    }

    // ============================================================
    //  canBeat —— 基础矩阵
    // ============================================================

    @Test
    fun canBeat_singleBiggerRank_beats() {
        assertTrue(gm.canBeat(single("NINE"), single("JACK")))
    }

    @Test
    fun canBeat_singleSameRank_doesNotBeat() {
        assertFalse(gm.canBeat(single("NINE"), single("NINE")))
    }

    @Test
    fun canBeat_singleSmallerRank_doesNotBeat() {
        assertFalse(gm.canBeat(single("JACK"), single("NINE")))
    }

    @Test
    fun canBeat_pairBiggerRank_beats() {
        assertTrue(gm.canBeat(pair("SEVEN"), pair("JACK")))
    }

    @Test
    fun canBeat_pairVsSingle_doesNotBeat() {
        assertFalse(gm.canBeat(single("THREE"), pair("ACE")))
        assertFalse(gm.canBeat(pair("THREE"), single("ACE")))
    }

    @Test
    fun canBeat_tripleBiggerRank_beats() {
        assertTrue(gm.canBeat(triple("SIX"), triple("QUEEN")))
    }

    // ============================================================
    //  炸弹 hierarchy —— docs/regressions.md #2 防回归
    // ============================================================

    @Test
    fun canBeat_bombBeatsSingle() {
        assertTrue(gm.canBeat(single("ACE"), bomb("THREE", count = 4)))
    }

    @Test
    fun canBeat_bombBeatsPair() {
        assertTrue(gm.canBeat(pair("ACE"), bomb("FOUR", count = 4)))
    }

    @Test
    fun canBeat_singleDoesNotBeatBomb() {
        assertFalse(gm.canBeat(bomb("THREE", count = 4), single("BIG_JOKER")))
    }

    @Test
    fun canBeat_bombSameSizeBiggerRank_beats() {
        assertTrue(gm.canBeat(bomb("THREE", count = 4), bomb("KING", count = 4)))
    }

    @Test
    fun canBeat_bombSameSizeSmallerRank_doesNotBeat() {
        assertFalse(gm.canBeat(bomb("KING", count = 4), bomb("THREE", count = 4)))
    }

    /**
     * 防回归：docs/regressions.md #2 L1。
     * 旧实现严格要求 size 相同，导致 5+ 张大炸弹被错误拒绝、AI 卡死。
     * 5×3 必须能压 4×10：张数优先级高于点数。
     */
    @Test
    fun canBeat_biggerBombSizeWinsOverRank_regressions2() {
        val bombSmall = bomb("TEN", count = 4)        // 4×10
        val bombLarge = bomb("THREE", count = 5)         // 5×3
        assertTrue(
            gm.canBeat(bombSmall, bombLarge),
            "5×3 应该压 4×10（炸弹张数优先于点数；regressions #2 L1）",
        )
    }

    @Test
    fun canBeat_biggerBombBeatsAllSmallerBombs() {
        val sixBomb = bomb("THREE", count = 6)
        val fiveBomb = bomb("KING", count = 5)
        assertTrue(gm.canBeat(fiveBomb, sixBomb))
    }

    @Test
    fun canBeat_smallerBombSize_doesNotBeat() {
        val sixBomb = bomb("THREE", count = 6)
        val fourBomb = bomb("BIG_JOKER", count = 4, suit = "JOKER")
        assertFalse(gm.canBeat(sixBomb, fourBomb))
    }

    // ============================================================
    //  大小王
    // ============================================================

    @Test
    fun canBeat_bigJokerBeatsSmallJoker() {
        assertTrue(gm.canBeat(single("SMALL_JOKER", "JOKER"), single("BIG_JOKER", "JOKER")))
    }

    @Test
    fun canBeat_bigJokerBeatsTwo() {
        assertTrue(gm.canBeat(single("TWO"), single("BIG_JOKER", "JOKER")))
    }

    @Test
    fun getRankValue_jokers_outrank2() {
        // 大小关系: 大王 > 小王 > 2 > A > K > ...
        assertTrue(
            gm.getRankValue("BIG_JOKER") > gm.getRankValue("SMALL_JOKER"),
            "BIG_JOKER 应该 > SMALL_JOKER",
        )
        assertTrue(gm.getRankValue("SMALL_JOKER") > gm.getRankValue("TWO"))
        assertTrue(gm.getRankValue("TWO") > gm.getRankValue("ACE"))
        assertTrue(gm.getRankValue("ACE") > gm.getRankValue("KING"))
        assertTrue(gm.getRankValue("THREE") < gm.getRankValue("FOUR"))
    }

    // ============================================================
    //  computeAllFinishedScores —— docs/regressions.md #4 #8 防回归
    // ============================================================

    /**
     * 防回归 #4：playerScores 不能被硬编码 0。
     * 防回归 #8：赢方得分 = 赢方已收 + 输方未走完玩家已收 + 输方未走完玩家手牌分。
     *
     * 场景：A 队全员走完，B 队 b1 也走完了，b2/b3 没走完。
     * 已收分: a1=80, a2=60, a3=40 (赢方共 180); b1=20 (走完); b2=30 (未走完); b3=10 (未走完)
     * b2 手牌总分: 25; b3 手牌: 15
     *
     * 期望赢方总分 = 180 (赢方自己的) + (30+25) + (10+15) = 180+55+25 = 260
     * 期望输方总分 = 20 (b1 走完了的已收)
     */
    @Test
    fun computeAllFinishedScores_includesLoserUnfinishedCollected_regressions8() {
        val winner = listOf(
            sp(seat = 0, team = "ACE"),
            sp(seat = 1, team = "ACE"),
            sp(seat = 2, team = "ACE"),
        )
        val loser = listOf(
            sp(seat = 3, team = "B"),
            sp(seat = 4, team = "B"),
            sp(seat = 5, team = "B"),
        )
        val state = state(
            hands = mapOf(
                0 to emptyList(),                           // a1 走完
                1 to emptyList(),                           // a2 走完
                2 to emptyList(),                           // a3 走完
                3 to emptyList(),                           // b1 走完
                4 to listOf(card("FIVE"), card("TEN"), card("TEN")), // b2 手牌 = 5+10+10 = 25 分
                5 to listOf(card("FIVE"), card("KING")),              // b3 手牌 = 5+10 = 15 分
            ),
            playerScores = mapOf(0 to 80, 1 to 60, 2 to 40, 3 to 20, 4 to 30, 5 to 10),
        )

        val (winnerScore, loserScore) = gm.computeAllFinishedScores(state, winner, loser)
        assertEquals(260, winnerScore, "赢方=赢方已收180 + b2(30+25) + b3(10+15)")
        assertEquals(20, loserScore, "输方=唯一走完的 b1 的已收")
    }

    /**
     * 防回归 #8 边界：输方全员走完（没有未走完玩家）。
     * 期望：赢方仅得自己的已收（无 loser unfinished 项）；输方得自己全部已收。
     */
    @Test
    fun computeAllFinishedScores_loserAllFinished_returnsCleanSums() {
        val winner = listOf(sp(0, "ACE"), sp(1, "ACE"), sp(2, "ACE"))
        val loser = listOf(sp(3, "B"), sp(4, "B"), sp(5, "B"))
        val state = state(
            hands = mapOf(0 to listOf(), 1 to listOf(), 2 to listOf(),
                3 to listOf(), 4 to listOf(), 5 to listOf()),
            playerScores = mapOf(0 to 100, 1 to 50, 2 to 30, 3 to 20, 4 to 40, 5 to 10),
        )
        val (w, l) = gm.computeAllFinishedScores(state, winner, loser)
        assertEquals(180, w, "赢方=自己已收 100+50+30")
        assertEquals(70, l, "输方=自己全部已收 20+40+10（全员走完）")
    }

    /**
     * 防回归 #4：playerScores 中所有玩家都是 0（硬编码症状）。
     * 期望：仍然能正确计算，赢方仅拿到输方未走完玩家手牌分，loser 全 0。
     */
    @Test
    fun computeAllFinishedScores_allCollectedZero_stillComputesHandScores() {
        val winner = listOf(sp(0, "ACE"), sp(1, "ACE"), sp(2, "ACE"))
        val loser = listOf(sp(3, "B"), sp(4, "B"), sp(5, "B"))
        val state = state(
            hands = mapOf(
                0 to listOf(), 1 to listOf(), 2 to listOf(),
                3 to listOf(),
                4 to listOf(card("FIVE"), card("TEN")),  // 15 分
                5 to listOf(card("KING")),               // 10 分
            ),
            playerScores = mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0),
        )
        val (w, l) = gm.computeAllFinishedScores(state, winner, loser)
        assertEquals(25, w, "赢方=0 已收 + b2 手牌 15 + b3 手牌 10")
        assertEquals(0, l)
    }

    // ============================================================
    //  辅助工厂
    // ============================================================

    private fun card(rank: String, suit: String = "SPADE", deckIndex: Int = 0) =
        ServerCard(rank, suit, deckIndex)

    private fun single(rank: String, suit: String = "SPADE"): ServerCardGroup =
        ServerCardGroup(listOf(card(rank, suit)), "SINGLE", rank)

    private fun pair(rank: String): ServerCardGroup =
        ServerCardGroup(listOf(card(rank, "SPADE"), card(rank, "HEART")), "PAIR", rank)

    private fun triple(rank: String): ServerCardGroup =
        ServerCardGroup(
            listOf(card(rank, "SPADE"), card(rank, "HEART"), card(rank, "CLUB")),
            "TRIPLE",
            rank,
        )

    private fun bomb(rank: String, count: Int, suit: String = "SPADE"): ServerCardGroup {
        require(count >= 4) { "bomb 至少 4 张" }
        val cards = (0 until count).map { card(rank, suit, deckIndex = it) }
        return ServerCardGroup(cards, "BOMB", rank)
    }

    /**
     * 构造一个测试用 ServerPlayer（session=null，因测试不发网络消息）。
     */
    private fun sp(seat: Int, team: String): ServerPlayer = ServerPlayer(
        id = "test-player-$seat",
        name = "P$seat",
        session = null,
        isReady = true,
        isAI = true,
        seatIndex = seat,
        team = team,
    )

    /**
     * 构造一个最小可用的 ServerGameState，只填必要字段。
     */
    private fun state(
        hands: Map<Int, List<ServerCard>>,
        playerScores: Map<Int, Int>,
        phase: String = "PLAYING",
    ): ServerGameState = ServerGameState(
        phase = phase,
        currentPlayerIndex = 0,
        hands = hands.mapValues { it.value.toMutableList() }.toMutableMap(),
        lastPlayedGroup = null,
        lastPlayerId = null,
        consecutivePasses = 0,
        currentRoundScore = 0,
        teamAScore = 0,
        teamBScore = 0,
        finishOrder = mutableListOf(),
        version = 1,
        playerScores = playerScores.toMutableMap(),
    )

    // ============================================================
    //  AI 速度配置（feature_spec G37 / G38）
    //  测试 ServerRoom / ServerPlayer 上的字段可以被读写并被 toRoomInfo 序列化；
    //  effectiveAiDelayMs() 是 private，本组测试通过黑盒断言其等价行为
    // ============================================================

    @Test
    fun room_serverAiDelayMs_defaultsToProtocolConstant() {
        val room = ServerRoom("r1", "ABCD", "test", "host1", maxPlayers = 6)
        assertEquals(
            com.communicationcard.game.network.GameMessage.AI_DELAY_DEFAULT_MS,
            room.serverAiDelayMs,
            "默认应为 AI_DELAY_DEFAULT_MS（400ms），feature_spec G37"
        )
    }

    @Test
    fun room_serverAiDelayMs_persistsToRoomInfo() {
        val room = ServerRoom("r1", "ABCD", "test", "host1", maxPlayers = 6)
        room.serverAiDelayMs = 100
        assertEquals(100, room.toRoomInfo().serverAiDelayMs)
    }

    @Test
    fun player_takeoverAiDelayMs_defaultsToProtocolConstant() {
        val player = ServerPlayer(
            id = "p1", name = "Alice", session = null, isReady = false,
            isAI = false, seatIndex = 0, team = "TEAM_A"
        )
        assertEquals(
            com.communicationcard.game.network.GameMessage.AI_DELAY_DEFAULT_MS,
            player.takeoverAiDelayMs,
            "默认应为 AI_DELAY_DEFAULT_MS（400ms），feature_spec G38"
        )
    }

    @Test
    fun player_takeoverFields_persistToRoomPlayer() {
        val player = ServerPlayer(
            id = "p1", name = "Alice", session = null, isReady = false,
            isAI = false, seatIndex = 0, team = "TEAM_A"
        )
        player.takeoverAiDelayMs = 1000
        player.isAISubstitute = true
        val rp = player.toRoomPlayer()
        assertEquals(1000, rp.takeoverAiDelayMs)
        assertTrue(rp.isAISubstitute, "isAISubstitute 必须随 RoomUpdate 广播给客户端")
    }

    // ============================================================
    //  effectiveAiDelayMs —— feature_spec G37/G38 行为测试
    //  规约：isAISubstitute=true → player.takeoverAiDelayMs；否则 room.serverAiDelayMs
    // ============================================================

    @Test
    fun effectiveAiDelayMs_filledAi_usesRoomDelay() {
        val room = ServerRoom("r1", "ABCD", "test", "host1", maxPlayers = 6)
        room.serverAiDelayMs = 200
        val aiPlayer = ServerPlayer(
            id = "AI_1", name = "电脑1", session = null, isReady = true,
            isAI = true, seatIndex = 1, team = "TEAM_B"
        )
        aiPlayer.takeoverAiDelayMs = 999  // 不应被读取（不是 substitute）
        room.players.add(aiPlayer)
        assertEquals(200L, gm.effectiveAiDelayMs(room, seatIndex = 1))
    }

    @Test
    fun effectiveAiDelayMs_substitute_usesPlayerTakeoverDelay() {
        val room = ServerRoom("r1", "ABCD", "test", "host1", maxPlayers = 6)
        room.serverAiDelayMs = 200  // 不应被读取（玩家自己的 takeover 优先）
        val substitute = ServerPlayer(
            id = "alice", name = "Alice", session = null, isReady = true,
            isAI = false, seatIndex = 2, team = "TEAM_A"
        )
        substitute.takeoverAiDelayMs = 800
        substitute.isAISubstitute = true
        room.players.add(substitute)
        assertEquals(800L, gm.effectiveAiDelayMs(room, seatIndex = 2))
    }

    @Test
    fun effectiveAiDelayMs_clampsBelowMin() {
        val room = ServerRoom("r1", "ABCD", "test", "host1", maxPlayers = 6)
        room.serverAiDelayMs = 5  // 远低于 MIN_MULTIPLAYER_MS=100
        val aiPlayer = ServerPlayer(
            id = "AI_1", name = "电脑1", session = null, isReady = true,
            isAI = true, seatIndex = 0, team = "TEAM_A"
        )
        room.players.add(aiPlayer)
        // 防御：恶意 / 旧客户端把值推到 < 100ms 时，effectiveAiDelayMs 必须 clamp 到 100
        assertEquals(
            com.communicationcard.game.network.GameMessage.AI_DELAY_MIN_MULTIPLAYER_MS.toLong(),
            gm.effectiveAiDelayMs(room, seatIndex = 0),
            "速度低于 MIN 应被 clamp 到 100ms 防止 AI 雪崩"
        )
    }

    @Test
    fun effectiveAiDelayMs_clampsAboveMax() {
        val room = ServerRoom("r1", "ABCD", "test", "host1", maxPlayers = 6)
        room.serverAiDelayMs = 99999  // 远高于 MAX_MS=1000
        val aiPlayer = ServerPlayer(
            id = "AI_1", name = "电脑1", session = null, isReady = true,
            isAI = true, seatIndex = 0, team = "TEAM_A"
        )
        room.players.add(aiPlayer)
        assertEquals(
            com.communicationcard.game.network.GameMessage.AI_DELAY_MAX_MS.toLong(),
            gm.effectiveAiDelayMs(room, seatIndex = 0)
        )
    }

    // ============================================================
    //  shouldYieldToHumanPlayer —— Codex P2 (PR #53) 防回归
    //  玩家在 AI 延迟期间收回控制权时，AI 必须让位
    // ============================================================

    @Test
    fun shouldYieldToHumanPlayer_aiPlayer_doesNotYield() {
        val ai = ServerPlayer(
            id = "AI_1", name = "电脑1", session = null, isReady = true,
            isAI = true, seatIndex = 0, team = "TEAM_A"
        )
        assertFalse(gm.shouldYieldToHumanPlayer(ai), "纯 AI 座位永远不让位")
    }

    @Test
    fun shouldYieldToHumanPlayer_substitute_doesNotYield() {
        val sub = ServerPlayer(
            id = "alice", name = "Alice", session = null, isReady = true,
            isAI = false, seatIndex = 0, team = "TEAM_A"
        )
        sub.isAISubstitute = true
        assertFalse(
            gm.shouldYieldToHumanPlayer(sub),
            "玩家正在托管中（暂离 / 超时），AI 应继续代打"
        )
    }

    @Test
    fun shouldYieldToHumanPlayer_disconnected_doesNotYield() {
        val player = ServerPlayer(
            id = "alice", name = "Alice", session = null, isReady = true,
            isAI = false, seatIndex = 0, team = "TEAM_A"
        )
        player.isConnected = false
        assertFalse(
            gm.shouldYieldToHumanPlayer(player),
            "玩家已断线，AI 应继续代打到他重连"
        )
    }

    @Test
    fun shouldYieldToHumanPlayer_humanReconnectedAndResumed_yields() {
        val player = ServerPlayer(
            id = "alice", name = "Alice", session = null, isReady = true,
            isAI = false, seatIndex = 0, team = "TEAM_A"
        )
        // 玩家在 AI delay 期间点"我回来了"：isAISubstitute=false + isConnected=true
        player.isAISubstitute = false
        player.isConnected = true
        assertTrue(
            gm.shouldYieldToHumanPlayer(player),
            "Codex P2: 玩家在延迟期内取消托管时，AI 必须让出控制权（feature_spec G34/G35）"
        )
    }

    // ============================================================
    //  PR 2 (admin 后台 · 监控) 新增的 internal API 覆盖
    // ============================================================

    @Test
    fun withRoomLock_serializesConcurrentCalls() = runTest {
        // admin SnapshotBuilder 用 withRoomLock 取房间 snapshot；与同房间内的
        // handleAction 共享同一把 mutex。验证：两个并发协程进入同一房间的
        // withRoomLock 时被串行化（counter 不出现读写竞争）。
        val room = ServerRoom(
            roomId = "test-room",
            roomCode = "ABCD",
            roomName = "TestRoom",
            hostId = "host",
            maxPlayers = 6,
        )
        var counter = 0
        val a = async {
            gm.withRoomLock(room) {
                val seen = counter
                delay(5)  // 假装做点事，给 b 一个抢占窗口
                counter = seen + 1
                seen
            }
        }
        val b = async {
            gm.withRoomLock(room) {
                val seen = counter
                delay(5)
                counter = seen + 1
                seen
            }
        }
        val aSeen = a.await()
        val bSeen = b.await()
        // 若两个 block 真的串行，看到的 counter 起点会不同（0 / 1）；并发则可能都看到 0
        assertEquals(setOf(0, 1), setOf(aSeen, bSeen))
        assertEquals(2, counter, "串行化后 counter 应该恰好 +2")
    }

    @Test
    fun gameEndListener_defaultsToNull() {
        // PR 2：gameEndListener 默认 null，installAdmin 才会设入；
        // 没装 admin 模块时（生产 enableAdmin=false 或运行单机测试）也不能 NPE
        assertNull(gm.gameEndListener)
    }

    @Test
    fun serverGameState_lastActionAt_initializesToCurrentTime() {
        // PR 3: ROOM_STUCK 告警依赖 lastActionAt。新 game state 创建时该字段
        // 应该是当下；后续 handleAction 成功路径会持续刷新。
        val before = System.currentTimeMillis()
        val state = ServerGameState(
            phase = "PLAYING",
            currentPlayerIndex = 0,
            hands = mutableMapOf(),
            lastPlayedGroup = null,
            lastPlayerId = null,
            consecutivePasses = 0,
            currentRoundScore = 0,
            teamAScore = 0,
            teamBScore = 0,
            finishOrder = mutableListOf(),
            version = 0,
        )
        val after = System.currentTimeMillis()
        assertTrue(state.lastActionAt in before..after, "lastActionAt 默认应当是构造时刻")
    }

    @Test
    fun gameEndListener_settable_and_invokable() {
        // PR 2：admin 模块通过 listener 接收 game-end 事件后构建 GameRecord
        // 异步入库。验证 setter / 直接 invoke 都正常
        val captured = mutableListOf<Pair<String, String>>()
        gm.gameEndListener = { room, result ->
            captured.add(room.roomId to result.winner.orEmpty())
        }

        val room = ServerRoom(
            roomId = "room-x", roomCode = "ZZZZ", roomName = "Z",
            hostId = "h", maxPlayers = 6,
        )
        val result = SerializedGameResult(
            winner = "TEAM_A",
            teamAScore = 250,
            teamBScore = 10,
            trigger = "TEAM_ALL_FINISHED",
        )
        gm.gameEndListener?.invoke(room, result)

        assertEquals(1, captured.size)
        assertEquals("room-x" to "TEAM_A", captured.first())

        // tearDown：避免影响其他 test
        gm.gameEndListener = null
    }
}
