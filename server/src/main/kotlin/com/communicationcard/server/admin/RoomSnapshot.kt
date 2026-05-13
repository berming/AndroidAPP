package com.communicationcard.server.admin

import com.communicationcard.game.network.SerializedCardGroup

/**
 * 房间的 immutable 快照。SnapshotBuilder 在 [com.communicationcard.server.ServerGameManager.withRoomLock]
 * 内构造一份；后续渲染 DTO / 序列化 JSON 全部在锁外做。
 *
 * **重要约束（CLAUDE.md 约束 9）**：所有 [Map] / [List] 字段必须是 defensive
 * copy（toMap / toList），不允许直接持有 ServerGameState 内部容器的引用。
 *
 * **重要约束（CLAUDE.md 约束 10）**：本结构**不**含 `hands`（牌面内容）；
 * 只保留 `handSizes`（牌的数量）。
 */
internal data class RoomSnapshot(
    val roomId: String,
    val roomCode: String,
    val roomName: String,
    val status: String,
    val hostIdMasked: String,
    val maxPlayers: Int,
    val players: List<PlayerSnapshot>,
    val serverAiDelayMs: Int,
    val phase: String?,
    val currentPlayerIndex: Int?,
    val handSizes: Map<Int, Int>,
    val playerScores: Map<Int, Int>,
    val lastPlayedGroup: SerializedCardGroup?,
    val lastPlayerId: Int?,
    val consecutivePasses: Int,
    val currentRoundScore: Int,
    val teamAScore: Int,
    val teamBScore: Int,
    val finishOrder: List<Int>,
    val version: Long,
)

internal data class PlayerSnapshot(
    val playerIdRaw: String,      // 完整 UUID，渲染 DTO 时由调用方 mask
    val name: String,
    val seatIndex: Int,
    val team: String,
    val isAI: Boolean,
    val isAISubstitute: Boolean,
    val isConnected: Boolean,
    val isReady: Boolean,
    val takeoverAiDelayMs: Int,
)
