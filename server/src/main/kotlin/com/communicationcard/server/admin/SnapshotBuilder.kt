package com.communicationcard.server.admin

import com.communicationcard.server.ServerContext
import com.communicationcard.server.ServerPlayer
import com.communicationcard.server.ServerRoom
import java.time.Instant

/**
 * Admin 监控端点的"渲染层"：把内存房间状态 + 历史 SQLite 数据合成成 DTO。
 *
 * 设计原则（CLAUDE.md 约束 9）：
 * - 房间维度数据：先在 [com.communicationcard.server.ServerGameManager.withRoomLock]
 *   内做 defensive copy → [RoomSnapshot]，**锁外**渲染 DTO
 * - 跨房间维度（overview / players / sessions）：直接读 ConcurrentHashMap
 *   弱一致快照（`.toList()` / `.values.toList()`），不持锁
 * - 进程级指标（JVM heap）：[Runtime.getRuntime] 实时读
 */
class SnapshotBuilder(
    private val serverCtx: ServerContext,
    private val historyStore: GameHistoryStore,
) {

    suspend fun overview(): OverviewDto {
        val nowMs = System.currentTimeMillis()
        val uptimeSec = (nowMs - serverCtx.startedAtEpochMs) / 1000

        val rooms = serverCtx.roomManager.allRoomsSnapshot()
        val counts = serverCtx.roomManager.snapshotCounts()

        // 跨房间扫一遍人员；O(rooms × players) 但实际 < 100，纳秒级
        var humanConnected = 0
        var humanDisconnected = 0
        var aiPlayers = 0
        var aiSubstituted = 0
        var totalPlayers = 0
        for (room in rooms) {
            for (player in room.players) {
                totalPlayers++
                when {
                    player.isAI -> aiPlayers++
                    player.isAISubstitute -> {
                        aiSubstituted++
                        if (!player.isConnected) humanDisconnected++ else humanConnected++
                    }
                    player.isConnected -> humanConnected++
                    else -> humanDisconnected++
                }
            }
        }

        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMaxMb = runtime.maxMemory() / MB

        val todayCount = historyStore.countSince(startOfTodayUtcMs())
        val totalCount = historyStore.countAll()

        return OverviewDto(
            serverStartedAt = Instant.ofEpochMilli(serverCtx.startedAtEpochMs).toString(),
            uptimeSeconds = uptimeSec,
            totalRooms = rooms.size,
            roomsByStatus = counts.mapKeys { it.key.name },
            totalPlayers = totalPlayers,
            humanPlayersConnected = humanConnected,
            humanPlayersDisconnected = humanDisconnected,
            aiPlayers = aiPlayers,
            aiSubstituted = aiSubstituted,
            totalSessions = serverCtx.sessions.size,
            jvmHeapUsedMb = heapUsedMb,
            jvmHeapMaxMb = heapMaxMb,
            todayGameCount = todayCount,
            totalGameCount = totalCount,
        )
    }

    /**
     * 所有房间的摘要列表（lock-free，弱一致）。
     */
    suspend fun rooms(): List<RoomSummaryDto> {
        val snap = serverCtx.roomManager.allRoomsSnapshot()
        return snap.map { it.toSummaryDto() }
    }

    /**
     * 单个房间的详情。短暂持锁拿 [RoomSnapshot]，锁外渲染 DTO。
     */
    suspend fun room(roomId: String): RoomDetailDto? {
        val room = serverCtx.roomManager.getRoom(roomId) ?: return null
        val snapshot = serverCtx.gameManager.withRoomLock(room) { captureRoom(room) }
        return snapshot.toDetailDto()
    }

    /**
     * 跨房间扁平玩家列表。
     */
    suspend fun players(): List<PlayerSummaryDto> {
        val rooms = serverCtx.roomManager.allRoomsSnapshot()
        val result = mutableListOf<PlayerSummaryDto>()
        for (room in rooms) {
            // 短暂持锁取 hands.size + playerScores
            val handAndScore = serverCtx.gameManager.withRoomLock(room) {
                val state = room.gameState
                val sizes = state?.hands?.mapValues { it.value.size }?.toMap() ?: emptyMap()
                val scores = state?.playerScores?.toMap() ?: emptyMap()
                sizes to scores
            }
            val (handSizes, playerScores) = handAndScore
            for (player in room.players.toList()) {
                result.add(
                    PlayerSummaryDto(
                        playerIdMasked = maskId(player.id),
                        name = player.name,
                        roomId = room.roomId,
                        roomCode = room.roomCode,
                        seatIndex = player.seatIndex,
                        team = player.team,
                        isAI = player.isAI,
                        isAISubstitute = player.isAISubstitute,
                        isConnected = player.isConnected,
                        handSize = handSizes[player.seatIndex] ?: 0,
                        collectedScore = playerScores[player.seatIndex] ?: 0,
                    )
                )
            }
        }
        return result
    }

    /**
     * 当前活跃 WS 会话列表（lock-free）。
     */
    suspend fun sessions(): List<SessionDto> {
        val now = System.currentTimeMillis()
        return serverCtx.sessions.values.toList().map { s ->
            SessionDto(
                sessionIdMasked = maskId(s.id),
                roomId = s.roomId,
                playerName = s.playerName,
                seatIndex = s.seatIndex,
                connectedAtIso = Instant.ofEpochMilli(s.connectedAt).toString(),
                connectedSeconds = (now - s.connectedAt) / 1000,
            )
        }
    }

    // === 内部 helpers ===

    private fun captureRoom(room: ServerRoom): RoomSnapshot {
        val state = room.gameState
        return RoomSnapshot(
            roomId = room.roomId,
            roomCode = room.roomCode,
            roomName = room.roomName,
            status = room.status.name,
            hostIdMasked = maskId(room.hostId),
            maxPlayers = room.maxPlayers,
            players = room.players.map { it.toPlayerSnapshot() },
            serverAiDelayMs = room.serverAiDelayMs,
            phase = state?.phase,
            currentPlayerIndex = state?.currentPlayerIndex,
            handSizes = state?.hands?.mapValues { it.value.size }?.toMap() ?: emptyMap(),
            playerScores = state?.playerScores?.toMap() ?: emptyMap(),
            // server 端 lastPlayedGroup 是 com.communicationcard.server.ServerCardGroup
            // (server-side type, with ServerCard)；admin DTO 想要 :shared 的
            // SerializedCardGroup。这里做一次类型转换 + defensive copy
            lastPlayedGroup = state?.lastPlayedGroup?.let { scg ->
                com.communicationcard.game.network.SerializedCardGroup(
                    cards = scg.cards.map { c ->
                        com.communicationcard.game.network.SerializedCard(c.rank, c.suit, c.deckIndex)
                    },
                    type = scg.type,
                    primaryRank = scg.primaryRank,
                )
            },
            lastPlayerId = state?.lastPlayerId,
            consecutivePasses = state?.consecutivePasses ?: 0,
            currentRoundScore = state?.currentRoundScore ?: 0,
            teamAScore = state?.teamAScore ?: 0,
            teamBScore = state?.teamBScore ?: 0,
            finishOrder = state?.finishOrder?.toList() ?: emptyList(),
            version = state?.version ?: 0L,
        )
    }

    private fun ServerPlayer.toPlayerSnapshot() = PlayerSnapshot(
        playerIdRaw = id,
        name = name,
        seatIndex = seatIndex,
        team = team,
        isAI = isAI,
        isAISubstitute = isAISubstitute,
        isConnected = isConnected,
        isReady = isReady,
        takeoverAiDelayMs = takeoverAiDelayMs,
    )

    private fun ServerRoom.toSummaryDto(): RoomSummaryDto {
        val state = gameState
        var humanCount = 0
        var aiCount = 0
        players.forEach { if (it.isAI) aiCount++ else humanCount++ }
        return RoomSummaryDto(
            roomId = roomId,
            roomCode = roomCode,
            roomName = roomName,
            status = status.name,
            playerCount = players.size,
            maxPlayers = maxPlayers,
            humanCount = humanCount,
            aiCount = aiCount,
            phase = state?.phase,
            currentPlayerIndex = state?.currentPlayerIndex,
            teamAScore = state?.teamAScore ?: 0,
            teamBScore = state?.teamBScore ?: 0,
            version = state?.version ?: 0L,
            serverAiDelayMs = serverAiDelayMs,
        )
    }

    private fun RoomSnapshot.toDetailDto(): RoomDetailDto {
        val humanCount = players.count { !it.isAI }
        val aiCount = players.size - humanCount
        val summary = RoomSummaryDto(
            roomId = roomId,
            roomCode = roomCode,
            roomName = roomName,
            status = status,
            playerCount = players.size,
            maxPlayers = maxPlayers,
            humanCount = humanCount,
            aiCount = aiCount,
            phase = phase,
            currentPlayerIndex = currentPlayerIndex,
            teamAScore = teamAScore,
            teamBScore = teamBScore,
            version = version,
            serverAiDelayMs = serverAiDelayMs,
        )
        val playersDto = players.map { p ->
            RoomPlayerDetailDto(
                playerIdMasked = maskId(p.playerIdRaw),
                name = p.name,
                seatIndex = p.seatIndex,
                team = p.team,
                isAI = p.isAI,
                isAISubstitute = p.isAISubstitute,
                isConnected = p.isConnected,
                isReady = p.isReady,
                takeoverAiDelayMs = p.takeoverAiDelayMs,
                handSize = handSizes[p.seatIndex] ?: 0,
                collectedScore = playerScores[p.seatIndex] ?: 0,
            )
        }
        return RoomDetailDto(
            summary = summary,
            players = playersDto,
            lastPlayedGroup = lastPlayedGroup,
            lastPlayerId = lastPlayerId,
            consecutivePasses = consecutivePasses,
            currentRoundScore = currentRoundScore,
            finishOrder = finishOrder,
        )
    }

    companion object {
        private const val MB = 1024L * 1024L

        private fun startOfTodayUtcMs(): Long {
            val zone = java.time.ZoneOffset.UTC
            val today = java.time.LocalDate.now(zone)
            return today.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}
