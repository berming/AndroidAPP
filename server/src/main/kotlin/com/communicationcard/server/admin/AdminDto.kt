package com.communicationcard.server.admin

import com.communicationcard.game.network.SerializedCardGroup
import kotlinx.serialization.Serializable

/**
 * 登录 / 改密 / `/me` 系列接口的请求 + 响应 DTO。
 *
 * 强制约束（约束 10 / 见 CLAUDE.md PR 1 起）：
 * - **绝不**把 password_hash / 任何密钥字段序列化进 DTO
 * - last_login_at 以 ISO-8601 字符串返回（前端 dayjs 直接 parse）
 */

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
)

@Serializable
data class AdminUserDto(
    val id: Long,
    val username: String,
    val role: String,
    val permissions: List<String>,
    val lastLoginAt: String?,
) {
    companion object {
        fun from(user: AdminUser): AdminUserDto = AdminUserDto(
            id = user.id,
            username = user.username,
            role = user.role.name,
            permissions = ROLE_PERMISSIONS[user.role].orEmpty().map { it.name }.sorted(),
            lastLoginAt = user.lastLoginAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
    }
}

@Serializable
data class LoginResponse(val user: AdminUserDto)

@Serializable
data class ErrorResponse(val error: String)

// ====================================================================
//  PR 2 监控 DTO（/admin/api/overview /rooms /players /sessions /games）
//
//  脱敏约束（CLAUDE.md 约束 10）：
//  - playerIdMasked / sessionIdMasked / hostIdMasked：UUID 取前 8 hex + "..."；
//    AI_N 形式的 AI id 保持原样
//  - 永远不暴露 hands（只暴露 handSize）
//  - 永远不暴露 password_hash / session token
// ====================================================================

@Serializable
data class OverviewDto(
    val serverStartedAt: String,          // ISO-8601
    val uptimeSeconds: Long,
    val totalRooms: Int,
    val roomsByStatus: Map<String, Int>,  // "WAITING" / "IN_GAME" / ...
    val totalPlayers: Int,
    val humanPlayersConnected: Int,
    val humanPlayersDisconnected: Int,
    val aiPlayers: Int,
    val aiSubstituted: Int,
    val totalSessions: Int,
    val jvmHeapUsedMb: Long,
    val jvmHeapMaxMb: Long,
    val todayGameCount: Int,
    val totalGameCount: Long,
)

@Serializable
data class RoomSummaryDto(
    val roomId: String,
    val roomCode: String,
    val roomName: String,
    val status: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val humanCount: Int,
    val aiCount: Int,
    val phase: String?,
    val currentPlayerIndex: Int?,
    val teamAScore: Int,
    val teamBScore: Int,
    val version: Long,
    val serverAiDelayMs: Int,
)

@Serializable
data class RoomPlayerDetailDto(
    val playerIdMasked: String,
    val name: String,
    val seatIndex: Int,
    val team: String,
    val isAI: Boolean,
    val isAISubstitute: Boolean,
    val isConnected: Boolean,
    val isReady: Boolean,
    val takeoverAiDelayMs: Int,
    val handSize: Int,
    val collectedScore: Int,
)

@Serializable
data class RoomDetailDto(
    val summary: RoomSummaryDto,
    val players: List<RoomPlayerDetailDto>,
    val lastPlayedGroup: SerializedCardGroup?,
    val lastPlayerId: Int?,
    val consecutivePasses: Int,
    val currentRoundScore: Int,
    val finishOrder: List<Int>,
)

@Serializable
data class PlayerSummaryDto(
    val playerIdMasked: String,
    val name: String,
    val roomId: String,
    val roomCode: String,
    val seatIndex: Int,
    val team: String,
    val isAI: Boolean,
    val isAISubstitute: Boolean,
    val isConnected: Boolean,
    val handSize: Int,
    val collectedScore: Int,
)

@Serializable
data class SessionDto(
    val sessionIdMasked: String,
    val roomId: String?,
    val playerName: String,
    val seatIndex: Int,
    val connectedAtIso: String,
    val connectedSeconds: Long,
)

@Serializable
data class GameSummaryDto(
    val id: Long,
    val roomCode: String,
    val startedAt: String,
    val endedAt: String,
    val durationMs: Long,
    val playerCount: Int,
    val humanCount: Int,
    val aiCount: Int,
    val winnerTeam: String,
    val trigger: String,
    val teamAScore: Int,
    val teamBScore: Int,
)

@Serializable
data class GamePlayerDto(
    val seatIndex: Int,
    val playerIdMasked: String,
    val name: String,
    val team: String,
    val isAI: Boolean,
    val wasSubstituted: Boolean,
    val finished: Boolean,
    val finishOrder: Int,
    val collectedScore: Int,
    val finalHandSize: Int,
)

@Serializable
data class GameDetailDto(
    val summary: GameSummaryDto,
    val players: List<GamePlayerDto>,
)

// ====================================================================
//  脱敏工具：UUID 取 prefix。AI_N 保留原样（运维需要快速识别 AI）
// ====================================================================

internal fun maskId(id: String): String {
    if (id.startsWith("AI_")) return id
    if (id.length <= 8) return id
    return id.substring(0, 8) + "..."
}
