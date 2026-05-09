package com.communicationcard.game.engine

/**
 * 结算计算器 - 独立的结算逻辑，方便测试
 */
object SettlementCalculator {

    /**
     * 玩家结算状态
     * @param isFinished 是否已走完
     * @param collectedScore 已收分
     * @param handScore 手牌分（未走完时有效）
     */
    data class PlayerSettlementState(
        val isFinished: Boolean,
        val collectedScore: Int,
        val handScore: Int = 0
    )

    /**
     * 队伍结算状态
     */
    data class TeamSettlementState(
        val players: List<PlayerSettlementState>
    ) {
        val allFinished: Boolean
            get() = players.all { it.isFinished }

        val finishedPlayersScore: Int
            get() = players.filter { it.isFinished }.sumOf { it.collectedScore }

        val unfinishedCollectedScore: Int
            get() = players.filter { !it.isFinished }.sumOf { it.collectedScore }

        val unfinishedHandScore: Int
            get() = players.filter { !it.isFinished }.sumOf { it.handScore }
    }

    /**
     * 结算结果
     */
    data class SettlementResult(
        val teamAScore: Int,
        val teamBScore: Int,
        val trigger: SettlementTrigger,
        val winner: Winner
    )

    enum class Winner {
        TEAM_A, TEAM_B, DRAW
    }

    /**
     * 计算结算结果
     * @param teamA A队状态
     * @param teamB B队状态
     * @return 结算结果，如果未触发结算则返回null
     */
    fun calculate(teamA: TeamSettlementState, teamB: TeamSettlementState): SettlementResult? {
        // 检查触发条件
        val trigger = when {
            teamA.allFinished || teamB.allFinished -> SettlementTrigger.TEAM_ALL_FINISHED
            teamA.finishedPlayersScore >= 200 -> SettlementTrigger.SCORE_REACHED_200
            teamB.finishedPlayersScore >= 200 -> SettlementTrigger.SCORE_REACHED_200
            else -> return null // 未触发结算
        }

        val (teamAScore, teamBScore) = when (trigger) {
            SettlementTrigger.TEAM_ALL_FINISHED -> {
                calculateTeamAllFinishedScores(teamA, teamB)
            }
            SettlementTrigger.SCORE_REACHED_200 -> {
                Pair(teamA.finishedPlayersScore, teamB.finishedPlayersScore)
            }
        }

        val winner = determineWinner(teamAScore, teamBScore)

        return SettlementResult(teamAScore, teamBScore, trigger, winner)
    }

    /**
     * 计算全队走完时的得分
     */
    private fun calculateTeamAllFinishedScores(
        teamA: TeamSettlementState,
        teamB: TeamSettlementState
    ): Pair<Int, Int> {
        return if (teamA.allFinished) {
            // A队全员走完触发
            val teamAScore = teamA.finishedPlayersScore +
                    teamB.unfinishedCollectedScore +
                    teamB.unfinishedHandScore
            val teamBScore = teamB.finishedPlayersScore
            Pair(teamAScore, teamBScore)
        } else {
            // B队全员走完触发
            val teamBScore = teamB.finishedPlayersScore +
                    teamA.unfinishedCollectedScore +
                    teamA.unfinishedHandScore
            val teamAScore = teamA.finishedPlayersScore
            Pair(teamAScore, teamBScore)
        }
    }

    /**
     * 判定胜者
     */
    private fun determineWinner(teamAScore: Int, teamBScore: Int): Winner {
        return when {
            teamAScore >= 200 && teamBScore >= 200 -> {
                when {
                    teamAScore > teamBScore -> Winner.TEAM_A
                    teamBScore > teamAScore -> Winner.TEAM_B
                    else -> Winner.DRAW
                }
            }
            teamAScore >= 200 -> Winner.TEAM_A
            teamBScore >= 200 -> Winner.TEAM_B
            teamAScore > teamBScore -> Winner.TEAM_A
            teamBScore > teamAScore -> Winner.TEAM_B
            else -> Winner.DRAW
        }
    }
}
