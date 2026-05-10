package com.communicationcard.game.web.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 用户偏好（持久化到 localStorage）。与 Android 端 SettingsActivity 字段对齐。
 *
 * - [nickname]：联网时显示名
 * - [soundEnabled] / [vibrationEnabled]：Web 上 vibration 仅手机有意义；桌面静默 ignore
 * - [animationEnabled]：出牌 / 桌面动效（暂为 placeholder，UI 未消费 — Stage 3 接入）
 * - [gameSpeed]：AI 出牌延迟 multiplier；与 Android 的"慢/正常/快/极速"对齐
 *
 * 持久化键名带版本前缀 `v1:` —— 后续 schema 演化时可按版本读旧值再迁移。
 */
@Serializable
data class UserPreferences(
    val nickname: String = "玩家",
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val animationEnabled: Boolean = true,
    val gameSpeed: GameSpeed = GameSpeed.NORMAL,
) {
    companion object {
        private const val KEY = "v1:userPreferences"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(): UserPreferences {
            val raw = LocalStorage.getString(KEY) ?: return UserPreferences()
            return runCatching { json.decodeFromString<UserPreferences>(raw) }
                .getOrElse { UserPreferences() }
        }

        fun save(prefs: UserPreferences) {
            LocalStorage.setString(KEY, json.encodeToString(prefs))
        }
    }
}

@Serializable
enum class GameSpeed(val aiDelayMs: Long, val displayName: String) {
    SLOW(1500, "慢"),
    NORMAL(800, "正常"),
    FAST(400, "快"),
    INSTANT(50, "极速"),
}
