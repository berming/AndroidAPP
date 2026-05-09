package com.communicationcard.game.engine

import com.communicationcard.game.model.Card
import com.communicationcard.game.model.CardGroup
import com.communicationcard.game.model.CardGroupType
import com.communicationcard.game.model.CardRank
import com.communicationcard.game.model.CardSuit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CardRules 单元测试 —— 关键路径强制 TDD（CLAUDE.md 第三章）。
 *
 * 防回归覆盖（详见 docs/regressions.md）：
 *  - #2 游戏卡死 L1: canBeat 比较不同张数炸弹优先级颠倒
 *  - #1 CardSuit 枚举不匹配的反向保险（这里直接断言枚举存在性）
 *
 * 设计原则：
 *  - 每个测试只断言一件事；失败信息能直接定位问题
 *  - 使用 `card(rank)` / `group(...)` helper 收敛构造样板代码
 *  - 不测试 private helper（isStraight 等），只测公开 API（identifyCardGroup,
 *    canBeat, findValidPlays）。私有逻辑通过公开 API 间接覆盖
 */
class CardRulesTest {

    // ============================================================
    //  identifyCardGroup
    // ============================================================

    @Test
    fun identifyCardGroup_emptyList_returnsNull() {
        assertNull(CardRules.identifyCardGroup(emptyList()))
    }

    @Test
    fun identifyCardGroup_singleCard_returnsSingle() {
        val cards = listOf(card(CardRank.SEVEN))
        val group = CardRules.identifyCardGroup(cards)
        assertNotNull(group)
        assertEquals(CardGroupType.SINGLE, group!!.type)
    }

    @Test
    fun identifyCardGroup_twoSameRank_returnsPair() {
        val cards = listOf(card(CardRank.NINE), card(CardRank.NINE, CardSuit.HEART))
        assertEquals(CardGroupType.PAIR, CardRules.identifyCardGroup(cards)!!.type)
    }

    @Test
    fun identifyCardGroup_twoDifferentRanks_returnsNull() {
        val cards = listOf(card(CardRank.NINE), card(CardRank.TEN))
        assertNull(CardRules.identifyCardGroup(cards))
    }

    @Test
    fun identifyCardGroup_threeSameRank_returnsTriple() {
        val cards = listOf(
            card(CardRank.QUEEN),
            card(CardRank.QUEEN, CardSuit.HEART),
            card(CardRank.QUEEN, CardSuit.CLUB),
        )
        assertEquals(CardGroupType.TRIPLE, CardRules.identifyCardGroup(cards)!!.type)
    }

    @Test
    fun identifyCardGroup_fourSameRank_returnsBomb() {
        val cards = listOf(
            card(CardRank.SEVEN),
            card(CardRank.SEVEN, CardSuit.HEART),
            card(CardRank.SEVEN, CardSuit.CLUB),
            card(CardRank.SEVEN, CardSuit.DIAMOND),
        )
        assertEquals(CardGroupType.BOMB, CardRules.identifyCardGroup(cards)!!.type)
    }

    @Test
    fun identifyCardGroup_fiveSameRank_returnsBomb() {
        // 6 副牌可能凑齐 5+ 同点 —— 必须识别为大炸弹
        val cards = (0..4).map { card(CardRank.FIVE, CardSuit.SPADE, deckIndex = it) }
        val group = CardRules.identifyCardGroup(cards)
        assertEquals(CardGroupType.BOMB, group!!.type)
        assertEquals(5, group.size)
    }

