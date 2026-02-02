package com.communicationcard.game.ai

import com.communicationcard.game.engine.CardRules
import com.communicationcard.game.model.*
import kotlin.random.Random

/**
 * AI难度等级
 */
enum class AIDifficulty {
    EASY,       // 简单：随机出牌
    MEDIUM,     // 中等：简单策略
    HARD        // 困难：复杂策略
}

/**
 * AI沟通消息
 */
data class AICommunication(
    val player: Player,
    val message: String,
    val isBluff: Boolean = false  // 是否是虚假信息
)

/**
 * AI玩家决策引擎
 */
class AIPlayer(private val difficulty: AIDifficulty = AIDifficulty.MEDIUM) {

    /**
     * AI做出出牌决策
     * @param player 当前AI玩家
     * @param lastPlay 上一手牌（null表示可以自由出牌）
     * @param gameState 游戏状态信息
     * @return 决定出的牌组，null表示过牌
     */
    fun makeDecision(
        player: Player,
        lastPlay: CardGroup?,
        gameState: GameStateInfo
    ): CardGroup? {
        if (player.hasFinished) return null

        val validPlays = CardRules.findValidPlays(player.hand, lastPlay)

        if (validPlays.isEmpty()) return null

        return when (difficulty) {
            AIDifficulty.EASY -> easyStrategy(validPlays, lastPlay)
            AIDifficulty.MEDIUM -> mediumStrategy(validPlays, lastPlay, player, gameState)
            AIDifficulty.HARD -> hardStrategy(validPlays, lastPlay, player, gameState)
        }
    }

    /**
     * 简单策略：随机出牌或50%概率过牌
     */
    private fun easyStrategy(validPlays: List<CardGroup>, lastPlay: CardGroup?): CardGroup? {
        // 如果是自由出牌，必须出
        if (lastPlay == null) {
            return validPlays.randomOrNull()
        }

        // 50%概率过牌
        if (Random.nextBoolean()) return null

        return validPlays.randomOrNull()
    }

    /**
     * 中等策略：基本的牌力评估
     */
    private fun mediumStrategy(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup?,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 自由出牌时的策略
        if (lastPlay == null) {
            return chooseFreePlay(validPlays, player, gameState)
        }

        // 跟牌策略
        return chooseFollowPlay(validPlays, lastPlay, player, gameState)
    }

    /**
     * 困难策略：考虑团队配合和得分
     */
    private fun hardStrategy(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup?,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 分析当前局势
        val myTeamScore = if (player.team == Team.TEAM_A) {
            gameState.teamAFinishedScore
        } else {
            gameState.teamBFinishedScore
        }

        val opponentTeamScore = if (player.team == Team.TEAM_A) {
            gameState.teamBFinishedScore
        } else {
            gameState.teamAFinishedScore
        }

        // 如果我方接近200分，积极出牌争取走完
        if (myTeamScore >= 150) {
            return validPlays.minByOrNull { it.primaryRank.value }
        }

        // 如果对方接近200分，尽量阻止
        if (opponentTeamScore >= 150) {
            // 优先出能获得高分的牌
            val highScorePlays = validPlays.filter { it.totalScore > 0 }
            if (highScorePlays.isNotEmpty()) {
                return highScorePlays.maxByOrNull { it.totalScore }
            }
        }

        // 自由出牌
        if (lastPlay == null) {
            return chooseFreePlayHard(validPlays, player, gameState)
        }

        // 跟牌
        return chooseFollowPlayHard(validPlays, lastPlay, player, gameState)
    }

    /**
     * 选择自由出牌（中等难度）
     */
    private fun chooseFreePlay(
        validPlays: List<CardGroup>,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 优先出小牌
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }

        if (nonBombs.isNotEmpty()) {
            // 按牌力排序，出最小的
            return nonBombs.minByOrNull { it.primaryRank.value }
        }

