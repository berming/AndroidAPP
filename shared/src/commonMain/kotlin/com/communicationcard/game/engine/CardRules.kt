package com.communicationcard.game.engine

import com.communicationcard.game.model.*

/**
 * 牌型规则引擎
 * 处理牌型识别、比较和合法性验证
 */
object CardRules {

    /**
     * 识别一组牌的牌型
     * @return CardGroup 如果是有效牌型，否则返回null
     */
    fun identifyCardGroup(cards: List<Card>): CardGroup? {
        if (cards.isEmpty()) return null

        val sortedCards = cards.sortedByDescending { it.rank.value }

        return when {
            isBomb(sortedCards) -> CardGroup(sortedCards, CardGroupType.BOMB)
            isSingle(sortedCards) -> CardGroup(sortedCards, CardGroupType.SINGLE)
            isPair(sortedCards) -> CardGroup(sortedCards, CardGroupType.PAIR)
            isTriple(sortedCards) -> CardGroup(sortedCards, CardGroupType.TRIPLE)
            else -> null
        }
    }

    /**
     * 判断是否为单张
     */
    private fun isSingle(cards: List<Card>): Boolean = cards.size == 1

    /**
     * 判断是否为对子
     */
    private fun isPair(cards: List<Card>): Boolean {
        return cards.size == 2 && cards[0].rank == cards[1].rank
    }

    /**
     * 判断是否为三张
     */
    private fun isTriple(cards: List<Card>): Boolean {
        return cards.size == 3 && cards.all { it.rank == cards[0].rank }
    }

    /**
     * 判断是否为炸弹（4张或以上相同点数）
     */
    private fun isBomb(cards: List<Card>): Boolean {
        return cards.size >= 4 && cards.all { it.rank == cards[0].rank }
    }

    /**
     * 比较两组牌，判断cards2是否能压过cards1
     * @param lastPlay 上一手牌
     * @param currentPlay 当前要出的牌
     * @return true如果currentPlay能压过lastPlay
     */
    fun canBeat(lastPlay: CardGroup?, currentPlay: CardGroup): Boolean {
        // 如果没有上家出牌，任何有效牌型都可以出
        if (lastPlay == null) return true

        // 炸弹可以压制任何非炸弹牌型
        if (currentPlay.type == CardGroupType.BOMB && lastPlay.type != CardGroupType.BOMB) {
            return true
        }

        // 如果上家是炸弹，当前必须也是炸弹才能压
        if (lastPlay.type == CardGroupType.BOMB && currentPlay.type != CardGroupType.BOMB) {
            return false
        }

        // 炸弹比较：先比张数，再比点数
        if (lastPlay.type == CardGroupType.BOMB && currentPlay.type == CardGroupType.BOMB) {
            return if (currentPlay.size != lastPlay.size) {
                currentPlay.size > lastPlay.size
            } else {
                currentPlay.primaryRank.value > lastPlay.primaryRank.value
            }
        }

        // 非炸弹牌型：必须类型相同且张数相同
        if (currentPlay.type != lastPlay.type || currentPlay.size != lastPlay.size) {
            return false
        }

        // 比较点数
        return currentPlay.primaryRank.value > lastPlay.primaryRank.value
    }

    /**
     * 从手牌中找出所有能压过指定牌的出牌组合
     */
    fun findValidPlays(hand: List<Card>, lastPlay: CardGroup?): List<CardGroup> {
        val validPlays = mutableListOf<CardGroup>()

        // 如果没有上家，可以出任何有效牌型
        if (lastPlay == null) {
            validPlays.addAll(findAllSingles(hand))
            validPlays.addAll(findAllPairs(hand))
            validPlays.addAll(findAllTriples(hand))
            validPlays.addAll(findAllBombs(hand))
            return validPlays
        }

        // 根据上家牌型找对应的更大牌
        when (lastPlay.type) {
            CardGroupType.SINGLE -> {
                validPlays.addAll(findBiggerSingles(hand, lastPlay))
                validPlays.addAll(findAllBombs(hand))
            }
            CardGroupType.PAIR -> {
                validPlays.addAll(findBiggerPairs(hand, lastPlay))
                validPlays.addAll(findAllBombs(hand))
            }
            CardGroupType.TRIPLE -> {
                validPlays.addAll(findBiggerTriples(hand, lastPlay))
                validPlays.addAll(findAllBombs(hand))
            }
            CardGroupType.BOMB -> {
                validPlays.addAll(findBiggerBombs(hand, lastPlay))
            }
        }

        return validPlays
    }

