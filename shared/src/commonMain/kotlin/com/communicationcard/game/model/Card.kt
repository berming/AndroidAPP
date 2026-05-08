package com.communicationcard.game.model

/**
 * 牌面点数枚举
 * 大小顺序: 大王 > 小王 > 2 > A > K > Q > J > 10 > 9 > 8 > 7 > 6 > 5 > 4 > 3
 */
enum class CardRank(val value: Int, val displayName: String, val scoreValue: Int) {
    THREE(1, "3", 0),
    FOUR(2, "4", 0),
    FIVE(3, "5", 5),      // 5分
    SIX(4, "6", 0),
    SEVEN(5, "7", 0),
    EIGHT(6, "8", 0),
    NINE(7, "9", 0),
    TEN(8, "10", 10),     // 10分
    JACK(9, "J", 0),
    QUEEN(10, "Q", 0),
    KING(11, "K", 10),    // 10分
    ACE(12, "A", 0),
    TWO(13, "2", 0),
    SMALL_JOKER(14, "小王", 0),
    BIG_JOKER(15, "大王", 0);

    companion object {
        fun fromValue(value: Int): CardRank? = entries.find { it.value == value }

        // 获取用于顺子的连续牌（不含大小王）
        val straightRanks = listOf(THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING, ACE, TWO)
    }
}

/**
 * 花色枚举
 */
enum class CardSuit(val displayName: String, val symbol: String) {
    SPADE("黑桃", "♠"),
    HEART("红桃", "♥"),
    CLUB("梅花", "♣"),
    DIAMOND("方块", "♦"),
    JOKER("王", "★");  // 大小王使用
}

/**
 * 单张牌
 */
data class Card(
    val rank: CardRank,
    val suit: CardSuit,
    val deckIndex: Int = 0  // 来自第几副牌 (0-3)
) : Comparable<Card> {

    val isJoker: Boolean
        get() = rank == CardRank.SMALL_JOKER || rank == CardRank.BIG_JOKER

    val scoreValue: Int
        get() = rank.scoreValue

    val displayName: String
        get() = if (isJoker) rank.displayName else "${suit.symbol}${rank.displayName}"

    override fun compareTo(other: Card): Int = rank.value.compareTo(other.rank.value)

    override fun toString(): String = displayName

    companion object {
        fun createJoker(isBig: Boolean, deckIndex: Int = 0): Card {
            return Card(
                rank = if (isBig) CardRank.BIG_JOKER else CardRank.SMALL_JOKER,
                suit = CardSuit.JOKER,
                deckIndex = deckIndex
            )
        }
    }
}

/**
 * 一组牌（用于出牌）
 */
data class CardGroup(
    val cards: List<Card>,
    val type: CardGroupType
) {
    val totalScore: Int
        get() = cards.sumOf { it.scoreValue }

    val size: Int
        get() = cards.size

    // 获取用于比较的主要点数
    val primaryRank: CardRank
        get() = when (type) {
            CardGroupType.SINGLE, CardGroupType.PAIR, CardGroupType.TRIPLE, CardGroupType.BOMB ->
                cards.first().rank
            CardGroupType.STRAIGHT ->
                cards.maxByOrNull { it.rank.value }?.rank ?: cards.first().rank
        }

    override fun toString(): String = cards.joinToString(" ") { it.displayName }
}

/**
 * 牌型枚举
 */
enum class CardGroupType(val displayName: String) {
    SINGLE("单张"),
    PAIR("对子"),
    TRIPLE("三张"),
    STRAIGHT("顺子"),
    BOMB("炸弹");
}
