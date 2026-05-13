package com.communicationcard.server.admin

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminAuthServiceTest {

    private lateinit var db: AdminDb
    private lateinit var service: AdminAuthService
    private var nowMs: Long = 1_700_000_000_000L

    @BeforeTest
    fun setup() {
        db = AdminDb(AdminDb.IN_MEMORY)
        // 用可控时钟，方便测试过期 + 滚动续期
        service = AdminAuthService(db, sessionTtlSeconds = 3600, clock = { nowMs })
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `bootstrap inserts initial admin on empty db then no-op`() = runTest {
        db.runMigrations()
        assertTrue(service.bootstrapInitialAdmin("root", "secret-pw-12"))
        // 第二次：admin_users 已非空 → 返回 false 不再插入
        assertFalse(service.bootstrapInitialAdmin("root2", "another-pw-34"))

        // 第二次的用户名不应入库
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM admin_users").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next(); assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `bootstrap rejects weak initial password`() = runTest {
        db.runMigrations()
        val ex = runCatching { service.bootstrapInitialAdmin("root", "short") }.exceptionOrNull()
        assertNotNull(ex, "短密码应抛 IllegalArgumentException")
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `login with correct credentials returns non-null token`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")

        val token = service.login("root", "correct-horse-battery-staple", "ua", "ip")
        assertNotNull(token)
        assertTrue(token.length > 16, "token 应至少 16 字符")
    }

    @Test
    fun `login with wrong password returns null`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")

        val token = service.login("root", "WRONG", "ua", "ip")
        assertNull(token)
    }

    @Test
    fun `login with non-existent user returns null without crashing`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")

        val token = service.login("ghost-user", "anything-here", "ua", "ip")
        assertNull(token)
    }

    @Test
    fun `validate returns user for fresh token`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val token = service.login("root", "correct-horse-battery-staple", "ua", "ip")
        assertNotNull(token)

        val user = service.validate(token)
        assertNotNull(user)
        assertEquals("root", user.username)
        assertEquals(AdminRole.SUPER_ADMIN, user.role)
    }

    @Test
    fun `validate returns null for blank or unknown token`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")

        assertNull(service.validate(null))
        assertNull(service.validate(""))
        assertNull(service.validate("not-a-real-token"))
    }

    @Test
    fun `validate rejects expired token and deletes it`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val token = service.login("root", "correct-horse-battery-staple", "ua", "ip")
        assertNotNull(token)

        // 推进时钟超过 ttl
        nowMs += 3600_000L + 1

        assertNull(service.validate(token), "expired token should yield null")

        // 表里也应删了
        val count = db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM admin_sessions WHERE token = ?").use { ps ->
                ps.setString(1, token); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(0, count)
    }

    @Test
    fun `validate rolls expiry on each call`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val token = service.login("root", "correct-horse-battery-staple", "ua", "ip")
        assertNotNull(token)

        val firstExpires = readExpires(token)

        // 推进 30 分钟（半 ttl），再 validate
        nowMs += 1800_000L
        assertNotNull(service.validate(token))
        val secondExpires = readExpires(token)

        assertTrue(secondExpires > firstExpires, "expires_at 应该向后滚")
    }

    @Test
    fun `logout deletes session`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val token = service.login("root", "correct-horse-battery-staple", "ua", "ip")
        assertNotNull(token)

        service.logout(token)

        assertNull(service.validate(token))
    }

    @Test
    fun `changePassword with correct old password succeeds and invalidates other sessions`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")

        val token1 = service.login("root", "correct-horse-battery-staple", "ua1", "ip1")!!
        val token2 = service.login("root", "correct-horse-battery-staple", "ua2", "ip2")!!

        val userId = service.validate(token1)!!.id
        val result = service.changePassword(
            adminId = userId,
            keepToken = token1,
            oldPassword = "correct-horse-battery-staple",
            newPassword = "new-pw-fresh-and-clean",
        )
        assertEquals(AdminAuthService.ChangePasswordResult.Success, result)

        // token1（keep）仍可用；token2 被作废
        assertNotNull(service.validate(token1))
        assertNull(service.validate(token2))

        // 旧密码不能再登录
        assertNull(service.login("root", "correct-horse-battery-staple", null, null))
        // 新密码可以
        assertNotNull(service.login("root", "new-pw-fresh-and-clean", null, null))
    }

    @Test
    fun `changePassword rejects wrong old password`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val token = service.login("root", "correct-horse-battery-staple", "ua", "ip")!!
        val userId = service.validate(token)!!.id

        val result = service.changePassword(
            adminId = userId,
            keepToken = token,
            oldPassword = "WRONG-OLD",
            newPassword = "new-strong-pw-12",
        )
        assertEquals(AdminAuthService.ChangePasswordResult.WrongOldPassword, result)

        // 旧密码仍可登录
        assertNotNull(service.login("root", "correct-horse-battery-staple", null, null))
    }

    @Test
    fun `reapExpiredSessions removes only expired rows`() = runTest {
        db.runMigrations()
        service.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val tokenA = service.login("root", "correct-horse-battery-staple", null, null)!!

        // 推进 ttl + 1 → tokenA 过期
        nowMs += 3600_000L + 1
        // 再发一个新 token（用新时钟 → expires_at = now + ttl，未来）
        val tokenB = service.login("root", "correct-horse-battery-staple", null, null)!!

        val reaped = service.reapExpiredSessions()
        assertEquals(1, reaped, "应只清理 tokenA 那一行")

        // tokenA 已不在
        val countA = db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM admin_sessions WHERE token = ?").use { ps ->
                ps.setString(1, tokenA); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(0, countA)
        // tokenB 仍在
        assertNotNull(service.validate(tokenB))
    }

    private suspend fun readExpires(token: String): Long = db.withConnection { conn ->
        conn.prepareStatement("SELECT expires_at FROM admin_sessions WHERE token = ?").use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}