        // 只有炸弹时，出最小的炸弹
        return validPlays.minByOrNull {
            it.size * 100 + it.primaryRank.value
        }
    }

    /**
     * 选择跟牌（中等难度）
     */
    private fun chooseFollowPlay(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 如果上家是队友且牌里有高分，考虑过牌
        if (isTeammatePlay(lastPlay, player, gameState)) {
            if (lastPlay.totalScore > 0 && Random.nextFloat() > 0.3f) {
                return null  // 70%概率过牌让队友收分
            }
        }

        // 如果这轮牌有高分，积极跟牌
        if (gameState.currentRoundScore >= 15) {
            val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
            if (nonBombs.isNotEmpty()) {
                return nonBombs.minByOrNull { it.primaryRank.value }
            }
            // 分值很高时可以考虑用炸弹
            if (gameState.currentRoundScore >= 30) {
                return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
            }
        }

        // 普通情况：出最小的能压过的牌
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
        if (nonBombs.isNotEmpty()) {
            // 30%概率过牌
            if (Random.nextFloat() < 0.3f) return null
            return nonBombs.minByOrNull { it.primaryRank.value }
        }

        // 只有炸弹，大多数情况过牌
        if (Random.nextFloat() < 0.7f) return null
        return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
    }

    /**
     * 选择自由出牌（困难难度）
     */
    private fun chooseFreePlayHard(
        validPlays: List<CardGroup>,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 分析手牌结构
        val singles = validPlays.filter { it.type == CardGroupType.SINGLE }
        val pairs = validPlays.filter { it.type == CardGroupType.PAIR }
        val triples = validPlays.filter { it.type == CardGroupType.TRIPLE }
        val straights = validPlays.filter { it.type == CardGroupType.STRAIGHT }

        // 如果手牌很少，优先出能走完的牌型
        if (player.handSize <= 5) {
            // 尝试一次性走完
            val finishingPlay = validPlays.find { it.size == player.handSize }
            if (finishingPlay != null) return finishingPlay
        }

        // 有顺子优先出顺子
        if (straights.isNotEmpty()) {
            return straights.minByOrNull { it.primaryRank.value }
        }

        // 出单张或对子，保留三张和炸弹
        val smallPlays = (singles + pairs).filter { it.type != CardGroupType.BOMB }
        if (smallPlays.isNotEmpty()) {
            return smallPlays.minByOrNull { it.primaryRank.value }
        }

        // 出三张
        if (triples.isNotEmpty()) {
            return triples.minByOrNull { it.primaryRank.value }
        }

        // 最后才出炸弹
        return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
    }

    /**
     * 选择跟牌（困难难度）
     */
    private fun chooseFollowPlayHard(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        val isTeammate = isTeammatePlay(lastPlay, player, gameState)

        // 队友出的牌且有分，让队友收
        if (isTeammate && lastPlay.totalScore > 0) {
            return null
        }

        // 队友出的牌且分值很低，可以考虑接手
        if (isTeammate && lastPlay.totalScore == 0) {
            // 如果自己有更大的牌且队友手牌少，帮队友出牌
            val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
            if (nonBombs.isNotEmpty() && gameState.lastPlayerHandSize <= 3) {
                return nonBombs.minByOrNull { it.primaryRank.value }
            }
            return null
        }

        // 对手出的牌
        val roundScore = gameState.currentRoundScore

        // 高分轮次，积极争取
        if (roundScore >= 20) {
            val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
            if (nonBombs.isNotEmpty()) {
                return nonBombs.minByOrNull { it.primaryRank.value }
            }
            // 分值足够高时用炸弹
            if (roundScore >= 40) {
                return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
            }
        }

        // 低分轮次
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
        if (nonBombs.isNotEmpty()) {
            // 如果能用小牌压过，就出
            val smallPlay = nonBombs.minByOrNull { it.primaryRank.value }
            if (smallPlay != null && smallPlay.primaryRank.value <= CardRank.TEN.value) {
                return smallPlay
            }
            // 否则50%概率过牌
            if (Random.nextBoolean()) return null
            return smallPlay
        }

        // 只有炸弹，过牌
        return null
    }

    /**
     * 判断上一手牌是否是队友出的
     */
    private fun isTeammatePlay(lastPlay: CardGroup, player: Player, gameState: GameStateInfo): Boolean {
        val lastPlayerId = gameState.lastPlayerId ?: return false
        val lastPlayerTeam = gameState.getPlayerTeam(lastPlayerId)
        return lastPlayerTeam == player.team
    }

    /**
     * 生成AI沟通消息
     */
    fun generateCommunication(
        player: Player,
        gameState: GameStateInfo,
        action: AIAction
    ): AICommunication? {
        // 30%概率发送消息
        if (Random.nextFloat() > 0.3f) return null

        val messages = when (action) {
            AIAction.PLAY_SMALL -> listOf(
                "出个小的试试",
                "先出小牌",
                "我来探路"
            )
            AIAction.PLAY_BIG -> listOf(
                "大的来了！",
                "压！",
                "吃我一记"
            )
            AIAction.PLAY_BOMB -> listOf(
                "炸弹！",
                "接招！",
                "王炸！"
            )
            AIAction.PASS -> listOf(
                "过",
                "要不起",
                "你们来吧"
            )
            AIAction.BLUFF_HAS_BOMB -> listOf(
                "我有炸弹，你们小心",
                "别太嚣张，我有大的"
            )
            AIAction.CALL_TEAMMATE -> listOf(
                "队友上！",
                "你来压",
                "帮帮忙"
            )
            AIAction.CELEBRATE -> listOf(
                "漂亮！",
                "好牌！",
                "这分拿到了"
            )
        }

        val message = messages.randomOrNull() ?: return null
        val isBluff = action == AIAction.BLUFF_HAS_BOMB && !hasBomb(player)

        return AICommunication(player, message, isBluff)
    }

    private fun hasBomb(player: Player): Boolean {
        val grouped = player.hand.groupBy { it.rank }
        return grouped.any { it.value.size >= 4 }
    }
}

/**
 * AI动作类型
 */
enum class AIAction {
    PLAY_SMALL,
    PLAY_BIG,
    PLAY_BOMB,
    PASS,
    BLUFF_HAS_BOMB,
    CALL_TEAMMATE,
    CELEBRATE
}

/**
 * 游戏状态信息（供AI决策使用）
 */
data class GameStateInfo(
    val currentPlayerId: Int,
    val lastPlayerId: Int?,
    val lastPlayerHandSize: Int,
    val currentRoundScore: Int,      // 当前轮次已出牌的总分值
    val teamAFinishedScore: Int,     // A队已走完玩家的已收分
    val teamBFinishedScore: Int,     // B队已走完玩家的已收分
    val teamAAllFinished: Boolean,   // A队是否全员走完
    val teamBAllFinished: Boolean,   // B队是否全员走完
    val playerTeams: Map<Int, Team>, // 玩家ID到队伍的映射
    val playerHandSizes: Map<Int, Int> // 玩家ID到手牌数量的映射
) {
    fun getPlayerTeam(playerId: Int): Team? = playerTeams[playerId]
}
