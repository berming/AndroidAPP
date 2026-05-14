package com.communicationcard.server.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64

/**
 * Admin 鉴权服务：账号 CRUD（最小集合）+ 登录 / 校验 / 登出 / 改密。
 *
 * 设计原则：
 * - **不**用 Ktor `Sessions` / `Authentication` plugin；自己读 cookie 校验 token
 *   （单一 cookie 用途，自己写更直观）
 * - bcrypt cost factor = 12（约 250 ms 单次校验，登录路径足够；如未来开放注册
 *   或玩家账号引入再降到 10-11）
 * - sessionToken：32 字节 SecureRandom → base64url（44 字符），存 cookie + 服务端表
 * - validate 每次刷新 expires_at（rolling expiry），无活动 8 小时后过期
 */
class AdminAuthService(
    private val db: AdminDb,
    private val sessionTtlSeconds: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 首次启动时调用：若 admin_users 表为空 → 用初始账号入库。
     * @return true 表示插入了初始账号；false 表示表非空跳过
     */
    suspend fun bootstrapInitialAdmin(username: String, plainPassword: String): Boolean {
        require(username.isNotBlank()) { "ADMIN_INITIAL_USERNAME must not be blank" }
        require(plainPassword.length >= MIN_PASSWORD_LEN) {
            "ADMIN_INITIAL_PASSWORD must be at least $MIN_PASSWORD_LEN characters"
        }
        // pr-reviewer PR #61 P2 #7：先 COUNT；表非空直接 return false，省 250 ms
        // bcrypt 启动 overhead（hashPassword 在表非空时永远是 wasted work）
        val empty = db.withConnection { conn -> countAdmins(conn) == 0 }
        if (!empty) return false

        // 表确实空 → 才付 bcrypt 成本。bcrypt CPU-bound：在锁外算好 hash 再插入
        // （pr-reviewer PR #61 P2 #1：避免 hashpw 250 ms 阻塞 DB mutex）
        val passwordHash = withContext(Dispatchers.Default) { hashPassword(plainPassword) }
        return db.withConnection { conn ->
            // 二次校验（防极小并发窗口：两个进程同时检测到表空）
            if (countAdmins(conn) > 0) return@withConnection false
            insertAdmin(
                conn = conn,
                username = username,
                passwordHash = passwordHash,
                role = AdminRole.SUPER_ADMIN,
                createdAt = clock(),
            )
            true
        }
    }

    /**
     * 登录：用户名 + 明文密码 → 校验 → 写 session 行 → 返回 token。
     * @return token 字符串；用户名 / 密码 / is_active 失败均返回 null（不区分原因，
     *         避免账号枚举）
     */
    suspend fun login(username: String, password: String, userAgent: String?, ip: String?): String? {
        // bcrypt.checkpw 单次约 250 ms；放 DB lock 外做，避免拖慢其他查询。
        // 但要先取出 password_hash —— 表锁内取一次，然后表锁外校验。
        // 关键：bcrypt 是 **CPU-bound 同步操作**，必须 withContext(Dispatchers.Default)
        // 否则会阻塞 Ktor / Netty 的 IO 线程，登录洪泛时 N 个并发登录吃 N 个 worker
        // 250 ms（pr-reviewer PR #61 P2 #1）
        val user = db.withConnection { conn -> findActiveByUsername(conn, username) } ?: run {
            // 即使用户不存在也跑一次 bcrypt 防 timing attack。常数耗时即可。
            withContext(Dispatchers.Default) { BCrypt.checkpw(password, DUMMY_HASH_FOR_TIMING) }
            return null
        }
        val ok = withContext(Dispatchers.Default) { BCrypt.checkpw(password, user.passwordHash) }
        if (!ok) return null

        val now = clock()
        val token = generateToken()
        db.withConnection { conn ->
            insertSession(
                conn = conn,
                token = token,
                adminId = user.id,
                createdAt = now,
                expiresAt = now + sessionTtlSeconds * 1000,
                userAgent = userAgent?.take(USER_AGENT_MAX_LEN),
                ip = ip?.take(IP_MAX_LEN),
            )
            updateLastLoginAt(conn, user.id, now)
        }
        return token
    }

    /**
     * 校验 token：查表 + 检查过期 + 滚动续期 → 返回对应 AdminUser，或 null。
     * 在每次 admin 请求开头调用。
     */
    suspend fun validate(token: String?): AdminUser? {
        if (token.isNullOrBlank()) return null
        val now = clock()
        return db.withConnection { conn ->
            val session = findSession(conn, token) ?: return@withConnection null
            if (session.expiresAt < now) {
                // 过期 → 删 + 返回 null
                deleteSession(conn, token)
                return@withConnection null
            }
            val user = findAdminById(conn, session.adminId) ?: return@withConnection null
            if (!user.isActive) {
                deleteSession(conn, token)
                return@withConnection null
            }
            // Rolling expiry：每次有效访问刷新 expires_at 和 last_used_at
            updateSessionExpiry(
                conn = conn,
                token = token,
                expiresAt = now + sessionTtlSeconds * 1000,
                lastUsedAt = now,
            )
            user
        }
    }

    suspend fun logout(token: String?) {
        if (token.isNullOrBlank()) return
        db.withConnection { conn -> deleteSession(conn, token) }
    }

    /**
     * 改密：必须先验旧密码。改密后**保留**当前 session（用户体验：改密后不掉线）；
     * 但删除该 admin 的**其他**所有 session（防御：旧 cookie 在别处仍然有效）。
     */
    suspend fun changePassword(
        adminId: Long,
        keepToken: String?,
        oldPassword: String,
        newPassword: String,
    ): ChangePasswordResult {
        require(newPassword.length >= MIN_PASSWORD_LEN) {
            "new password must be at least $MIN_PASSWORD_LEN characters"
        }
        val user = db.withConnection { conn -> findAdminById(conn, adminId) }
            ?: return ChangePasswordResult.AdminNotFound
        // bcrypt CPU-bound → Dispatchers.Default 避免阻塞 Ktor worker（同 login）
        val ok = withContext(Dispatchers.Default) { BCrypt.checkpw(oldPassword, user.passwordHash) }
        if (!ok) {
            return ChangePasswordResult.WrongOldPassword
        }
        val newHash = withContext(Dispatchers.Default) { hashPassword(newPassword) }
        db.withConnection { conn ->
            updatePasswordHash(conn, adminId, newHash)
            deleteOtherSessions(conn, adminId, keepToken)
        }
        return ChangePasswordResult.Success
    }

    /**
     * 删除已过期的 session 行。后台调度（PR 3 引入 AlertEngine 的 ticker
     * 里顺手调）调用，防止表无限膨胀。
     */
    suspend fun reapExpiredSessions(): Int = db.withConnection { conn ->
        val now = clock()
        conn.prepareStatement("DELETE FROM admin_sessions WHERE expires_at < ?").use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }
    }

    enum class ChangePasswordResult {
        Success, WrongOldPassword, AdminNotFound
    }

    // === 内部 SQL helpers（保持 SQL 字符串集中，方便 review）===

    private fun countAdmins(conn: Connection): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM admin_users").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun insertAdmin(
        conn: Connection,
        username: String,
        passwordHash: String,
        role: AdminRole,
        createdAt: Long,
    ): Long = conn.prepareStatement(
        """
        INSERT INTO admin_users (username, password_hash, role, is_active, created_at, last_login_at)
        VALUES (?, ?, ?, 1, ?, NULL)
        """.trimIndent(),
        java.sql.Statement.RETURN_GENERATED_KEYS,
    ).use { ps ->
        ps.setString(1, username)
        ps.setString(2, passwordHash)
        ps.setString(3, role.name)
        ps.setLong(4, createdAt)
        ps.executeUpdate()
        ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
    }

    private fun findActiveByUsername(conn: Connection, username: String): AdminUser? =
        conn.prepareStatement(
            "SELECT id, username, password_hash, role, is_active, created_at, last_login_at " +
                "FROM admin_users WHERE username = ? AND is_active = 1"
        ).use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toAdminUser() else null
            }
        }

    private fun findAdminById(conn: Connection, id: Long): AdminUser? =
        conn.prepareStatement(
            "SELECT id, username, password_hash, role, is_active, created_at, last_login_at " +
                "FROM admin_users WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toAdminUser() else null
            }
        }

    private fun insertSession(
        conn: Connection,
        token: String,
        adminId: Long,
        createdAt: Long,
        expiresAt: Long,
        userAgent: String?,
        ip: String?,
    ) {
        conn.prepareStatement(
            "INSERT INTO admin_sessions (token, admin_id, created_at, expires_at, last_used_at, " +
                "user_agent, ip) VALUES (?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, token)
            ps.setLong(2, adminId)
            ps.setLong(3, createdAt)
            ps.setLong(4, expiresAt)
            ps.setLong(5, createdAt)
            if (userAgent != null) ps.setString(6, userAgent) else ps.setNull(6, java.sql.Types.VARCHAR)
            if (ip != null) ps.setString(7, ip) else ps.setNull(7, java.sql.Types.VARCHAR)
            ps.executeUpdate()
        }
    }

    private fun findSession(conn: Connection, token: String): AdminSession? =
        conn.prepareStatement(
            "SELECT token, admin_id, created_at, expires_at, last_used_at, user_agent, ip " +
                "FROM admin_sessions WHERE token = ?"
        ).use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs ->
                if (rs.next()) AdminSession(
                    token = rs.getString(1),
                    adminId = rs.getLong(2),
                    createdAt = rs.getLong(3),
                    expiresAt = rs.getLong(4),
                    lastUsedAt = rs.getLong(5),
                    userAgent = rs.getString(6),
                    ip = rs.getString(7),
                ) else null
            }
        }

    private fun deleteSession(conn: Connection, token: String) {
        conn.prepareStatement("DELETE FROM admin_sessions WHERE token = ?").use { ps ->
            ps.setString(1, token)
            ps.executeUpdate()
        }
    }

    private fun updateSessionExpiry(conn: Connection, token: String, expiresAt: Long, lastUsedAt: Long) {
        conn.prepareStatement(
            "UPDATE admin_sessions SET expires_at = ?, last_used_at = ? WHERE token = ?"
        ).use { ps ->
            ps.setLong(1, expiresAt)
            ps.setLong(2, lastUsedAt)
            ps.setString(3, token)
            ps.executeUpdate()
        }
    }

    private fun updateLastLoginAt(conn: Connection, adminId: Long, ts: Long) {
        conn.prepareStatement("UPDATE admin_users SET last_login_at = ? WHERE id = ?").use { ps ->
            ps.setLong(1, ts)
            ps.setLong(2, adminId)
            ps.executeUpdate()
        }
    }

    private fun updatePasswordHash(conn: Connection, adminId: Long, newHash: String) {
        conn.prepareStatement("UPDATE admin_users SET password_hash = ? WHERE id = ?").use { ps ->
            ps.setString(1, newHash)
            ps.setLong(2, adminId)
            ps.executeUpdate()
        }
    }

    private fun deleteOtherSessions(conn: Connection, adminId: Long, keepToken: String?) {
        if (keepToken != null) {
            conn.prepareStatement("DELETE FROM admin_sessions WHERE admin_id = ? AND token <> ?").use { ps ->
                ps.setLong(1, adminId)
                ps.setString(2, keepToken)
                ps.executeUpdate()
            }
        } else {
            conn.prepareStatement("DELETE FROM admin_sessions WHERE admin_id = ?").use { ps ->
                ps.setLong(1, adminId)
                ps.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.toAdminUser(): AdminUser = AdminUser(
        id = getLong(1),
        username = getString(2),
        passwordHash = getString(3),
        role = AdminRole.fromString(getString(4)) ?: AdminRole.OPS_ADMIN,
        isActive = getInt(5) != 0,
        createdAt = getLong(6),
        lastLoginAt = getLong(7).takeIf { !wasNull() },
    )

    companion object {
        const val MIN_PASSWORD_LEN = 8
        private const val USER_AGENT_MAX_LEN = 255
        private const val IP_MAX_LEN = 64
        private const val TOKEN_BYTES = 32

        // 用一次 bcrypt 当 timing 占位（用户不存在时也跑一次，免 timing 攻击）
        // 哈希内容无所谓，反正不会比中。cost 12 与生产配置一致。
        // pr-reviewer PR #61 P2 #9：改 by lazy，避免类首次加载就消耗 250 ms。
        // 等真有第一次"用户不存在"的 login 才付这次 cost；之后所有调用共享同一字符串
        private val DUMMY_HASH_FOR_TIMING: String by lazy { BCrypt.hashpw("xxxxx", BCrypt.gensalt(12)) }

        private val random = SecureRandom()
        private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

        fun hashPassword(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt(12))

        fun generateToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return urlEncoder.encodeToString(bytes)
        }
    }
}
