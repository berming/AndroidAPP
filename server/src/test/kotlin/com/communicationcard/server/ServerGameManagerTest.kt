package com.communicationcard.server

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
 * 与客户端 CardRulesTest（apps/communication-card/.../engine/CardRulesTest.kt）
 * **测试用例必须保持同义**——这就是 CLAUDE.md 约束 1 的等价契约。任一边
 * 改了行为却没改另一边，CI 红 → 强迫两端同步。
 *
 * （PR-H3 把 server 并入 :shared 后，本测试退化为 :shared 单一测试。）
 */
class ServerGameManagerTest {

    private val gm = ServerGameManager()

    // ============================================================
    //  identifyCardGroup
    // ============================================================

    @Test
    fun identifyCardGroup_emptyList_returnsNull() {
        assertNull(gm.identifyCardGroup(emptyList()))
    }

    @Test
    fun identifyCardGroup_singleCard_returnsSingle() {
        val g = gm.identifyCardGroup(listOf(card("7")))
        assertNotNull(g)
        assertEquals("SINGLE", g.type)
        assertEquals("7", g.primaryRank)
    }

    @Test
    fun identifyCardGroup_pairSameRank_returnsPair() {
        val g = gm.identifyCardGroup(listOf(card("9", "SPADE"), card("9", "HEART")))
        assertEquals("PAIR", g!!.type)
    }

    @Test
    fun identifyCardGroup_threeSameRank_returnsTriple() {
        val g = gm.identifyCardGroup(
            listOf(card("Q", "SPADE"), card("Q", "HEART"), card("Q", "CLUB")),
        )
        assertEquals("TRIPLE", g!!.type)
    }

    @Test
    fun identifyCardGroup_fourSameRank_returnsBomb() {
        val g = gm.identifyCardGroup(
            listOf(card("7", "SPADE"), card("7", "HEART"), card("7", "CLUB"), card("7", "DIAMOND")),
        )
        assertEquals("BOMB", g!!.type)
    }

    @Test
    fun identifyCardGroup_fiveSameRank_returnsBomb() {
        val cards = (0..4).map { card("5", "SPADE", deckIndex = it) }
        val g = gm.identifyCardGroup(cards)
        assertEquals("BOMB", g!!.type)
        assertEquals(5, g.cards.size)
    }

    @Test
    fun identifyCardGroup_fiveConsecutive_returnsStraight() {
        val cards = listOf(card("5"), card("6"), card("7"), card("8"), card("9"))
        val g = gm.identifyCardGroup(cards)
        assertEquals("STRAIGHT", g!!.type)
    }

    @Test
    fun identifyCardGroup_fourConsecutive_returnsNull() {
        // 顺子至少 5 张
        val cards = listOf(card("5"), card("6"), card("7"), card("8"))
        assertNull(gm.identifyCardGroup(cards))
    }

    /**
     * 防回归 #1（CardSuit 枚举不匹配）：用 :shared 大写值"SPADE/HEART/CLUB/DIAMOND"
     * 应正常识别，对小写"spade"则不应能错误生成有效组（这条由 client 协议层
     * 校验，不在本测试范围 —— 但记录以避免未来无意宽松）。
     */
    @Test
    fun identifyCardGroup_acceptsUppercaseSuit_regressions1() {
        val g = gm.identifyCardGroup(listOf(card("8", "SPADE"), card("8", "HEART")))
        assertEquals("PAIR", g!!.type)
    }

    // ============================================================
    //  canBeat —— 基础矩阵
    // ============================================================

    @Test
    fun canBeat_singleBiggerRank_beats() {
        assertTrue(gm.canBeat(single("9"), single("J")))
    }

    @Test
    fun canBeat_singleSameRank_doesNotBeat() {
        assertFalse(gm.canBeat(single("9"), single("9")))
    }

    @Test
    fun canBeat_singleSmallerRank_doesNotBeat() {
        assertFalse(gm.canBeat(single("J"), single("9")))
    }

    @Test
    fun canBeat_pairBiggerRank_beats() {
        assertTrue(gm.canBeat(pair("7"), pair("J")))
    }

