package com.communicationcard.server.admin.alert

import com.communicationcard.server.admin.AdminDb
import com.communicationcard.server.admin.AlertDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/**
 * alerts 表 CRUD。所有写入走 [AdminDb.withConnection]（内含 mutex），
 * 调用方不要再嵌套游戏 mutex（约束 9）。
 */
class AlertStore(private val db: AdminDb) {

    /**
     * 插入告警（如果同 (rule, room_id) 在 cooldown 窗口内已有未 ack 行 → 跳过）。
     * @return 真正插入的行 id；被 cooldown 抑制时返回 null。
     */
    suspend fun insertIfNotCoolingDown(
        candidate: AlertCandidate,
        cooldownMs: Long,
        nowMs: Long,
    ): Long? = db.withConnection { conn ->
        val cooldownStart = nowMs - cooldownMs
        if (existsRecent(conn, candidate.rule, candidate.roomId, cooldownStart)) {
            return@withConnection null
        }
        val payloadJson = if (candidate.payload.isEmpty()) null else jsonEncode(candidate.payload)
        conn.prepareStatement(
            """
            INSERT INTO alerts (rule, severity, room_id, player_id_masked, message, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, candidate.rule)
            ps.setString(2, candidate.severity)
            if (candidate.roomId != null) ps.setString(3, candidate.roomId) else ps.setNull(3, java.sql.Types.VARCHAR)
            if (candidate.playerIdMasked != null) ps.setString(4, candidate.playerIdMasked) else ps.setNull(4, java.sql.Types.VARCHAR)
            ps.setString(5, candidate.message)
            if (payloadJson != null) ps.setString(6, payloadJson) else ps.setNull(6, java.sql.Types.VARCHAR)
            ps.setLong(7, nowMs)
            ps.executeUpdate()
            ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
        }
    }

    suspend fun listUnacked(limit: Int): List<AlertDto> = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT a.id, a.rule, a.severity, a.room_id, a.player_id_masked, a.message, " +
                "a.payload, a.created_at, a.acked_at, u.username " +
                "FROM alerts a LEFT JOIN admin_users u ON u.id = a.acked_by " +
                "WHERE a.acked_at IS NULL ORDER BY a.created_at DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, 500))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AlertDto>()
                while (rs.next()) out.add(rs.toAlertDto())
                out
            }
        }
    }

    suspend fun listRecent(limit: Int): List<AlertDto> = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT a.id, a.rule, a.severity, a.room_id, a.player_id_masked, a.message, " +
                "a.payload, a.created_at, a.acked_at, u.username " +
                "FROM alerts a LEFT JOIN admin_users u ON u.id = a.acked_by " +
                "ORDER BY a.created_at DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, 500))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AlertDto>()
                while (rs.next()) out.add(rs.toAlertDto())
                out
            }
        }
    }

    suspend fun ack(alertId: Long, ackedByAdminId: Long, nowMs: Long): Boolean = db.withConnection { conn ->
        conn.prepareStatement(
            "UPDATE alerts SET acked_at = ?, acked_by = ? WHERE id = ? AND acked_at IS NULL"
        ).use { ps ->
            ps.setLong(1, nowMs)
            ps.setLong(2, ackedByAdminId)
            ps.setLong(3, alertId)
            ps.executeUpdate() > 0
        }
    }

    suspend fun countUnacked(): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM alerts WHERE acked_at IS NULL").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    // === internal ===

    private fun existsRecent(conn: Connection, rule: String, roomId: String?, sinceMs: Long): Boolean {
        val sql = if (roomId == null) {
            "SELECT 1 FROM alerts WHERE rule = ? AND room_id IS NULL AND created_at >= ? LIMIT 1"
        } else {
            "SELECT 1 FROM alerts WHERE rule = ? AND room_id = ? AND created_at >= ? LIMIT 1"
        }
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, rule)
            if (roomId == null) {
                ps.setLong(2, sinceMs)
            } else {
                ps.setString(2, roomId)
                ps.setLong(3, sinceMs)
            }
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun ResultSet.toAlertDto(): AlertDto = AlertDto(
        id = getLong(1),
        rule = getString(2),
        severity = getString(3),
        roomId = getString(4),
        playerIdMasked = getString(5),
        message = getString(6),
        payload = getString(7)?.let { jsonDecode(it) },
        createdAt = Instant.ofEpochMilli(getLong(8)).toString(),
        ackedAt = if (getObject(9) == null) null else Instant.ofEpochMilli(getLong(9)).toString(),
        ackedBy = getString(10),
    )

    companion object {
        // 用 kotlinx-serialization 的 Json 跑 Map<String, String> 即可；
        // payload 仅做诊断详情，不在协议层使用
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        internal fun jsonEncode(payload: Map<String, String>): String =
            json.encodeToString(payload)
        internal fun jsonDecode(s: String): Map<String, String>? = try {
            json.decodeFromString<Map<String, String>>(s)
        } catch (_: Throwable) { null }
    }
}

