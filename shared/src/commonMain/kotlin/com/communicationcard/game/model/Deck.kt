package com.communicationcard.game.model

/**
 * 牌组管理类 - 4副扑克牌（共216张）
 */
class Deck {
    private val cards = mutableListOf<Card>()

    init {
        reset()
    }

    /**
     * 重置牌组为4副完整扑克牌
     */
    fun reset() {
        cards.clear()

        // 创建4副牌
        repeat(4) { deckIndex ->
            // 添加普通牌 (3-2, 共13种点数)
            val normalRanks = listOf(
                CardRank.THREE, CardRank.FOUR, CardRank.FIVE, CardRank.SIX,
                CardRank.SEVEN, CardRank.EIGHT, CardRank.NINE, CardRank.TEN,
                CardRank.JACK, CardRank.QUEEN, CardRank.KING, CardRank.ACE, CardRank.TWO
            )

            val suits = listOf(CardSuit.SPADE, CardSuit.HEART, CardSuit.CLUB, CardSuit.DIAMOND)

            for (rank in normalRanks) {
                for (suit in suits) {
                    cards.add(Card(rank, suit, deckIndex))
                }
            }

            // 添加大小王
            cards.add(Card.createJoker(isBig = false, deckIndex = deckIndex))
            cards.add(Card.createJoker(isBig = true, deckIndex = deckIndex))
        }
    }

    /**
     * 洗牌
     */
    fun shuffle() {
        cards.shuffle()
    }

    /**
     * 发牌给指定数量的玩家
     * @param playerCount 玩家数量
     * @return 每个玩家的手牌列表
     */
    fun deal(playerCount: Int): List<MutableList<Card>> {
        require(playerCount in listOf(6, 8, 10, 12)) { "玩家数量必须是6、8、10或12人" }

        shuffle()

        val hands = List(playerCount) { mutableListOf<Card>() }
        var playerIndex = 0

        for (card in cards) {
            hands[playerIndex].add(card)
            playerIndex = (playerIndex + 1) % playerCount
        }

        // 对每个玩家的手牌排序
        for (hand in hands) {
            hand.sortByDescending { it.rank.value }
        }

        return hands
    }

    /**
     * 获取牌组总数
     */
    val size: Int
        get() = cards.size

    /**
     * 计算总分值
     */
    val totalScore: Int
        get() = cards.sumOf { it.scoreValue }

    companion object {
        const val TOTAL_CARDS = 216  // 4副牌 × 54张 = 216张
        const val TOTAL_SCORE = 400  // K(160) + 10(160) + 5(80) = 400分
    }
}