    // ========== 查找牌型的辅助方法 ==========

    private fun findAllSingles(hand: List<Card>): List<CardGroup> {
        return hand.map { CardGroup(listOf(it), CardGroupType.SINGLE) }
    }

    private fun findAllPairs(hand: List<Card>): List<CardGroup> {
        val pairs = mutableListOf<CardGroup>()
        val grouped = hand.groupBy { it.rank }

        for ((_, cards) in grouped) {
            if (cards.size >= 2) {
                // 取前两张组成对子
                pairs.add(CardGroup(cards.take(2), CardGroupType.PAIR))
            }
        }
        return pairs
    }

    private fun findAllTriples(hand: List<Card>): List<CardGroup> {
        val triples = mutableListOf<CardGroup>()
        val grouped = hand.groupBy { it.rank }

        for ((_, cards) in grouped) {
            if (cards.size >= 3) {
                triples.add(CardGroup(cards.take(3), CardGroupType.TRIPLE))
            }
        }
        return triples
    }

    private fun findAllBombs(hand: List<Card>): List<CardGroup> {
        val bombs = mutableListOf<CardGroup>()
        val grouped = hand.groupBy { it.rank }

        for ((_, cards) in grouped) {
            if (cards.size >= 4) {
                // 可以出4张、5张...等不同大小的炸弹
                for (size in 4..cards.size) {
                    bombs.add(CardGroup(cards.take(size), CardGroupType.BOMB))
                }
            }
        }
        return bombs.sortedWith(compareBy({ it.size }, { it.primaryRank.value }))
    }

    private fun findBiggerSingles(hand: List<Card>, lastPlay: CardGroup): List<CardGroup> {
        return hand.filter { it.rank.value > lastPlay.primaryRank.value }
            .map { CardGroup(listOf(it), CardGroupType.SINGLE) }
    }

    private fun findBiggerPairs(hand: List<Card>, lastPlay: CardGroup): List<CardGroup> {
        val pairs = mutableListOf<CardGroup>()
        val grouped = hand.groupBy { it.rank }

        for ((rank, cards) in grouped) {
            if (cards.size >= 2 && rank.value > lastPlay.primaryRank.value) {
                pairs.add(CardGroup(cards.take(2), CardGroupType.PAIR))
            }
        }
        return pairs
    }

    private fun findBiggerTriples(hand: List<Card>, lastPlay: CardGroup): List<CardGroup> {
        val triples = mutableListOf<CardGroup>()
        val grouped = hand.groupBy { it.rank }

        for ((rank, cards) in grouped) {
            if (cards.size >= 3 && rank.value > lastPlay.primaryRank.value) {
                triples.add(CardGroup(cards.take(3), CardGroupType.TRIPLE))
            }
        }
        return triples
    }

    private fun findBiggerBombs(hand: List<Card>, lastPlay: CardGroup): List<CardGroup> {
        val bombs = findAllBombs(hand)
        return bombs.filter { bomb ->
            // 张数更多，或张数相同但点数更大
            bomb.size > lastPlay.size ||
                    (bomb.size == lastPlay.size && bomb.primaryRank.value > lastPlay.primaryRank.value)
        }
    }

    /**
     * 计算一组牌的总分值
     */
    fun calculateScore(cards: List<Card>): Int = cards.sumOf { it.scoreValue }
}
