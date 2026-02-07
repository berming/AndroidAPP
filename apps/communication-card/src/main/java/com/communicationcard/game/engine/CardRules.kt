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
            isStraight(sortedCards) -> CardGroup(sortedCards, CardGroupType.STRAIGHT)
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
     * 判断是否为顺子（5张或以上连续，不含大小王）
     */
    private fun isStraight(cards: List<Card>): Boolean {
        if (cards.size < 5) return false

        // 顺子不能包含大小王
        if (cards.any { it.isJoker }) return false

        val ranks = cards.map { it.rank }.distinct().sortedBy { it.value }

        // 必须是连续的不同点数
        if (ranks.size != cards.size) return false

        // 检查是否连续
        for (i in 0 until ranks.size - 1) {
            val current = ranks[i].value
            val next = ranks[i + 1].value

            // 检查是否连续（支持 Q-K-A-2-3 这样的循环顺子）
            if (next - current != 1) {
                // 检查是否是 2->3 的循环（A之后是2，2之后是3，但3的value是1）
                // 实际上我们的value设计: 3=1, 2=13, A=12
                // 所以 A(12) -> 2(13) 差值为1，是连续的
                // 但 2(13) -> 3(1) 不连续，需要特殊处理循环顺子
                return checkCircularStraight(cards)
            }
        }

        return true
    }

    /**
     * 检查循环顺子（如 Q-K-A-2-3）
     */
    private fun checkCircularStraight(cards: List<Card>): Boolean {
        if (cards.any { it.isJoker }) return false

        val ranks = cards.map { it.rank }.distinct()
        if (ranks.size != cards.size) return false

        // 将顺子视为环形：3-4-5-6-7-8-9-10-J-Q-K-A-2-3-4-...
        // 使用value: 3=1, 4=2, ..., A=12, 2=13
        // 环形连接: 13(2) -> 1(3)

        val values = ranks.map { it.value }.sorted()

        // 检查普通连续
        var isNormalStraight = true
        for (i in 0 until values.size - 1) {
            if (values[i + 1] - values[i] != 1) {
                isNormalStraight = false
                break
            }
        }
        if (isNormalStraight) return true

        // 检查跨2-3的循环顺子
        // 如果包含2(13)和3(1)，尝试将3的值视为14
        if (values.contains(13) && values.contains(1)) {
            val adjustedValues = values.map { if (it <= 13 - values.size + 1 && it < 13) it + 13 else it }.sorted()
            var isCircular = true
            for (i in 0 until adjustedValues.size - 1) {
                if (adjustedValues[i + 1] - adjustedValues[i] != 1) {
                    isCircular = false
                    break
                }
            }
            if (isCircular) return true
        }

        return false
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
            validPlays.addAll(findAllStraights(hand))
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
            CardGroupType.STRAIGHT -> {
                validPlays.addAll(findBiggerStraights(hand, lastPlay))
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

    private fun findAllStraights(hand: List<Card>): List<CardGroup> {
        val straights = mutableListOf<CardGroup>()

        // 获取非王牌的手牌
        val nonJokerCards = hand.filter { !it.isJoker }
        val grouped = nonJokerCards.groupBy { it.rank }

        // 获取有牌的点数（按顺序）
        val availableRanks = CardRank.straightRanks.filter { grouped.containsKey(it) }

        // 尝试找出所有可能的顺子（5张及以上）
        for (startIndex in availableRanks.indices) {
            for (length in 5..availableRanks.size - startIndex) {
                val endIndex = startIndex + length
                if (endIndex > availableRanks.size) break

                val selectedRanks = availableRanks.subList(startIndex, endIndex)

                // 检查是否连续
                var isConsecutive = true
                for (i in 0 until selectedRanks.size - 1) {
                    val currentValue = selectedRanks[i].value
                    val nextValue = selectedRanks[i + 1].value
                    if (nextValue - currentValue != 1) {
                        // 检查循环: 2(13) -> 3(1)
                        if (!(currentValue == 13 && nextValue == 1)) {
                            isConsecutive = false
                            break
                        }
                    }
                }

                if (isConsecutive) {
                    val cards = selectedRanks.mapNotNull { rank -> grouped[rank]?.firstOrNull() }
                    if (cards.size == selectedRanks.size) {
                        val cardGroup = identifyCardGroup(cards)
                        if (cardGroup != null && cardGroup.type == CardGroupType.STRAIGHT) {
                            straights.add(cardGroup)
                        }
                    }
                }
            }
        }

        return straights
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

    private fun findBiggerStraights(hand: List<Card>, lastPlay: CardGroup): List<CardGroup> {
        val straights = findAllStraights(hand)
        return straights.filter { straight ->
            straight.size == lastPlay.size && straight.primaryRank.value > lastPlay.primaryRank.value
        }
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
