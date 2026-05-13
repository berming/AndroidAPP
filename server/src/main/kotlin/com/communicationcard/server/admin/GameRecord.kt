package com.communicationcard.server.admin

import com.communicationcard.game.network.SerializedGameResult
import com.communicationcard.server.ServerRoom

/**
 * 一局游戏的入库记录（admin 历史持久化）。**immutable**：构造时拍快照，
 * 后续可以放心跨线程 / 跨协程发往 [GameHistoryStore] 的 Channel。
 *
 * 字段语义见 docs/admin-backend.md（PR 4 一起补）。脱敏约束（CLAUDE.md 约束 10）：
 * - playerIdMasked：UUID 前 8 hex；AI_N 保持原样
 * - 不暴露真实手牌，只记 finalHandSize
 */
data class GameRecord(
    val roomId: String,
    val roomCode: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val playerCount: Int,
    val humanCount: Int,
    val aiCount: Int,
    val winnerTeam: String,
    val trigger: String,
    val teamAScore: Int,
    val teamBScore: Int,
    val finalVersion: Long,
    val players: List<GamePlayerRecord>,
) {
    companion object {
        /**
         * 从 server-side 房间 + 结果构造 record。
         * 调用方约定：在 [com.communicationcard.server.ServerGameManager.broadcastActionResult]
         * 的 `result.gameResult?.let { ... }` 分支内调用——此时 `room.gameState`
         * 仍然包含最终的 hand sizes / scores。
         */
        fun capture(room: ServerRoom, result: SerializedGameResult): GameRecord {
            val state = room.gameState
            val ended = System.currentTimeMillis()
            val started = state?.startedAtEpochMs ?: ended
            val players = room.players.toList()  // CopyOnWriteArrayList 安全拷贝
            val finishOrderMap = state?.finishOrder?.withIndex()?.associate { (i, seat) -> seat to (i + 1) } ?: emptyMap()
            val handSizes = state?.hands?.mapValues { it.value.size } ?: emptyMap()
            val scores = state?.playerScores ?: emptyMap()

            val humanCount = players.count { !it.isAI }
            val aiCount = players.size - humanCount

            return GameRecord(
                roomId = room.roomId,
                roomCode = room.roomCode,
                startedAt = started,
                endedAt = ended,
                durationMs = (ended - started).coerceAtLeast(0),
                playerCount = players.size,
                humanCount = humanCount,
                aiCount = aiCount,
                winnerTeam = result.winner ?: "DRAW",
                trigger = result.trigger,
                teamAScore = result.teamAScore,
                teamBScore = result.teamBScore,
                finalVersion = state?.version ?: 0L,
                players = players.map { p ->
                    GamePlayerRecord(
                        seatIndex = p.seatIndex,
                        playerIdMasked = maskId(p.id),
                        name = p.name,
                        team = p.team,
                        isAI = p.isAI,
                        wasSubstituted = p.isAISubstitute,
                        finished = (handSizes[p.seatIndex] ?: 1) == 0,
                        finishOrder = finishOrderMap[p.seatIndex] ?: 0,
                        collectedScore = scores[p.seatIndex] ?: 0,
                        finalHandSize = handSizes[p.seatIndex] ?: 0,
                    )
                },
            )
        }
    }
}

data class GamePlayerRecord(
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
