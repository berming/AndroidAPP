package com.communicationcard.server.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.sql.Connection
import java.time.Instant

/**
 * 历史游戏异步入库。设计原则（CLAUDE.md 约束 9）：
 *
 * - **不在 mutex 内做 IO**：game-end listener 在 `broadcastActionResult` 内被
 *   调用——已在锁外。listener 同步构造 [GameRecord] 后 [enqueue]。
 * - **enqueue 是非阻塞**：`Channel.UNLIMITED` capacity，trySend 必成功。
 *   即便 IO 协程暂时拥堵，broadcast 线程也不会被拖慢一纳秒。
 * - **永不抛出**：IO 协程内部所有异常吞掉 + logger.warn；保活意图明确——
 *   一条记录丢失不应让整条管道死掉。
 * - **优雅停机**：[stop] 关 Channel + join 协程；丢的话只丢未消费的尾部。
 *
 * 默认共享 [AdminDb] 的 mutex。admin 路由读和 history 写串行化执行，
 * SQLite WAL 单连接下天然安全。
 */
class GameHistoryStore(private val db: AdminDb) {

    private val channel = Channel<GameRecord>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var consumerJob: Job? = null

    /**
     * 启动单 IO 消费协程。需在 [AdminDb.runMigrations] 之后调用。
     */
    fun start() {
        if (consumerJob != null) return
        consumerJob = scope.launch {
            for (record in channel) {
                try {
                    insertOne(record)
                } catch (e: Throwable) {
                    System.err.println("GameHistoryStore insert failed (dropping): ${e.message}")
                }
            }
        }
    }

    suspend fun stop() {
        channel.close()
        consumerJob?.let {
            try { it.join() } catch (_: Throwable) { /* ignore */ }
        }
        consumerJob = null
    }

    /**
     * 非阻塞 enqueue（UNLIMITED capacity → trySend 必成功）。
     * 调用方：game-end listener（在 broadcastActionResult 内，锁外）。
     */
    fun enqueue(record: GameRecord) {
        channel.trySend(record)
    }

    // === 查询 API（admin 路由用）===

    suspend fun countAll(): Long = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM games").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    suspend fun countSince(epochMs: Long): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM games WHERE started_at >= ?").use { ps ->
            ps.setLong(1, epochMs)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    suspend fun listSummaries(from: Long?, to: Long?, limit: Int): List<GameSummaryDto> =
        db.withConnection { conn ->
            val sql = buildString {
                append(
                    "SELECT id, room_code, started_at, ended_at, duration_ms, player_count, " +
                        "human_count, ai_count, winner_team, trigger, team_a_score, team_b_score " +
                        "FROM games WHERE 1=1"
                )
                if (from != null) append(" AND started_at >= ?")
                if (to != null) append(" AND started_at < ?")
                append(" ORDER BY started_at DESC LIMIT ?")
            }
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (from != null) ps.setLong(idx++, from)
                if (to != null) ps.setLong(idx++, to)
                ps.setInt(idx, limit.coerceIn(1, 500))
                val out = mutableListOf<GameSummaryDto>()
                ps.executeQuery().use { rs ->
                    while (rs.next()) out.add(rs.toGameSummaryDto())
                }
                out
            }
        }

    suspend fun findDetail(id: Long): GameDetailDto? = db.withConnection { conn ->
        val summary = conn.prepareStatement(
            "SELECT id, room_code, started_at, ended_at, duration_ms, player_count, " +
                "human_count, ai_count, winner_team, trigger, team_a_score, team_b_score " +
                "FROM games WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toGameSummaryDto() else null }
        } ?: return@withConnection null

        val players = conn.prepareStatement(
            "SELECT seat_index, player_id_masked, name, team, is_ai, was_substituted, " +
                "finished, finish_order, collected_score, final_hand_size " +
                "FROM game_players WHERE game_id = ? ORDER BY seat_index"
        ).use { ps ->
            ps.setLong(1, id)
            val out = mutableListOf<GamePlayerDto>()
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        GamePlayerDto(
                            seatIndex = rs.getInt(1),
                            playerIdMasked = rs.getString(2),
                            name = rs.getString(3),
                            team = rs.getString(4),
                            isAI = rs.getInt(5) != 0,
                            wasSubstituted = rs.getInt(6) != 0,
                            finished = rs.getInt(7) != 0,
                            finishOrder = rs.getInt(8),
                            collectedScore = rs.getInt(9),
                            finalHandSize = rs.getInt(10),
                        )
                    )
                }
            }
            out
        }
        GameDetailDto(summary, players)
    }

    // === 内部 helpers ===

    private suspend fun insertOne(record: GameRecord) = db.tx { conn ->
        val gameId = insertGame(conn, record)
        for (player in record.players) {
            insertGamePlayer(conn, gameId, player)
        }
    }

    private fun insertGame(conn: Connection, r: GameRecord): Long =
        conn.prepareStatement(
            """
            INSERT INTO games (room_id, room_code, started_at, ended_at, duration_ms,
                player_count, human_count, ai_count, winner_team, trigger,
                team_a_score, team_b_score, final_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, r.roomId)
            ps.setString(2, r.roomCode)
            ps.setLong(3, r.startedAt)
            ps.setLong(4, r.endedAt)
            ps.setLong(5, r.durationMs)
            ps.setInt(6, r.playerCount)
            ps.setInt(7, r.humanCount)
            ps.setInt(8, r.aiCount)
            ps.setString(9, r.winnerTeam)
            ps.setString(10, r.trigger)
            ps.setInt(11, r.teamAScore)
            ps.setInt(12, r.teamBScore)
            ps.setLong(13, r.finalVersion)
            ps.executeUpdate()
            ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
        }

    private fun insertGamePlayer(conn: Connection, gameId: Long, p: GamePlayerRecord) {
        conn.prepareStatement(
            """
            INSERT INTO game_players (game_id, seat_index, player_id_masked, name, team,
                is_ai, was_substituted, finished, finish_order, collected_score, final_hand_size)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, gameId)
            ps.setInt(2, p.seatIndex)
            ps.setString(3, p.playerIdMasked)
            ps.setString(4, p.name)
            ps.setString(5, p.team)
            ps.setInt(6, if (p.isAI) 1 else 0)
            ps.setInt(7, if (p.wasSubstituted) 1 else 0)
            ps.setInt(8, if (p.finished) 1 else 0)
            ps.setInt(9, p.finishOrder)
            ps.setInt(10, p.collectedScore)
            ps.setInt(11, p.finalHandSize)
            ps.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toGameSummaryDto(): GameSummaryDto = GameSummaryDto(
        id = getLong(1),
        roomCode = getString(2),
        startedAt = Instant.ofEpochMilli(getLong(3)).toString(),
        endedAt = Instant.ofEpochMilli(getLong(4)).toString(),
        durationMs = getLong(5),
        playerCount = getInt(6),
        humanCount = getInt(7),
        aiCount = getInt(8),
        winnerTeam = getString(9),
        trigger = getString(10),
        teamAScore = getInt(11),
        teamBScore = getInt(12),
    )
}
