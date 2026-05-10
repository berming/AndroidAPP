package com.communicationcard.game.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * 游戏设置管理器
 */
class GamePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "communication_card_prefs"

        // 设置键
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_ANIMATION_ENABLED = "animation_enabled"
        private const val KEY_GAME_SPEED = "game_speed"
        private const val KEY_LAST_DIFFICULTY = "last_difficulty"
        private const val KEY_LAST_PLAYER_COUNT = "last_player_count"
        private const val KEY_LAST_PLAYER_NAME = "last_player_name"

        // 统计键
        private const val KEY_TOTAL_GAMES = "total_games"
        private const val KEY_WINS = "wins"
        private const val KEY_LOSSES = "losses"
        private const val KEY_BEST_STREAK = "best_streak"
        private const val KEY_CURRENT_STREAK = "current_streak"
        private const val KEY_TOTAL_SCORE = "total_score"

        // 游戏速度选项
        // 单机 AI 出牌速度（feature_spec G36：3 档预设 50/400/1000ms）
        const val SPEED_SLOW = 0      // 慢速 (1000ms)
        const val SPEED_NORMAL = 1    // 默认 (400ms)
        const val SPEED_VERY_FAST = 2 // 极快 (50ms)
        // 历史 SPEED_FAST(300ms) 在 v3 移除；老偏好值 2 / 3 都映射到 SPEED_VERY_FAST
    }

    // ========== 设置 ==========

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    var isAnimationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANIMATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ANIMATION_ENABLED, value).apply()

    var gameSpeed: Int
        get() = prefs.getInt(KEY_GAME_SPEED, SPEED_NORMAL)
        set(value) = prefs.edit().putInt(KEY_GAME_SPEED, value).apply()

    var lastDifficulty: String
        get() = prefs.getString(KEY_LAST_DIFFICULTY, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit().putString(KEY_LAST_DIFFICULTY, value).apply()

    var lastPlayerCount: Int
        get() = prefs.getInt(KEY_LAST_PLAYER_COUNT, 6)
        set(value) = prefs.edit().putInt(KEY_LAST_PLAYER_COUNT, value).apply()

    var lastPlayerName: String
        get() = prefs.getString(KEY_LAST_PLAYER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PLAYER_NAME, value).apply()

    /**
     * 获取AI延迟毫秒数
     */
    fun getAIDelayMs(): Long {
        return when (gameSpeed) {
            SPEED_SLOW -> 1000L
            SPEED_NORMAL -> 400L
            SPEED_VERY_FAST -> 50L
            else -> 400L  // 含老偏好值 2 (former SPEED_FAST)、3 (former SPEED_VERY_FAST)
        }
    }

    /**
     * 获取回放延迟毫秒数
     */
    fun getReplayDelayMs(): Long {
        return when (gameSpeed) {
            SPEED_SLOW -> 1000L
            SPEED_NORMAL -> 600L
            SPEED_VERY_FAST -> 200L
            else -> 600L
        }
    }

    // ========== 统计 ==========

    var totalGames: Int
        get() = prefs.getInt(KEY_TOTAL_GAMES, 0)
        private set(value) = prefs.edit().putInt(KEY_TOTAL_GAMES, value).apply()

    var wins: Int
        get() = prefs.getInt(KEY_WINS, 0)
        private set(value) = prefs.edit().putInt(KEY_WINS, value).apply()

    var losses: Int
        get() = prefs.getInt(KEY_LOSSES, 0)
        private set(value) = prefs.edit().putInt(KEY_LOSSES, value).apply()

    var bestStreak: Int
        get() = prefs.getInt(KEY_BEST_STREAK, 0)
        private set(value) = prefs.edit().putInt(KEY_BEST_STREAK, value).apply()

    var currentStreak: Int
        get() = prefs.getInt(KEY_CURRENT_STREAK, 0)
        private set(value) = prefs.edit().putInt(KEY_CURRENT_STREAK, value).apply()

    var totalScore: Long
        get() = prefs.getLong(KEY_TOTAL_SCORE, 0L)
        private set(value) = prefs.edit().putLong(KEY_TOTAL_SCORE, value).apply()

    /**
     * 获取胜率
     */
    fun getWinRate(): Float {
        val total = wins + losses
        return if (total > 0) wins.toFloat() / total else 0f
    }

    /**
     * 记录游戏结果
     */
    fun recordGameResult(isWin: Boolean, score: Int) {
        totalGames++
        totalScore += score

        if (isWin) {
            wins++
            currentStreak++
            if (currentStreak > bestStreak) {
                bestStreak = currentStreak
            }
        } else {
            losses++
            currentStreak = 0
        }
    }

    /**
     * 重置统计数据
     */
    fun resetStatistics() {
        prefs.edit()
            .putInt(KEY_TOTAL_GAMES, 0)
            .putInt(KEY_WINS, 0)
            .putInt(KEY_LOSSES, 0)
            .putInt(KEY_BEST_STREAK, 0)
            .putInt(KEY_CURRENT_STREAK, 0)
            .putLong(KEY_TOTAL_SCORE, 0L)
            .apply()
    }

    /**
     * 获取统计摘要
     */
    fun getStatisticsSummary(): String {
        val winRate = (getWinRate() * 100).toInt()
        return """
            总场次: $totalGames
            胜: $wins / 负: $losses
            胜率: $winRate%
            当前连胜: $currentStreak
            最高连胜: $bestStreak
            总得分: $totalScore
        """.trimIndent()
    }
}
