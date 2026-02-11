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
     * 中等策略：基本的牌力评估 + 团队配合
     */
    private fun mediumStrategy(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup?,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 自由出牌时的策略
        if (lastPlay == null) {
            return chooseFreePlayWithTeamAwareness(validPlays, player, gameState)
        }

        // 跟牌策略
        return chooseFollowPlayWithTeamAwareness(validPlays, lastPlay, player, gameState)
    }

    /**
     * 困难策略：考虑团队配合和得分（最优策略）
     */
    private fun hardStrategy(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup?,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        // 分析当前局势
        val myTeamScore = if (player.team == Team.TEAM_A) {
            gameState.teamATotalScore
        } else {
            gameState.teamBTotalScore
        }

        val opponentTeamScore = if (player.team == Team.TEAM_A) {
            gameState.teamBTotalScore
        } else {
            gameState.teamATotalScore
        }

        // 自由出牌
        if (lastPlay == null) {
            return chooseFreePlayOptimal(validPlays, player, gameState, myTeamScore, opponentTeamScore)
        }

        // 跟牌
        return chooseFollowPlayOptimal(validPlays, lastPlay, player, gameState, myTeamScore, opponentTeamScore)
    }

    /**
     * 自由出牌（最优策略）
     */
    private fun chooseFreePlayOptimal(
        validPlays: List<CardGroup>,
        player: Player,
        gameState: GameStateInfo,
        myTeamScore: Int,
        opponentTeamScore: Int
    ): CardGroup? {
        // 1. 如果我方接近200分，尽快走完
        if (myTeamScore >= 150) {
            return chooseFastFinishPlay(validPlays, player)
        }

        // 2. 检查是否需要送队友走
        val teammateInfo = gameState.getTeammateWithFewestCards(player.team, player.id)
        if (teammateInfo != null && teammateInfo.second <= 3) {
            // 队友快走完了，出小牌让队友能接
            return choosePlayToHelpTeammate(validPlays, player, gameState)
        }

        // 3. 检查下家是否是对手且快走完
        val nextPlayerId = gameState.getNextActivePlayerId()
        if (nextPlayerId != null) {
            val nextTeam = gameState.getPlayerTeam(nextPlayerId)
            val nextHandSize = gameState.playerHandSizes[nextPlayerId] ?: 0
            if (nextTeam != player.team && nextHandSize <= 3) {
                // 下家是对手且快走完，出大牌压制
                return choosePlayToBlockOpponent(validPlays, player)
            }
        }

        // 4. 正常情况：送队友策略
        return chooseFreePlayWithTeamAwareness(validPlays, player, gameState)
    }

    /**
     * 跟牌（最优策略）
     */
    private fun chooseFollowPlayOptimal(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup,
        player: Player,
        gameState: GameStateInfo,
        myTeamScore: Int,
        opponentTeamScore: Int
    ): CardGroup? {
        val lastPlayerId = gameState.lastPlayerId ?: return null
        val lastPlayerTeam = gameState.getPlayerTeam(lastPlayerId)
        val isTeammate = lastPlayerTeam == player.team
        val roundScore = gameState.currentRoundScore

        // 1. 队友正在赢，让队友收分
        if (isTeammate) {
            // 队友有分的牌，让给队友
            if (roundScore > 0) {
                return null  // 过牌
            }

            // 队友快走完了（手牌<=3），让队友继续出
            val lastPlayerHandSize = gameState.playerHandSizes[lastPlayerId] ?: 0
            if (lastPlayerHandSize <= 3) {
                return null  // 过牌让队友继续
            }

            // 队友出的牌没分，可以考虑接手
            val nextPlayerId = gameState.getNextActivePlayerId()
            if (nextPlayerId != null) {
                val nextTeam = gameState.getPlayerTeam(nextPlayerId)
                // 如果下家是对手且快走完，我来压住
                val nextHandSize = gameState.playerHandSizes[nextPlayerId] ?: 0
                if (nextTeam != player.team && nextHandSize <= 3) {
                    val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
                    if (nonBombs.isNotEmpty()) {
                        return nonBombs.minByOrNull { it.primaryRank.value }
                    }
                }
            }

            return null  // 让队友继续
        }

        // 2. 对手正在赢
        // 检查对手是否快走完
        val lastPlayerHandSize = gameState.playerHandSizes[lastPlayerId] ?: 0
        if (lastPlayerHandSize <= 3) {
            // 对手快走完了，必须压住！
            val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
            if (nonBombs.isNotEmpty()) {
                return nonBombs.minByOrNull { it.primaryRank.value }
            }
            // 用炸弹也要压
            return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
        }

        // 3. 高分轮次，积极争取
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

        // 4. 检查下家是否是队友
        val nextPlayerId = gameState.getNextActivePlayerId()
        if (nextPlayerId != null && gameState.getPlayerTeam(nextPlayerId) == player.team) {
            // 下家是队友，可以过牌让队友来处理
            val nextHandSize = gameState.playerHandSizes[nextPlayerId] ?: 0
            if (nextHandSize > 3 && Random.nextFloat() < 0.5f) {
                return null  // 让队友处理
            }
        }

        // 5. 正常跟牌
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
        if (nonBombs.isNotEmpty()) {
            val smallPlay = nonBombs.minByOrNull { it.primaryRank.value }
            if (smallPlay != null && smallPlay.primaryRank.value <= CardRank.TEN.value) {
                return smallPlay
            }
            // 大牌时考虑过牌
            if (Random.nextFloat() < 0.4f) return null
            return smallPlay
        }

        // 只有炸弹，过牌
        return null
    }

    /**
     * 选择快速走完的出牌
     */
    private fun chooseFastFinishPlay(validPlays: List<CardGroup>, player: Player): CardGroup? {
        // 能一次走完就走完
        val finishingPlay = validPlays.find { it.size == player.handSize }
        if (finishingPlay != null) return finishingPlay

        // 出最大的牌来确保能赢
        return validPlays.maxByOrNull { it.primaryRank.value }
    }

    /**
     * 选择帮助队友的出牌（出小牌让队友能接）
     */
    private fun choosePlayToHelpTeammate(
        validPlays: List<CardGroup>,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
        if (nonBombs.isEmpty()) {
            // 只有炸弹，出最小的
            return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
        }

        // 优先出单张小牌，方便队友接
        val singles = nonBombs.filter { it.type == CardGroupType.SINGLE }
        if (singles.isNotEmpty()) {
            // 出中等大小的单张（不是最小的3，给队友留机会压）
            val sorted = singles.sortedBy { it.primaryRank.value }
            return if (sorted.size > 2) sorted[1] else sorted.first()
        }

        // 出对子
        val pairs = nonBombs.filter { it.type == CardGroupType.PAIR }
        if (pairs.isNotEmpty()) {
            return pairs.minByOrNull { it.primaryRank.value }
        }

        return nonBombs.minByOrNull { it.primaryRank.value }
    }

    /**
     * 选择压制对手的出牌
     */
    private fun choosePlayToBlockOpponent(validPlays: List<CardGroup>, player: Player): CardGroup? {
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }

        // 出大牌压制
        if (nonBombs.isNotEmpty()) {
            // 出比较大的单张（让对手不容易压过）
            val singles = nonBombs.filter { it.type == CardGroupType.SINGLE }
            if (singles.isNotEmpty()) {
                val bigSingles = singles.filter { it.primaryRank.value >= CardRank.JACK.value }
                if (bigSingles.isNotEmpty()) {
                    return bigSingles.minByOrNull { it.primaryRank.value }
                }
            }
            return nonBombs.maxByOrNull { it.primaryRank.value }
        }

        // 用炸弹
        return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
    }

    /**
     * 选择自由出牌（带团队意识）
     */
    private fun chooseFreePlayWithTeamAwareness(
        validPlays: List<CardGroup>,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }

        // 检查下家是谁
        val nextPlayerId = gameState.getNextActivePlayerId()

        if (nextPlayerId != null) {
            val nextTeam = gameState.getPlayerTeam(nextPlayerId)
            val nextHandSize = gameState.playerHandSizes[nextPlayerId] ?: 0

            // 下家是队友
            if (nextTeam == player.team) {
                // 队友快走完了，出小牌让队友能接
                if (nextHandSize <= 3 && nonBombs.isNotEmpty()) {
                    val singles = nonBombs.filter { it.type == CardGroupType.SINGLE }
                    if (singles.isNotEmpty()) {
                        // 出中小单张
                        return singles.filter { it.primaryRank.value <= CardRank.TEN.value }
                            .minByOrNull { it.primaryRank.value }
                            ?: singles.minByOrNull { it.primaryRank.value }
                    }
                }
            } else {
                // 下家是对手且快走完，出大牌不让对手轻易接
                if (nextHandSize <= 3 && nonBombs.isNotEmpty()) {
                    return nonBombs.maxByOrNull { it.primaryRank.value }
                }
            }
        }

        // 正常情况：优先出小牌
        if (nonBombs.isNotEmpty()) {
            return nonBombs.minByOrNull { it.primaryRank.value }
        }

        // 只有炸弹时，出最小的炸弹
        return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
    }

    /**
     * 选择跟牌（带团队意识）
     */
    private fun chooseFollowPlayWithTeamAwareness(
        validPlays: List<CardGroup>,
        lastPlay: CardGroup,
        player: Player,
        gameState: GameStateInfo
    ): CardGroup? {
        val lastPlayerId = gameState.lastPlayerId
        val isTeammate = isTeammatePlay(lastPlay, player, gameState)
        val roundScore = gameState.currentRoundScore

        // 队友正在赢
        if (isTeammate) {
            // 有分的牌让给队友
            if (roundScore > 0) {
                return null
            }

            // 检查队友手牌数
            val lastPlayerHandSize = lastPlayerId?.let { gameState.playerHandSizes[it] } ?: 0
            if (lastPlayerHandSize <= 3) {
                // 队友快走完了，让队友继续
                return null
            }

            // 检查下家
            val nextPlayerId = gameState.getNextActivePlayerId()
            if (nextPlayerId != null) {
                val nextTeam = gameState.getPlayerTeam(nextPlayerId)
                val nextHandSize = gameState.playerHandSizes[nextPlayerId] ?: 0
                // 下家是对手且快走完，接手防止对手压
                if (nextTeam != player.team && nextHandSize <= 3) {
                    val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
                    if (nonBombs.isNotEmpty()) {
                        return nonBombs.minByOrNull { it.primaryRank.value }
                    }
                }
            }

            return null  // 让队友继续出
        }

        // 对手正在赢
        val lastPlayerHandSize = lastPlayerId?.let { gameState.playerHandSizes[it] } ?: 0

        // 对手快走完了，必须压住
        if (lastPlayerHandSize <= 3) {
            val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
            if (nonBombs.isNotEmpty()) {
                return nonBombs.minByOrNull { it.primaryRank.value }
            }
            // 用炸弹也要压
            return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
        }

        // 高分轮次，积极跟牌
        if (roundScore >= 15) {
            val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
            if (nonBombs.isNotEmpty()) {
                return nonBombs.minByOrNull { it.primaryRank.value }
            }
            if (roundScore >= 30) {
                return validPlays.minByOrNull { it.size * 100 + it.primaryRank.value }
            }
        }

        // 检查下家是否是队友
        val nextPlayerId = gameState.getNextActivePlayerId()
        if (nextPlayerId != null && gameState.getPlayerTeam(nextPlayerId) == player.team) {
            // 下家是队友，可以过牌让队友处理
            if (Random.nextFloat() < 0.4f) {
                return null
            }
        }

        // 普通情况
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
        if (nonBombs.isNotEmpty()) {
            if (Random.nextFloat() < 0.2f) return null
            return nonBombs.minByOrNull { it.primaryRank.value }
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
    val playerHandSizes: Map<Int, Int>, // 玩家ID到手牌数量的映射
    val playerOrder: List<Int> = emptyList(),  // 玩家出牌顺序（从当前玩家开始）
    val teamATotalScore: Int = 0,    // A队总已收分（包括未走完玩家）
    val teamBTotalScore: Int = 0     // B队总已收分（包括未走完玩家）
) {
    fun getPlayerTeam(playerId: Int): Team? = playerTeams[playerId]

    /**
     * 获取下一个玩家ID
     */
    fun getNextPlayerId(): Int? {
        if (playerOrder.size < 2) return null
        return playerOrder[1]
    }

    /**
     * 获取下一个未走完的玩家ID
     */
    fun getNextActivePlayerId(): Int? {
        for (i in 1 until playerOrder.size) {
            val playerId = playerOrder[i]
            if ((playerHandSizes[playerId] ?: 0) > 0) {
                return playerId
            }
        }
        return null
    }

    /**
     * 判断下一个玩家是否是队友
     */
    fun isNextPlayerTeammate(currentTeam: Team): Boolean {
        val nextId = getNextActivePlayerId() ?: return false
        return playerTeams[nextId] == currentTeam
    }

    /**
     * 获取队友中手牌最少的玩家
     */
    fun getTeammateWithFewestCards(currentTeam: Team, excludePlayerId: Int): Pair<Int, Int>? {
        return playerHandSizes
            .filter { (id, size) ->
                id != excludePlayerId &&
                playerTeams[id] == currentTeam &&
                size > 0
            }
            .minByOrNull { it.value }
            ?.toPair()
    }

    /**
     * 获取对手中手牌最少的玩家
     */
    fun getOpponentWithFewestCards(currentTeam: Team): Pair<Int, Int>? {
        val opponentTeam = if (currentTeam == Team.TEAM_A) Team.TEAM_B else Team.TEAM_A
        return playerHandSizes
            .filter { (id, size) ->
                playerTeams[id] == opponentTeam &&
                size > 0
            }
            .minByOrNull { it.value }
            ?.toPair()
    }

    private fun Map.Entry<Int, Int>.toPair() = Pair(key, value)
}
