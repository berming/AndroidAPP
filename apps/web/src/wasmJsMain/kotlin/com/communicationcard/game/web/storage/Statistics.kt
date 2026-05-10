package com.communicationcard.game.web.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 玩家战绩统计。与 Android 端 SettingsActivity 「游戏统计」面板对齐。
 *
 * - [totalGames]：总场次
 * - [wins] / [losses]：胜 / 负场（平局两边都不增）
 * - [currentStreak]：当前连胜数（输一场归零；和局保持）
 * - [maxStreak]：历史最高连胜
 * - [totalScore]：累计赢方得分（结算时若 winner = 我方队伍，加上该队伍 settlementScore）
 *
 * 持久化键名带 `v1:` 版本前缀。
 */
@Serializable
data class Statistics(
    val totalGames: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val currentStreak: Int = 0,
    val maxStreak: Int = 0,
    val totalScore: Int = 0,
) {
    val winRate: Double get() = if (totalGames == 0) 0.0 else wins.toDouble() / totalGames

    /** 一场游戏结束 → 返回更新后的 Statistics。 */
    fun afterGame(myTeamWon: Boolean?, myTeamScoreDelta: Int): Statistics = when (myTeamWon) {
        true -> {
            val newStreak = currentStreak + 1
            copy(
                totalGames = totalGames + 1,
                wins = wins + 1,
                currentStreak = newStreak,
                maxStreak = maxOf(maxStreak, newStreak),
                totalScore = totalScore + myTeamScoreDelta,
            )
        }
        false -> copy(
            totalGames = totalGames + 1,
            losses = losses + 1,
            currentStreak = 0,
        )
        null -> copy(totalGames = totalGames + 1) // 平局 / 未确定
    }

    companion object {
        private const val KEY = "v1:statistics"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(): Statistics {
            val raw = LocalStorage.getString(KEY) ?: return Statistics()
            return runCatching { json.decodeFromString<Statistics>(raw) }
                .getOrElse { Statistics() }
        }

        fun save(stats: Statistics) {
            LocalStorage.setString(KEY, json.encodeToString(stats))
        }

        fun reset() {
            LocalStorage.remove(KEY)
        }
    }
}