    @Test
    fun canBeat_pairVsSingle_doesNotBeat() {
        assertFalse(gm.canBeat(single("3"), pair("A")))
        assertFalse(gm.canBeat(pair("3"), single("A")))
    }

    @Test
    fun canBeat_tripleBiggerRank_beats() {
        assertTrue(gm.canBeat(triple("6"), triple("Q")))
    }

    // ============================================================
    //  炸弹 hierarchy —— docs/regressions.md #2 防回归
    // ============================================================

    @Test
    fun canBeat_bombBeatsSingle() {
        assertTrue(gm.canBeat(single("A"), bomb("3", count = 4)))
    }

    @Test
    fun canBeat_bombBeatsPair() {
        assertTrue(gm.canBeat(pair("A"), bomb("4", count = 4)))
    }

    @Test
    fun canBeat_singleDoesNotBeatBomb() {
        assertFalse(gm.canBeat(bomb("3", count = 4), single("BIG_JOKER")))
    }

    @Test
    fun canBeat_bombSameSizeBiggerRank_beats() {
        assertTrue(gm.canBeat(bomb("3", count = 4), bomb("K", count = 4)))
    }

    @Test
    fun canBeat_bombSameSizeSmallerRank_doesNotBeat() {
        assertFalse(gm.canBeat(bomb("K", count = 4), bomb("3", count = 4)))
    }

    /**
     * 防回归：docs/regressions.md #2 L1。
     * 旧实现严格要求 size 相同，导致 5+ 张大炸弹被错误拒绝、AI 卡死。
     * 5×3 必须能压 4×10：张数优先级高于点数。
     */
    @Test
    fun canBeat_biggerBombSizeWinsOverRank_regressions2() {
        val bombSmall = bomb("10", count = 4)        // 4×10
        val bombLarge = bomb("3", count = 5)         // 5×3
        assertTrue(
            gm.canBeat(bombSmall, bombLarge),
            "5×3 应该压 4×10（炸弹张数优先于点数；regressions #2 L1）",
        )
    }

    @Test
    fun canBeat_biggerBombBeatsAllSmallerBombs() {
        val sixBomb = bomb("3", count = 6)
        val fiveBomb = bomb("K", count = 5)
        assertTrue(gm.canBeat(fiveBomb, sixBomb))
    }

    @Test
    fun canBeat_smallerBombSize_doesNotBeat() {
        val sixBomb = bomb("3", count = 6)
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
        assertTrue(gm.canBeat(single("2"), single("BIG_JOKER", "JOKER")))
    }

    @Test
    fun getRankValue_jokers_outrank2() {
        // 大小关系: 大王 > 小王 > 2 > A > K > ...
        assertTrue(
            gm.getRankValue("BIG_JOKER") > gm.getRankValue("SMALL_JOKER"),
            "BIG_JOKER 应该 > SMALL_JOKER",
        )
        assertTrue(gm.getRankValue("SMALL_JOKER") > gm.getRankValue("2"))
        assertTrue(gm.getRankValue("2") > gm.getRankValue("A"))
        assertTrue(gm.getRankValue("A") > gm.getRankValue("K"))
        assertTrue(gm.getRankValue("3") < gm.getRankValue("4"))
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
            sp(seat = 0, team = "A"),
            sp(seat = 1, team = "A"),
            sp(seat = 2, team = "A"),
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
                4 to listOf(card("5"), card("10"), card("10")), // b2 手牌 = 5+10+10 = 25 分
                5 to listOf(card("5"), card("K")),              // b3 手牌 = 5+10 = 15 分
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
        val winner = listOf(sp(0, "A"), sp(1, "A"), sp(2, "A"))
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
        val winner = listOf(sp(0, "A"), sp(1, "A"), sp(2, "A"))
        val loser = listOf(sp(3, "B"), sp(4, "B"), sp(5, "B"))
        val state = state(
            hands = mapOf(
                0 to listOf(), 1 to listOf(), 2 to listOf(),
                3 to listOf(),
                4 to listOf(card("5"), card("10")),  // 15 分
                5 to listOf(card("K")),               // 10 分
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
}
