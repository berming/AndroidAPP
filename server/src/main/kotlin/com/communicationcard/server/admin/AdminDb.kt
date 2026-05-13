package com.communicationcard.server.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * SQLite 连接包装：admin 模块下所有持久化都走这一个 [AdminDb] 实例。
 *
 * 设计选择：
 * - 单一 [Connection]：admin 数据是极低并发场景（5 人以内运维点击），
 *   不引入 HikariCP；一根连接 + [Mutex] 序列化所有访问足够，简单可读
 * - WAL + synchronous=NORMAL：允许后续 PR（游戏关键路径 enqueue
 *   GameRecord）异步写入不被读阻塞
 * - foreign_keys=ON：admin_sessions.admin_id ON DELETE CASCADE 才能生效
 *
 * 调用约定：
 * - 写操作必须走 [tx] 或 [withConnection]，自动持锁
 * - 只读查询也走同一接口（简单优于过度设计；SQLite 单连接读写无竞态）
 */
class AdminDb(private val dbPath: String) : AutoCloseable {

    private val connection: Connection by lazy {
        // 显式加载 sqlite-jdbc driver（某些 JDK + slim classpath 环境需要）
        Class.forName("org.sqlite.JDBC")
        val url = if (dbPath == IN_MEMORY) "jdbc:sqlite::memory:" else "jdbc:sqlite:$dbPath"
        DriverManager.getConnection(url).apply {
            autoCommit = true
            createStatement().use { stmt ->
                // 内存库不支持 WAL，跳过；磁盘库启 WAL + NORMAL 兼容低耐久度
                if (dbPath != IN_MEMORY) {
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA synchronous=NORMAL")
                }
                stmt.execute("PRAGMA foreign_keys=ON")
            }
        }
    }

    private val mutex = Mutex()

    /**
     * 在 IO dispatcher 上拿锁后访问连接。所有 admin DB 操作（含读）走此入口。
     */
    suspend fun <T> withConnection(block: (Connection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block(connection) }
    }

    /**
     * 显式事务（autoCommit=false → 操作 → commit / rollback）。
     */
    suspend fun <T> tx(block: (Connection) -> T): T = withConnection { conn ->
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            try { conn.rollback() } catch (_: Throwable) { /* ignore */ }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * 幂等迁移。新增字段走 `ALTER TABLE ... ADD COLUMN`（也写在这里）；
     * 删字段 / 改字段需新表 + INSERT SELECT + DROP + RENAME，单事务内做。
     */
    suspend fun runMigrations() = withConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(SCHEMA_ADMIN_USERS)
            stmt.executeUpdate(SCHEMA_ADMIN_SESSIONS)
            stmt.executeUpdate(INDEX_SESSIONS_ADMIN)
            stmt.executeUpdate(INDEX_SESSIONS_EXPIRES)
        }
    }

    override fun close() {
        try { connection.close() } catch (_: Throwable) { /* ignore */ }
    }

    companion object {
        const val IN_MEMORY = ":memory:"

        private val SCHEMA_ADMIN_USERS = """
            CREATE TABLE IF NOT EXISTS admin_users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                last_login_at INTEGER
            )
        """.trimIndent()

        private val SCHEMA_ADMIN_SESSIONS = """
            CREATE TABLE IF NOT EXISTS admin_sessions (
                token TEXT PRIMARY KEY,
                admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL,
                user_agent TEXT,
                ip TEXT
            )
        """.trimIndent()

        private const val INDEX_SESSIONS_ADMIN =
            "CREATE INDEX IF NOT EXISTS idx_sessions_admin ON admin_sessions(admin_id)"

        private const val INDEX_SESSIONS_EXPIRES =
            "CREATE INDEX IF NOT EXISTS idx_sessions_expires ON admin_sessions(expires_at)"
    }
}

/**
 * 工具：把 ResultSet 当 sequence 用，自动 close。
 */
internal inline fun <T> ResultSet.useRows(block: (ResultSet) -> T): T = this.use { rs -> block(rs) }
