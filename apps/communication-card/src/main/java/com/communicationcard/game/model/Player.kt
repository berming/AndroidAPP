package com.communicationcard.game.model

/**
 * 玩家类型
 */
enum class PlayerType {
    HUMAN,          // 本地人类玩家
    AI,             // AI玩家
    REMOTE,         // 远程人类玩家
    AI_SUBSTITUTE   // AI代打（玩家断线时临时替代）
}

/**
 * 队伍枚举
 */
enum class Team(val displayName: String) {
    TEAM_A("红队"),
    TEAM_B("蓝队")
}

/**
 * 玩家
 */
data class Player(
    val id: Int,
    val name: String,
    val type: PlayerType,
    val team: Team,
    val remoteId: String? = null,       // 网络玩家ID
    val isConnected: Boolean = true,    // 连接状态
    val originalType: PlayerType = type // 原始类型（用于AI代打后恢复）
) {
    // 手牌
    private val _hand = mutableListOf<Card>()
    val hand: List<Card> get() = _hand.toList()

    // 已收分（赢得的牌中的分值）
    private var _collectedScore: Int = 0
    private var _collectedScoreOverride: Int? = null
    val collectedScore: Int get() = _collectedScoreOverride ?: _collectedScore

    fun setCollectedScoreOverride(score: Int) {
        _collectedScoreOverride = score
    }

    // 已收的牌（赢得的牌）
    private val _collectedCards = mutableListOf<Card>()
    val collectedCards: List<Card> get() = _collectedCards.toList()

    // 是否已走完（出完所有手牌）
    private var _hasFinishedOverride: Boolean? = null
    val hasFinished: Boolean get() = _hasFinishedOverride ?: _hand.isEmpty()

    fun setHasFinishedOverride(finished: Boolean) {
        _hasFinishedOverride = finished
    }

    // 手牌数量（可被覆盖，用于多人模式显示远程玩家牌数）
    private var _handSizeOverride: Int? = null
    val handSize: Int get() = _handSizeOverride ?: _hand.size

    fun setHandSizeOverride(size: Int) {
        _handSizeOverride = size
    }

    // 手牌总分值
    val handScore: Int get() = _hand.sumOf { it.scoreValue }

    /**
     * 设置初始手牌
     */
    fun setInitialHand(cards: List<Card>) {
        _hand.clear()
        _hand.addAll(cards.sortedByDescending { it.rank.value })
        _collectedScore = 0
        _collectedCards.clear()
    }

    /**
     * 出牌
     * @param cards 要出的牌
     * @return 是否出牌成功
     */
    fun playCards(cards: List<Card>): Boolean {
        // 检查手牌中是否有这些牌
        val handCopy = _hand.toMutableList()
        for (card in cards) {
            val index = handCopy.indexOfFirst {
                it.rank == card.rank && it.suit == card.suit && it.deckIndex == card.deckIndex
            }
            if (index == -1) return false
            handCopy.removeAt(index)
        }

        // 从手牌中移除
        for (card in cards) {
            val index = _hand.indexOfFirst {
                it.rank == card.rank && it.suit == card.suit && it.deckIndex == card.deckIndex
            }
            if (index != -1) {
                _hand.removeAt(index)
            }
        }
        return true
    }

    /**
     * 收牌（赢得一轮后获得牌）
     */
    fun collectCards(cards: List<Card>) {
        _collectedCards.addAll(cards)
        _collectedScore += cards.sumOf { it.scoreValue }
    }

    /**
     * 重置玩家状态
     */
    fun reset() {
        _hand.clear()
        _collectedScore = 0
        _collectedCards.clear()
    }

    /**
     * 检查手牌中是否包含指定的牌
     */
    fun hasCards(cards: List<Card>): Boolean {
        val handCopy = _hand.toMutableList()
        for (card in cards) {
            val index = handCopy.indexOfFirst {
                it.rank == card.rank && it.suit == card.suit
            }
            if (index == -1) return false
            handCopy.removeAt(index)
        }
        return true
    }

    override fun toString(): String = "$name (${team.displayName})"
}

/**
 * 队伍信息
 */
data class TeamInfo(
    val team: Team,
    val players: List<Player>
) {
    // 队伍已走完的玩家
    val finishedPlayers: List<Player>
        get() = players.filter { it.hasFinished }

    // 队伍未走完的玩家
    val unfinishedPlayers: List<Player>
        get() = players.filter { !it.hasFinished }

    // 队伍是否全员走完
    val allFinished: Boolean
        get() = players.all { it.hasFinished }

    // 已走完玩家的已收分总和
    val finishedPlayersScore: Int
        get() = finishedPlayers.sumOf { it.collectedScore }

    // 所有玩家的已收分总和
    val totalCollectedScore: Int
        get() = players.sumOf { it.collectedScore }

    // 未走完玩家的手牌分
    val unfinishedHandScore: Int
        get() = unfinishedPlayers.sumOf { it.handScore }

    // 未走完玩家的已收分
    val unfinishedCollectedScore: Int
        get() = unfinishedPlayers.sumOf { it.collectedScore }
}
