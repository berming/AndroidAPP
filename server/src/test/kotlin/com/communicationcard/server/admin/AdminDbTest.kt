package com.communicationcard.server.admin

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminDbTest {

    private lateinit var db: AdminDb

    @BeforeTest
    fun setup() {
        db = AdminDb(AdminDb.IN_MEMORY)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `runMigrations is idempotent`() = runTest {
        db.runMigrations()
        db.runMigrations()  // 第二次跑不抛
        db.runMigrations()
    }

    @Test
    fun `admin_users table accepts insert and query`() = runTest {
        db.runMigrations()
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_users (username, password_hash, role, is_active, created_at) " +
                    "VALUES (?, ?, ?, 1, ?)"
            ).use { ps ->
                ps.setString(1, "root")
                ps.setString(2, "fakehash")
                ps.setString(3, "SUPER_ADMIN")
                ps.setLong(4, 1000L)
                ps.executeUpdate()
            }

            conn.prepareStatement("SELECT username, role FROM admin_users WHERE username = ?").use { ps ->
                ps.setString(1, "root")
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "should find inserted row")
                    assertEquals("root", rs.getString(1))
                    assertEquals("SUPER_ADMIN", rs.getString(2))
                }
            }
        }
    }

    @Test
    fun `admin_sessions table accepts insert and query`() = runTest {
        db.runMigrations()
        db.withConnection { conn ->
            // 先插一个 admin 以满足外键
            conn.prepareStatement(
                "INSERT INTO admin_users (id, username, password_hash, role, is_active, created_at) " +
                    "VALUES (1, 'root', 'fakehash', 'SUPER_ADMIN', 1, 1000)"
            ).use { it.executeUpdate() }

            conn.prepareStatement(
                "INSERT INTO admin_sessions (token, admin_id, created_at, expires_at, last_used_at, " +
                    "user_agent, ip) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, "tok-abc")
                ps.setLong(2, 1L)
                ps.setLong(3, 1000L)
                ps.setLong(4, 5000L)
                ps.setLong(5, 1000L)
                ps.setString(6, "test-agent")
                ps.setString(7, "127.0.0.1")
                ps.executeUpdate()
            }

            conn.prepareStatement("SELECT admin_id FROM admin_sessions WHERE token = ?").use { ps ->
                ps.setString(1, "tok-abc")
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1L, rs.getLong(1))
                }
            }
        }
    }

    @Test
    fun `foreign_keys cascade deletes sessions when admin is deleted`() = runTest {
        db.runMigrations()
        db.withConnection { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO admin_users (id, username, password_hash, role, is_active, created_at) " +
                        "VALUES (42, 'r', 'h', 'OPS_ADMIN', 1, 1)"
                )
                it.executeUpdate(
                    "INSERT INTO admin_sessions (token, admin_id, created_at, expires_at, last_used_at) " +
                        "VALUES ('t', 42, 1, 2, 1)"
                )
                it.executeUpdate("DELETE FROM admin_users WHERE id = 42")
            }
            conn.prepareStatement("SELECT COUNT(*) FROM admin_sessions WHERE admin_id = 42").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "FK CASCADE should remove orphan session")
                }
            }
        }
    }

    @Test
    fun `tx rolls back on exception`() = runTest {
        db.runMigrations()
        try {
            db.tx { conn ->
                conn.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO admin_users (id, username, password_hash, role, is_active, created_at) " +
                            "VALUES (1, 'will-be-rolled-back', 'h', 'OPS_ADMIN', 1, 1)"
                    )
                }
                error("force rollback")
            }
        } catch (_: IllegalStateException) {
            // expected
        }

        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM admin_users").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "tx rollback should leave table empty")
                }
            }
        }
        // 确保 autoCommit 恢复成 true（rollback 路径里 finally 也要还原）
        val ok = db.withConnection { conn -> conn.autoCommit }
        assertEquals(true, ok)
        assertNotNull(db)  // 防 unused warning
    }
}
