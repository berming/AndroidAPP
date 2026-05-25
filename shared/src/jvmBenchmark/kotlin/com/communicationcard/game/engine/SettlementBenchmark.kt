package com.communicationcard.game.engine

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.benchmark.Measurement
import java.util.concurrent.TimeUnit

/**
 * Issue #83 (UC11 Critical 整改 #7) — 性能基准测试种子文件。
 *
 * 当前只覆盖 SettlementCalculator.calculate 的几个代表性场景；后续 Sprint B/C
 * 应补：CardRules.canBeat、ServerGameManager.computeAllFinishedScores、AI 决策。
 *
 * 跑法：
 *   ./gradlew :shared:jvmBenchmark
 *
 * 输出：shared/build/reports/benchmarks/main/<timestamp>/main.json
 *
 * 回归判定：AQR rev=3 → 核心性能指标劣化 ≤ 5%。
 * CI 集成（带与 main baseline 对比）作为 follow-up。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class SettlementBenchmark {

    private lateinit var balancedTeamA: SettlementCalculator.TeamSettlementState
    private lateinit var balancedTeamB: SettlementCalculator.TeamSettlementState

    @Setup
    fun setup() {
        // 典型场景：双队 4 名玩家，部分走完部分未走完
        balancedTeamA = SettlementCalculator.TeamSettlementState(
            players = listOf(
                SettlementCalculator.PlayerSettlementState(isFinished = true, collectedScore = 25),
                SettlementCalculator.PlayerSettlementState(isFinished = true, collectedScore = 30),
                SettlementCalculator.PlayerSettlementState(isFinished = false, collectedScore = 10, handScore = 15),
                SettlementCalculator.PlayerSettlementState(isFinished = false, collectedScore = 5, handScore = 20),
            )
        )
        balancedTeamB = SettlementCalculator.TeamSettlementState(
            players = listOf(
                SettlementCalculator.PlayerSettlementState(isFinished = true, collectedScore = 20),
                SettlementCalculator.PlayerSettlementState(isFinished = false, collectedScore = 15, handScore = 25),
                SettlementCalculator.PlayerSettlementState(isFinished = false, collectedScore = 8, handScore = 18),
                SettlementCalculator.PlayerSettlementState(isFinished = false, collectedScore = 12, handScore = 22),
            )
        )
    }

    @Benchmark
    fun benchmarkBalancedCalculate(): SettlementCalculator.SettlementResult? {
        return SettlementCalculator.calculate(balancedTeamA, balancedTeamB)
    }
}
