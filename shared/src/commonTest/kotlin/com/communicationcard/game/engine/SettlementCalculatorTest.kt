package com.communicationcard.game.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 结算逻辑测试 - 验证所有15个用例
 *
 * 结算规则：
 * 1. 全队走完触发：走完队得分 = 本队已收分 + 对方未走完玩家已收分 + 对方未走完玩家手牌分
 *                  未走完队得分 = 本队已走完玩家已收分（无人走完则为0）
 * 2. 提前结算触发（已收分≥200）：该队胜出，对方得分 = 对方已走完玩家已收分
 */
class SettlementCalculatorTest {

    private fun player(finished: Boolean, collected: Int, hand: Int = 0) =
        SettlementCalculator.PlayerSettlementState(finished, collected, hand)

    private fun team(vararg players: SettlementCalculator.PlayerSettlementState) =
        SettlementCalculator.TeamSettlementState(players.toList())

    /**
     * 用例1: A1(100), B1(50), A2(20), A3(30), B2手牌(120), B3手牌(80)
     * A队全员走完触发
     * A队 = 100+20+30 + 0 + (120+80) = 350
     * B队 = 50
     */
    @Test
    fun testCase1_TeamAFinished_WithOpponentUnfinished() {
        val teamA = team(
            player(finished = true, collected = 100),
            player(finished = true, collected = 20),
            player(finished = true, collected = 30)
        )
        val teamB = team(
            player(finished = true, collected = 50),
            player(finished = false, collected = 0, hand = 120),
            player(finished = false, collected = 0, hand = 80)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(350, result.teamAScore)
        assertEquals(50, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore) // 验证总分
    }

    /**
     * 用例2: A1(80), A2(70), A3(50), B队无人走完(B1已收100, B2已收60, B3已收40)
     * A队全员走完触发
     * A队 = 80+70+50 + (100+60+40) + 0 = 400
     * B队 = 0
     */
    @Test
    fun testCase2_TeamAFinished_OpponentNoneFinished() {
        val teamA = team(
            player(finished = true, collected = 80),
            player(finished = true, collected = 70),
            player(finished = true, collected = 50)
        )
        val teamB = team(
            player(finished = false, collected = 100, hand = 0),
            player(finished = false, collected = 60, hand = 0),
            player(finished = false, collected = 40, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(400, result.teamAScore)
        assertEquals(0, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
    }

    /**
     * 用例3: B1(150), B2(100), B3(50), A队无人走完(A1已收30, A2已收40, A3已收30)
     * B队全员走完触发
     * A队 = 0
     * B队 = 150+100+50 + (30+40+30) + 0 = 400
     */
    @Test
    fun testCase3_TeamBFinished_OpponentNoneFinished() {
        val teamA = team(
            player(finished = false, collected = 30, hand = 0),
            player(finished = false, collected = 40, hand = 0),
            player(finished = false, collected = 30, hand = 0)
        )
        val teamB = team(
            player(finished = true, collected = 150),
            player(finished = true, collected = 100),
            player(finished = true, collected = 50)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(0, result.teamAScore)
        assertEquals(400, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_B, result.winner)
    }

    /**
     * 用例4: A1(120), B1(80), A2(50), B2(80), A3(30), B3(40)
     * A队先全员走完触发（A3走完时B3还未走完）
     * A队 = 120+50+30 + 0 + 40 = 240
     * B队 = 80+80 = 160
     */
    @Test
    fun testCase4_TeamAFinishedFirst_OneOpponentUnfinished() {
        val teamA = team(
            player(finished = true, collected = 120),
            player(finished = true, collected = 50),
            player(finished = true, collected = 30)
        )
        val teamB = team(
            player(finished = true, collected = 80),
            player(finished = true, collected = 80),
            player(finished = false, collected = 0, hand = 40) // B3未走完，手牌40分
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(240, result.teamAScore)
        assertEquals(160, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore)
    }

    /**
     * 用例5: A1(90), B1(100), A2(60), A3(50), B2已收60, B3已收40
     * A队全员走完触发
     * A队 = 90+60+50 + (60+40) + 0 = 300
     * B队 = 100
     */
    @Test
    fun testCase5_TeamAFinished_TwoOpponentsUnfinished() {
        val teamA = team(
            player(finished = true, collected = 90),
            player(finished = true, collected = 60),
            player(finished = true, collected = 50)
        )
        val teamB = team(
            player(finished = true, collected = 100),
            player(finished = false, collected = 60, hand = 0),
            player(finished = false, collected = 40, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(300, result.teamAScore)
        assertEquals(100, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore)
    }

    /**
     * 用例6: A1(110), B1(90), B2(80), A2(40), A3(30), B3手牌(50)
     * A队全员走完触发
     * A队 = 110+40+30 + 0 + 50 = 230
     * B队 = 90+80 = 170
     */
    @Test
    fun testCase6_TeamAFinished_OneOpponentWithHandScore() {
        val teamA = team(
            player(finished = true, collected = 110),
            player(finished = true, collected = 40),
            player(finished = true, collected = 30)
        )
        val teamB = team(
            player(finished = true, collected = 90),
            player(finished = true, collected = 80),
            player(finished = false, collected = 0, hand = 50)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(230, result.teamAScore)
        assertEquals(170, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore)
    }

    /**
     * 用例7: A1(50), A2(30), A3(20), B1已收150, B2已收100, B3已收50
     * A队全员走完触发
     * A队 = 50+30+20 + (150+100+50) + 0 = 400
     * B队 = 0
     */
    @Test
    fun testCase7_TeamAFinished_AllOpponentScoresTransfer() {
        val teamA = team(
            player(finished = true, collected = 50),
            player(finished = true, collected = 30),
            player(finished = true, collected = 20)
        )
        val teamB = team(
            player(finished = false, collected = 150, hand = 0),
            player(finished = false, collected = 100, hand = 0),
            player(finished = false, collected = 50, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(400, result.teamAScore)
        assertEquals(0, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
    }

    /**
     * 用例8: B1(80), A1(120), B2(70), B3(50), A2已收40, A3已收40
     * B队全员走完触发
     * A队 = 120
     * B队 = 80+70+50 + (40+40) + 0 = 280
     */
    @Test
    fun testCase8_TeamBFinished_TwoOpponentsUnfinished() {
        val teamA = team(
            player(finished = true, collected = 120),
            player(finished = false, collected = 40, hand = 0),
            player(finished = false, collected = 40, hand = 0)
        )
        val teamB = team(
            player(finished = true, collected = 80),
            player(finished = true, collected = 70),
            player(finished = true, collected = 50)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(120, result.teamAScore)
        assertEquals(280, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_B, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore)
    }

    /**
     * 用例9: A1(110), A2(90), A3未走完, B队无人走完
     * A1+A2已收分 = 200，提前结算触发
     * A队 = 200
     * B队 = 0
     */
    @Test
    fun testCase9_EarlySettlement_ScoreReached200() {
        val teamA = team(
            player(finished = true, collected = 110),
            player(finished = true, collected = 90),
            player(finished = false, collected = 0, hand = 0)
        )
        val teamB = team(
            player(finished = false, collected = 0, hand = 0),
            player(finished = false, collected = 0, hand = 0),
            player(finished = false, collected = 0, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.SCORE_REACHED_200, result!!.trigger)
        assertEquals(200, result.teamAScore)
        assertEquals(0, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        // 提前结算时总分可能不等于400
    }

    /**
     * 用例10: A1(105), B1(95), A2(55), B2(60), A3(45), B3(40)
     * A队先全员走完触发（A3走完时B3还未走完）
     * A队 = 105+55+45 + 0 + 40 = 245
     * B队 = 95+60 = 155
     */
    @Test
    fun testCase10_TeamAFinishedFirst_CloseGame() {
        val teamA = team(
            player(finished = true, collected = 105),
            player(finished = true, collected = 55),
            player(finished = true, collected = 45)
        )
        val teamB = team(
            player(finished = true, collected = 95),
            player(finished = true, collected = 60),
            player(finished = false, collected = 0, hand = 40) // B3未走完
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(245, result.teamAScore)
        assertEquals(155, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore)
    }

    /**
     * 用例11: A1(100), A2(80), A3(30), B1已收70, B2已收60, B3手牌60
     * A队全员走完触发
     * A队 = 100+80+30 + (70+60) + 60 = 400
     * B队 = 0
     */
    @Test
    fun testCase11_TeamAFinished_MixedOpponentState() {
        val teamA = team(
            player(finished = true, collected = 100),
            player(finished = true, collected = 80),
            player(finished = true, collected = 30)
        )
        val teamB = team(
            player(finished = false, collected = 70, hand = 0),
            player(finished = false, collected = 60, hand = 0),
            player(finished = false, collected = 0, hand = 60)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(400, result.teamAScore)
        assertEquals(0, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
    }

    /**
     * 用例12: A1(180), A2(10), A3(10), B1已收100, B2已收60, B3已收40
     * A队全员走完触发
     * A队 = 180+10+10 + (100+60+40) + 0 = 400
     * B队 = 0
     */
    @Test
    fun testCase12_TeamAFinished_OneDominantPlayer() {
        val teamA = team(
            player(finished = true, collected = 180),
            player(finished = true, collected = 10),
            player(finished = true, collected = 10)
        )
        val teamB = team(
            player(finished = false, collected = 100, hand = 0),
            player(finished = false, collected = 60, hand = 0),
            player(finished = false, collected = 40, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(400, result.teamAScore)
        assertEquals(0, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
    }

    /**
     * 用例13: B1(60), B2(50), B3(40), A1已收190, A2已收30, A3已收30
     * B队全员走完触发
     * A队 = 0
     * B队 = 60+50+40 + (190+30+30) + 0 = 400
     */
    @Test
    fun testCase13_TeamBFinished_OpponentHighScore() {
        val teamA = team(
            player(finished = false, collected = 190, hand = 0),
            player(finished = false, collected = 30, hand = 0),
            player(finished = false, collected = 30, hand = 0)
        )
        val teamB = team(
            player(finished = true, collected = 60),
            player(finished = true, collected = 50),
            player(finished = true, collected = 40)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(0, result.teamAScore)
        assertEquals(400, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_B, result.winner)
    }

    /**
     * 用例14: A1(0), A2(0), A3(0), B1已收200, B2已收100, B3已收100
     * 极端速度流：A队全员走完但收0分
     * A队 = 0 + (200+100+100) + 0 = 400
     * B队 = 0
     */
    @Test
    fun testCase14_ExtremeSpeedStrategy_ZeroCollection() {
        val teamA = team(
            player(finished = true, collected = 0),
            player(finished = true, collected = 0),
            player(finished = true, collected = 0)
        )
        val teamB = team(
            player(finished = false, collected = 200, hand = 0),
            player(finished = false, collected = 100, hand = 0),
            player(finished = false, collected = 100, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(400, result.teamAScore)
        assertEquals(0, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
    }

    /**
     * 用例15: A1(120), B1(100), A2(60), B2(80), A3(40), B3(0)
     * A队先全员走完触发（B3还未走完，手牌0分）
     * A队 = 120+60+40 + 0 + 0 = 220
     * B队 = 100+80 = 180
     */
    @Test
    fun testCase15_TeamAFinished_OpponentZeroHandScore() {
        val teamA = team(
            player(finished = true, collected = 120),
            player(finished = true, collected = 60),
            player(finished = true, collected = 40)
        )
        val teamB = team(
            player(finished = true, collected = 100),
            player(finished = true, collected = 80),
            player(finished = false, collected = 0, hand = 0) // B3未走完，0分
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(SettlementTrigger.TEAM_ALL_FINISHED, result!!.trigger)
        assertEquals(220, result.teamAScore)
        assertEquals(180, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.TEAM_A, result.winner)
        assertEquals(400, result.teamAScore + result.teamBScore)
    }

    // ========== 额外边界测试 ==========

    /**
     * 测试平局情况：双方得分相等
     */
    @Test
    fun testDraw_EqualScores() {
        val teamA = team(
            player(finished = true, collected = 100),
            player(finished = true, collected = 50),
            player(finished = true, collected = 50)
        )
        val teamB = team(
            player(finished = true, collected = 100),
            player(finished = true, collected = 100),
            player(finished = false, collected = 0, hand = 0)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNotNull(result)
        assertEquals(200, result!!.teamAScore)
        assertEquals(200, result.teamBScore)
        assertEquals(SettlementCalculator.Winner.DRAW, result.winner)
    }

    /**
     * 测试未触发结算的情况
     */
    @Test
    fun testNoSettlement_NeitherConditionMet() {
        val teamA = team(
            player(finished = true, collected = 50),
            player(finished = false, collected = 30, hand = 20),
            player(finished = false, collected = 20, hand = 30)
        )
        val teamB = team(
            player(finished = true, collected = 80),
            player(finished = false, collected = 40, hand = 30),
            player(finished = false, collected = 30, hand = 20)
        )

        val result = SettlementCalculator.calculate(teamA, teamB)

        assertNull(result) // 未触发结算
    }

    /**
     * 运行所有测试的摘要
     */
    @Test
    fun printTestSummary() {
        println("=".repeat(60))
        println("沟通牌结算逻辑测试摘要")
        println("=".repeat(60))
        println("用例1-8:  全队走完触发，各种场景")
        println("用例9:    提前结算触发（已收分≥200）")
        println("用例10-15: 全队走完触发，边界场景")
        println("=".repeat(60))
        println("关键验证点：")
        println("1. 走完队得分 = 本队已收分 + 对方未走完已收分 + 对方未走完手牌分")
        println("2. 未走完队得分 = 本队已走完玩家已收分")
        println("3. 提前结算时，双方得分仅计算已走完玩家已收分")
        println("4. 总分验证（全队走完时应等于400）")
        println("=".repeat(60))
    }
}
