package com.communicationcard.server.admin

import com.communicationcard.game.network.SerializedGameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
     * PR 5d：按 roomId 累积 SerializedGameEvent。游戏结束时 [enqueue] 调
     * [drainEvents] 把对应 roomId 的事件序列连同 GameRecord 一并入 SQLite。
     *
     * - 同房间多次游戏会复用同 roomId：所以每次 [enqueue] 会 [drainEvents] 清空，
     *   下一局从 seq=0 重新开始
     * - 房间未结束就被 deleteRoom：缓冲条目会一直在 map 里，直到房间复活或
     *   服务重启。每条 ~50-300 字节，几百条不会爆。生产可定期 [forgetRoom]
     */
    private val eventBuffer = ConcurrentHashMap<String, MutableList<GameEventRecord>>()
    private val seqByRoom = ConcurrentHashMap<String, AtomicInteger>()

    private val eventJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    /**
     * PR 5d：每个 SerializedGameEvent 累积到房间事件缓冲。非阻塞、线程安全。
     * 由 ServerGameManager.gameEventListener 在 broadcastActionResult 内调用。
     */
    fun recordEvent(roomId: String, event: SerializedGameEvent) {
        val seq = seqByRoom.getOrPut(roomId) { AtomicInteger(0) }.getAndIncrement()
        val record = GameEventRecord(
            seq = seq,
            eventType = event.eventTypeName(),
            payloadJson = eventJson.encodeToString(SerializedGameEvent.serializer(), event),
            createdAt = System.currentTimeMillis(),
        )
        eventBuffer.compute(roomId) { _, existing ->
            val list = existing ?: java.util.concurrent.CopyOnWriteArrayList()
            list.add(record)
            list
        }
    }

    /**
     * 房间销毁时调用（可选）：丢弃当前累积但未持久化的事件。
     */
    fun forgetRoom(roomId: String) {
        eventBuffer.remove(roomId)
        seqByRoom.remove(roomId)
    }

    private fun drainEvents(roomId: String): List<GameEventRecord> {
        val list = eventBuffer.remove(roomId) ?: return emptyList()
        seqByRoom.remove(roomId)
        return list.toList()
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
                // id DESC tiebreaker：两条 enqueue 同毫秒（System.currentTimeMillis 分辨率
                // 1 ms，IO 协程也可能跑得快）时单 `started_at DESC` 顺序不确定，导致
                // GameHistoryStoreTest > "enqueue then async insert ..." CI flaky。AUTOINCREMENT
                // 的 id 与插入顺序单调一致，是稳定 tiebreaker。
                append(" ORDER BY started_at DESC, id DESC LIMIT ?")
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

    /**
     * PR 5b 趋势统计：过去 N 天每天的游戏数 / 平均时长 / 真人局数。
     *
     * 用 SQLite 的 `date(epoch_ms/1000, 'unixepoch')` 把 epoch ms 截到 UTC 当天，
     * 按天 GROUP BY。返回值含 N 天的所有日期（即便当天 0 局也补一行 0 计数），
     * 方便前端直接喂图表 X 轴。
     */
    suspend fun trendByDay(days: Int): List<TrendPointDto> = db.withConnection { conn ->
        val daysClamped = days.coerceIn(1, 90)
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.now(zone)
        val startOfTodayUtc = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowStart = startOfTodayUtc - (daysClamped - 1) * 24L * 3600 * 1000

        // 查每天的聚合
        val byDay = mutableMapOf<String, TrendPointDto>()
        conn.prepareStatement(
            """
            SELECT date(started_at/1000, 'unixepoch') AS day,
                   COUNT(*) AS cnt,
                   AVG(duration_ms) AS avg_ms,
                   SUM(CASE WHEN human_count = player_count THEN 1 ELSE 0 END) AS human_only
            FROM games
            WHERE started_at >= ?
            GROUP BY day
            ORDER BY day
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, windowStart)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val day = rs.getString(1)
                    byDay[day] = TrendPointDto(
                        date = day,
                        gameCount = rs.getInt(2),
                        avgDurationSeconds = (rs.getDouble(3) / 1000).toLong(),
                        humanGameCount = rs.getInt(4),
                    )
                }
            }
        }
        // 补全 N 天（即使当天 0 局也要 0 行），用上方 `today` 重用
        (0 until daysClamped).map { offset ->
            val date = today.minusDays((daysClamped - 1 - offset).toLong()).toString()
            byDay[date] ?: TrendPointDto(
                date = date, gameCount = 0, avgDurationSeconds = 0, humanGameCount = 0,
            )
        }
    }

    // === 内部 helpers ===

    private suspend fun insertOne(record: GameRecord) {
        // PR 5d: 在事务外 drain 事件缓冲（drain 不动 DB）；事务内一并写 games +
        // game_players + game_events，FK CASCADE 保证一致性
        val events = drainEvents(record.roomId)
        db.tx { conn ->
            val gameId = insertGame(conn, record)
            for (player in record.players) {
                insertGamePlayer(conn, gameId, player)
            }
            for (event in events) {
                insertGameEvent(conn, gameId, event)
            }
        }
    }

    private fun insertGameEvent(conn: Connection, gameId: Long, ev: GameEventRecord) {
        conn.prepareStatement(
            "INSERT INTO game_events (game_id, seq, event_type, payload, created_at) " +
                "VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setLong(1, gameId)
            ps.setInt(2, ev.seq)
            ps.setString(3, ev.eventType)
            ps.setString(4, ev.payloadJson)
            ps.setLong(5, ev.createdAt)
            ps.executeUpdate()
        }
    }

    suspend fun listEvents(gameId: Long): List<GameEventDto> = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT seq, event_type, payload, created_at FROM game_events " +
                "WHERE game_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setLong(1, gameId)
            val out = mutableListOf<GameEventDto>()
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val payloadStr = rs.getString(3)
                    val payloadJson: JsonElement = try {
                        eventJson.parseToJsonElement(payloadStr)
                    } catch (_: Throwable) {
                        kotlinx.serialization.json.JsonPrimitive(payloadStr)
                    }
                    out.add(
                        GameEventDto(
                            seq = rs.getInt(1),
                            eventType = rs.getString(2),
                            payload = payloadJson,
                            createdAt = Instant.ofEpochMilli(rs.getLong(4)).toString(),
                        )
                    )
                }
            }
            out
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
