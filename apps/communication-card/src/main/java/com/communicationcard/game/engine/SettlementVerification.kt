package com.communicationcard.game.engine

/**
 * 结算逻辑验证 - 验证所有15个用例
 * 可以通过 main 函数直接运行验证
 */
object SettlementVerification {

    data class TestCase(
        val name: String,
        val description: String,
        val teamA: SettlementCalculator.TeamSettlementState,
        val teamB: SettlementCalculator.TeamSettlementState,
        val expectedTeamAScore: Int,
        val expectedTeamBScore: Int,
        val expectedTrigger: SettlementTrigger,
        val expectedWinner: SettlementCalculator.Winner
    )

    private fun player(finished: Boolean, collected: Int, hand: Int = 0) =
        SettlementCalculator.PlayerSettlementState(finished, collected, hand)

    private fun team(vararg players: SettlementCalculator.PlayerSettlementState) =
        SettlementCalculator.TeamSettlementState(players.toList())

    private val testCases = listOf(
        // 用例1
        TestCase(
            name = "用例1",
            description = "A1(100), B1(50), A2(20), A3(30), B2手牌(120), B3手牌(80)",
            teamA = team(
                player(true, 100), player(true, 20), player(true, 30)
            ),
            teamB = team(
                player(true, 50), player(false, 0, 120), player(false, 0, 80)
            ),
            expectedTeamAScore = 350,
            expectedTeamBScore = 50,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例2
        TestCase(
            name = "用例2",
            description = "A队全员走完, B队无人走完(B1已收100, B2已收60, B3已收40)",
            teamA = team(
                player(true, 80), player(true, 70), player(true, 50)
            ),
            teamB = team(
                player(false, 100), player(false, 60), player(false, 40)
            ),
            expectedTeamAScore = 400,
            expectedTeamBScore = 0,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例3
        TestCase(
            name = "用例3",
            description = "B队全员走完, A队无人走完(A1已收30, A2已收40, A3已收30)",
            teamA = team(
                player(false, 30), player(false, 40), player(false, 30)
            ),
            teamB = team(
                player(true, 150), player(true, 100), player(true, 50)
            ),
            expectedTeamAScore = 0,
            expectedTeamBScore = 400,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_B
        ),

        // 用例4
        TestCase(
            name = "用例4",
            description = "A3走完触发, B3(40)未走完",
            teamA = team(
                player(true, 120), player(true, 50), player(true, 30)
            ),
            teamB = team(
                player(true, 80), player(true, 80), player(false, 0, 40)
            ),
            expectedTeamAScore = 240,
            expectedTeamBScore = 160,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例5
        TestCase(
            name = "用例5",
            description = "A队全员走完, B2已收60, B3已收40未走完",
            teamA = team(
                player(true, 90), player(true, 60), player(true, 50)
            ),
            teamB = team(
                player(true, 100), player(false, 60), player(false, 40)
            ),
            expectedTeamAScore = 300,
            expectedTeamBScore = 100,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例6
        TestCase(
            name = "用例6",
            description = "A队全员走完, B3手牌(50)未走完",
            teamA = team(
                player(true, 110), player(true, 40), player(true, 30)
            ),
            teamB = team(
                player(true, 90), player(true, 80), player(false, 0, 50)
            ),
            expectedTeamAScore = 230,
            expectedTeamBScore = 170,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例7
        TestCase(
            name = "用例7",
            description = "A队全员走完, B队无人走完(B1已收150, B2已收100, B3已收50)",
            teamA = team(
                player(true, 50), player(true, 30), player(true, 20)
            ),
            teamB = team(
                player(false, 150), player(false, 100), player(false, 50)
            ),
            expectedTeamAScore = 400,
            expectedTeamBScore = 0,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例8
        TestCase(
            name = "用例8",
            description = "B队全员走完, A2已收40, A3已收40未走完",
            teamA = team(
                player(true, 120), player(false, 40), player(false, 40)
            ),
            teamB = team(
                player(true, 80), player(true, 70), player(true, 50)
            ),
            expectedTeamAScore = 120,
            expectedTeamBScore = 280,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_B
        ),

        // 用例9 - 提前结算
        TestCase(
            name = "用例9",
            description = "A1(110)+A2(90)=200, 提前结算",
            teamA = team(
                player(true, 110), player(true, 90), player(false, 0)
            ),
            teamB = team(
                player(false, 0), player(false, 0), player(false, 0)
            ),
            expectedTeamAScore = 200,
            expectedTeamBScore = 0,
            expectedTrigger = SettlementTrigger.SCORE_REACHED_200,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例10
        TestCase(
            name = "用例10",
            description = "A3走完触发, B3(40)未走完",
            teamA = team(
                player(true, 105), player(true, 55), player(true, 45)
            ),
            teamB = team(
                player(true, 95), player(true, 60), player(false, 0, 40)
            ),
            expectedTeamAScore = 245,
            expectedTeamBScore = 155,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例11
        TestCase(
            name = "用例11",
            description = "A队全员走完, B1已收70, B2已收60, B3手牌60",
            teamA = team(
                player(true, 100), player(true, 80), player(true, 30)
            ),
            teamB = team(
                player(false, 70), player(false, 60), player(false, 0, 60)
            ),
            expectedTeamAScore = 400,
            expectedTeamBScore = 0,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例12
        TestCase(
            name = "用例12",
            description = "A1(180)独揽大分, B队无人走完",
            teamA = team(
                player(true, 180), player(true, 10), player(true, 10)
            ),
            teamB = team(
                player(false, 100), player(false, 60), player(false, 40)
            ),
            expectedTeamAScore = 400,
            expectedTeamBScore = 0,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例13
        TestCase(
            name = "用例13",
            description = "B队全员走完, A队无人走完(A1已收190)",
            teamA = team(
                player(false, 190), player(false, 30), player(false, 30)
            ),
            teamB = team(
                player(true, 60), player(true, 50), player(true, 40)
            ),
            expectedTeamAScore = 0,
            expectedTeamBScore = 400,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_B
        ),

        // 用例14
        TestCase(
            name = "用例14",
            description = "极端速度流: A队0分全走完, B队无人走完(已收400)",
            teamA = team(
                player(true, 0), player(true, 0), player(true, 0)
            ),
            teamB = team(
                player(false, 200), player(false, 100), player(false, 100)
            ),
            expectedTeamAScore = 400,
            expectedTeamBScore = 0,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        ),

        // 用例15
        TestCase(
            name = "用例15",
            description = "A3走完触发, B3(0)未走完",
            teamA = team(
                player(true, 120), player(true, 60), player(true, 40)
            ),
            teamB = team(
                player(true, 100), player(true, 80), player(false, 0, 0)
            ),
            expectedTeamAScore = 220,
            expectedTeamBScore = 180,
            expectedTrigger = SettlementTrigger.TEAM_ALL_FINISHED,
            expectedWinner = SettlementCalculator.Winner.TEAM_A
        )
    )

    /**
     * 运行所有验证
     */
    fun runAllVerifications(): VerificationReport {
        val results = mutableListOf<VerificationResult>()

        for (testCase in testCases) {
            val result = verify(testCase)
            results.add(result)
        }

        return VerificationReport(results)
    }

    private fun verify(testCase: TestCase): VerificationResult {
        val actual = SettlementCalculator.calculate(testCase.teamA, testCase.teamB)

        val errors = mutableListOf<String>()

        if (actual == null) {
            errors.add("结算未触发（期望触发）")
        } else {
            if (actual.teamAScore != testCase.expectedTeamAScore) {
                errors.add("A队得分: 期望${testCase.expectedTeamAScore}, 实际${actual.teamAScore}")
            }
            if (actual.teamBScore != testCase.expectedTeamBScore) {
                errors.add("B队得分: 期望${testCase.expectedTeamBScore}, 实际${actual.teamBScore}")
            }
            if (actual.trigger != testCase.expectedTrigger) {
                errors.add("触发方式: 期望${testCase.expectedTrigger}, 实际${actual.trigger}")
            }
            if (actual.winner != testCase.expectedWinner) {
                errors.add("胜者: 期望${testCase.expectedWinner}, 实际${actual.winner}")
            }

            // 验证总分（全队走完时应为400）
            if (actual.trigger == SettlementTrigger.TEAM_ALL_FINISHED) {
                val total = actual.teamAScore + actual.teamBScore
                if (total != 400) {
                    errors.add("总分验证: 期望400, 实际$total")
                }
            }
        }

        return VerificationResult(
            testCase = testCase,
            actualResult = actual,
            passed = errors.isEmpty(),
            errors = errors
        )
    }

    data class VerificationResult(
        val testCase: TestCase,
        val actualResult: SettlementCalculator.SettlementResult?,
        val passed: Boolean,
        val errors: List<String>
    )

    data class VerificationReport(
        val results: List<VerificationResult>
    ) {
        val totalCount: Int get() = results.size
        val passedCount: Int get() = results.count { it.passed }
        val failedCount: Int get() = results.count { !it.passed }
        val allPassed: Boolean get() = failedCount == 0

        fun printReport() {
            println("=" .repeat(70))
            println("沟通牌结算逻辑验证报告")
            println("=".repeat(70))
            println()

            for (result in results) {
                val status = if (result.passed) "✓ 通过" else "✗ 失败"
                println("${result.testCase.name}: $status")
                println("  描述: ${result.testCase.description}")

                if (result.passed && result.actualResult != null) {
                    println("  A队: ${result.actualResult.teamAScore}分, B队: ${result.actualResult.teamBScore}分")
                }

                if (!result.passed) {
                    for (error in result.errors) {
                        println("  ❌ $error")
                    }
                }
                println()
            }

            println("=".repeat(70))
            println("总结: $passedCount/$totalCount 通过")
            if (allPassed) {
                println("✓ 所有用例验证通过！")
            } else {
                println("✗ 有 $failedCount 个用例验证失败")
            }
            println("=".repeat(70))
        }
    }
}

/**
 * 主函数 - 运行验证
 */
fun main() {
    val report = SettlementVerification.runAllVerifications()
    report.printReport()
}