    @Test
    fun identifyCardGroup_fiveConsecutive_returnsStraight() {
        val cards = listOf(
            card(CardRank.FIVE),
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.NINE),
        )
        assertEquals(CardGroupType.STRAIGHT, CardRules.identifyCardGroup(cards)!!.type)
    }

    @Test
    fun identifyCardGroup_fourConsecutive_returnsNull() {
        // 顺子需要至少 5 张
        val cards = listOf(
            card(CardRank.FIVE),
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
        )
        assertNull(CardRules.identifyCardGroup(cards))
    }

    @Test
    fun identifyCardGroup_straightContainingJoker_returnsNull() {
        val cards = listOf(
            card(CardRank.JACK),
            card(CardRank.QUEEN),
            card(CardRank.KING),
            card(CardRank.ACE),
            Card.createJoker(isBig = false),
        )
        assertNull(CardRules.identifyCardGroup(cards))
    }

    @Test
    fun identifyCardGroup_nonConsecutiveSequence_returnsNull() {
        val cards = listOf(
            card(CardRank.FIVE),
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.TEN), // 跳过 9
        )
        assertNull(CardRules.identifyCardGroup(cards))
    }

    // ============================================================
    //  canBeat —— 无上家 / 类型匹配 / 点数比较
    // ============================================================

    @Test
    fun canBeat_noLastPlay_anyGroupBeats() {
        assertTrue(CardRules.canBeat(null, single(CardRank.THREE)))
        assertTrue(CardRules.canBeat(null, bomb(CardRank.THREE, count = 4)))
    }

    @Test
    fun canBeat_singleBiggerRank_beats() {
        assertTrue(CardRules.canBeat(single(CardRank.NINE), single(CardRank.JACK)))
    }

    @Test
    fun canBeat_singleSameRank_doesNotBeat() {
        // 必须严格大于；相等不算压
        assertFalse(CardRules.canBeat(single(CardRank.NINE), single(CardRank.NINE, CardSuit.HEART)))
    }

    @Test
    fun canBeat_singleSmallerRank_doesNotBeat() {
        assertFalse(CardRules.canBeat(single(CardRank.JACK), single(CardRank.NINE)))
    }

    @Test
    fun canBeat_pairBiggerRank_beats() {
        assertTrue(CardRules.canBeat(pair(CardRank.SEVEN), pair(CardRank.JACK)))
    }

    @Test
    fun canBeat_pairVsSingle_doesNotBeat() {
        // 张数不同 / 类型不同的非炸弹牌型不能压
        assertFalse(CardRules.canBeat(single(CardRank.THREE), pair(CardRank.ACE)))
        assertFalse(CardRules.canBeat(pair(CardRank.THREE), single(CardRank.ACE)))
    }

    @Test
    fun canBeat_tripleBiggerRank_beats() {
        assertTrue(CardRules.canBeat(triple(CardRank.SIX), triple(CardRank.QUEEN)))
    }

    @Test
    fun canBeat_straightSameLengthBiggerHead_beats() {
        val low = group(
            card(CardRank.FIVE),
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.NINE),
        )
        val high = group(
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.NINE),
            card(CardRank.TEN),
        )
        assertTrue(CardRules.canBeat(low, high))
    }

    @Test
    fun canBeat_straightDifferentLength_doesNotBeat() {
        val short = group(
            card(CardRank.FIVE),
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.NINE),
        )
        val long = group(
            card(CardRank.FIVE),
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.NINE),
            card(CardRank.TEN),
        )
        // 张数不同的同型牌（顺子）不能互压（炸弹除外）
        assertFalse(CardRules.canBeat(short, long))
    }

    // ============================================================
    //  炸弹 hierarchy —— docs/regressions.md #2 L1 防回归
    // ============================================================

    @Test
    fun canBeat_bombBeatsSingle() {
        assertTrue(CardRules.canBeat(single(CardRank.ACE), bomb(CardRank.THREE, count = 4)))
    }

    @Test
    fun canBeat_bombBeatsPair() {
        assertTrue(CardRules.canBeat(pair(CardRank.ACE), bomb(CardRank.THREE, count = 4)))
    }

    @Test
    fun canBeat_bombBeatsTriple() {
        assertTrue(CardRules.canBeat(triple(CardRank.ACE), bomb(CardRank.FOUR, count = 4)))
    }

    @Test
    fun canBeat_bombBeatsStraight() {
        val str = group(
            card(CardRank.SIX),
            card(CardRank.SEVEN),
            card(CardRank.EIGHT),
            card(CardRank.NINE),
            card(CardRank.TEN),
        )
        assertTrue(CardRules.canBeat(str, bomb(CardRank.FOUR, count = 4)))
    }

    @Test
    fun canBeat_singleDoesNotBeatBomb() {
        // 上家是炸弹 → 非炸弹一律压不过
        assertFalse(CardRules.canBeat(bomb(CardRank.THREE, count = 4), single(CardRank.BIG_JOKER, CardSuit.JOKER)))
    }

    @Test
    fun canBeat_pairDoesNotBeatBomb() {
        assertFalse(CardRules.canBeat(bomb(CardRank.THREE, count = 4), pair(CardRank.ACE)))
    }

    @Test
    fun canBeat_bombSameSizeBiggerRank_beats() {
        assertTrue(CardRules.canBeat(bomb(CardRank.THREE, count = 4), bomb(CardRank.KING, count = 4)))
    }

    @Test
    fun canBeat_bombSameSizeSmallerRank_doesNotBeat() {
        assertFalse(CardRules.canBeat(bomb(CardRank.KING, count = 4), bomb(CardRank.THREE, count = 4)))
    }

    @Test
    fun canBeat_bombSameSizeSameRank_doesNotBeat() {
        assertFalse(CardRules.canBeat(bomb(CardRank.SEVEN, count = 4), bomb(CardRank.SEVEN, count = 4, deckShift = 4)))
    }

    /**
     * 防回归：docs/regressions.md #2 L1 —— "AI 选出的更大炸弹被拒"。
     * 5×3 必须能压 4×10：张数优先级高于点数。
     */
    @Test
    fun canBeat_biggerBombSizeWinsOverRank_regressions2() {
        val bombSmall = bomb(CardRank.TEN, count = 4)        // 4×10
        val bombLarge = bomb(CardRank.THREE, count = 5)      // 5×3
        assertTrue(
            CardRules.canBeat(bombSmall, bombLarge),
            "5×3 应该压 4×10（炸弹张数优先于点数；regressions #2 L1）",
        )
    }

    @Test
    fun canBeat_biggerBombBeatsAllSmallerBombs() {
        // 6×3 压 5×K
        val sixBomb = bomb(CardRank.THREE, count = 6)
        val fiveBomb = bomb(CardRank.KING, count = 5)
        assertTrue(CardRules.canBeat(fiveBomb, sixBomb))
    }

    @Test
    fun canBeat_smallerBombSize_doesNotBeat() {
        val sixBomb = bomb(CardRank.THREE, count = 6)
        val fourBomb = bomb(CardRank.BIG_JOKER, count = 4, suit = CardSuit.JOKER)
        // 4×大王 压不过 6×3（张数优先）
        assertFalse(CardRules.canBeat(sixBomb, fourBomb))
    }

    // ============================================================
    //  大小王
    // ============================================================

    @Test
    fun canBeat_bigJokerBeatsSmallJoker() {
        val small = single(CardRank.SMALL_JOKER, CardSuit.JOKER)
        val big = single(CardRank.BIG_JOKER, CardSuit.JOKER)
        assertTrue(CardRules.canBeat(small, big))
    }

    @Test
    fun canBeat_bigJokerBeatsTwo() {
        // 大小关系: 大王(15) > 小王(14) > 2(13) > A(12) > ...
        val two = single(CardRank.TWO)
        val big = single(CardRank.BIG_JOKER, CardSuit.JOKER)
        assertTrue(CardRules.canBeat(two, big))
    }

    // ============================================================
    //  findValidPlays —— 黑盒覆盖
    // ============================================================

    @Test
    fun findValidPlays_noLastPlay_returnsAllValidGroupings() {
        val hand = listOf(
            card(CardRank.SEVEN),
            card(CardRank.SEVEN, CardSuit.HEART),
            card(CardRank.JACK),
        )
        val plays = CardRules.findValidPlays(hand, lastPlay = null)
        assertTrue(plays.any { it.type == CardGroupType.SINGLE }, "有 SINGLE")
        assertTrue(plays.any { it.type == CardGroupType.PAIR }, "有 PAIR")
    }

    @Test
    fun findValidPlays_lastSingleSeven_returnsOnlyBiggerSinglesAndBombs() {
        val hand = listOf(
            card(CardRank.SIX),                  // 不能压 7（小）
            card(CardRank.JACK),                 // 能压 7
            card(CardRank.JACK, CardSuit.HEART),
            card(CardRank.JACK, CardSuit.CLUB),
            card(CardRank.JACK, CardSuit.DIAMOND), // 4×J 是炸弹
        )
        val plays = CardRules.findValidPlays(hand, lastPlay = single(CardRank.SEVEN))

        // 应包含: 4 个独立的 J 单张 + 1 个 4×J 炸弹
        assertTrue(
            plays.any { it.type == CardGroupType.SINGLE && it.cards[0].rank == CardRank.JACK },
            "含更大单张",
        )
        assertTrue(plays.any { it.type == CardGroupType.BOMB }, "含炸弹")
        assertFalse(plays.any { it.cards[0].rank == CardRank.SIX }, "不含小于上家的单张 6")
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    private fun card(rank: CardRank, suit: CardSuit = CardSuit.SPADE, deckIndex: Int = 0): Card =
        Card(rank, suit, deckIndex)

    private fun single(rank: CardRank, suit: CardSuit = CardSuit.SPADE): CardGroup =
        CardGroup(listOf(card(rank, suit)), CardGroupType.SINGLE)

    private fun pair(rank: CardRank): CardGroup =
        CardGroup(
            listOf(card(rank, CardSuit.SPADE), card(rank, CardSuit.HEART)),
            CardGroupType.PAIR,
        )

    private fun triple(rank: CardRank): CardGroup =
        CardGroup(
            listOf(
                card(rank, CardSuit.SPADE),
                card(rank, CardSuit.HEART),
                card(rank, CardSuit.CLUB),
            ),
            CardGroupType.TRIPLE,
        )

    /**
     * 构造任意张数的炸弹（用 deckIndex 区分多副牌的同点同花）。
     * deckShift 让两次调用同 rank+count 时 deckIndex 不冲突。
     */
    private fun bomb(
        rank: CardRank,
        count: Int,
        suit: CardSuit = CardSuit.SPADE,
        deckShift: Int = 0,
    ): CardGroup {
        require(count >= 4) { "bomb 至少 4 张" }
        val cards = (0 until count).map { card(rank, suit, deckIndex = it + deckShift) }
        return CardGroup(cards, CardGroupType.BOMB)
    }

    private fun group(vararg cards: Card): CardGroup =
        CardRules.identifyCardGroup(cards.toList())
            ?: error("invalid CardGroup in test setup: ${cards.toList()}")
}
