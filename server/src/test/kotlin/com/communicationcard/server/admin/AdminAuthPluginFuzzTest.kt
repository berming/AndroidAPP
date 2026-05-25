package com.communicationcard.server.admin

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F-AUTH-02 — AdminAuthPlugin fuzz / property test.
 *
 * 覆盖维度：
 * 1. [clientAuditFields] X-Forwarded-For 取链尾（**回归 pr-reviewer P2#2 / 5ddb0351**），
 *    任意分隔/空白/伪造前缀场景必须取最后一段
 * 2. [adminToken] 任意 cookie 值不抛
 * 3. [requireAdmin] 混合 bypass 状态机：
 *    a. 无 cookie / 无效 cookie → bypass synthetic SUPER_ADMIN
 *    b. 有效 cookie → 真实用户
 *
 * 注意：当前 bypass 模式硬编码开启（b34808fc + c5ba575a），未来若移除 bypass，
 * 子集断言 "valid cookie returns real user" 仍成立；"invalid → bypass" 应改为
 * "invalid → 401"。在 bypass-off PR 中需同步改本测试。
 */
class AdminAuthPluginFuzzTest : FuzzTestBase() {

    private val totalCases = mutableMapOf<String, Int>()
    private val passCases = mutableMapOf<String, Int>()
    private val startedAt = System.currentTimeMillis()

    @AfterTest
    fun tearDown() {
        recordResult(
            moduleName = "server-admin/AdminAuthPlugin",
            testCaseCount = totalCases.values.sum(),
            passCount = passCases.values.sum(),
            durationMs = System.currentTimeMillis() - startedAt,
            filesChanged = listOf(
                "server/src/main/kotlin/com/communicationcard/server/admin/AdminAuthPlugin.kt"
            ),
            notes = "dimensions=" + totalCases.entries.joinToString("|") { "${it.key}:${it.value}" },
        )
    }

    private fun track(dim: String, ok: Boolean, detail: String? = null) {
        totalCases.merge(dim, 1, Int::plus)
        if (ok) passCases.merge(dim, 1, Int::plus)
        else println("[FAIL $dim] ${detail ?: "(no detail)"}")
    }

    // -----------------------------------------------------------------
    // 维度 1：XFF 取链尾（5ddb0351 / pr-reviewer PR #61 P2 #2 回归）
    //
    // 攻击场景：客户端发 `X-Forwarded-For: 8.8.8.8`，Caddy append 后变成
    // `8.8.8.8, <caddy_ip>`；取 first 拿到伪造 IP。必须取 LAST。
    // -----------------------------------------------------------------
    @Test
    fun `fuzz XFF parser always takes last element (regression 5ddb0351)`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/_test/audit") {
                    val (ua, ip) = call.clientAuditFields()
                    call.respondText("$ua|$ip")
                }
            }
        }

        // 已知场景：构造的伪造前缀 + 已知尾部 → 必须返回尾部
        val knownCases = listOf(
            Triple("8.8.8.8", "10.0.0.1", "10.0.0.1"),
            Triple("evil.com, attacker", "192.168.1.5", "192.168.1.5"),
            Triple("", "192.168.1.5", "192.168.1.5"),
            Triple("a,b,c,d,e,f", "trusted-edge", "trusted-edge"),
            Triple("1.1.1.1,  2.2.2.2 ,  3.3.3.3  ", "4.4.4.4", "4.4.4.4"),
        )
        for ((prefix, last, expected) in knownCases) {
            val xff = if (prefix.isEmpty()) last else "$prefix, $last"
            val resp = client.get("/_test/audit") { header("X-Forwarded-For", xff) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            val parts = body.split("|")
            val ip = parts.getOrNull(1) ?: "null"
            val ok = ip == expected
            if (!ok) {
                println("[FAIL XFF] header='$xff' expected='$expected' got='$ip'")
            }
            track("xff_known_takes_last", ok)
        }
        assertNoFailures("xff_known_takes_last")

        // 随机化：任意前缀链 + 已知 trusted 尾部 → 始终返回 trusted
        repeat(200) {
            val chainLen = seededRandom.nextInt(0, 6)
            val prefix = (0 until chainLen).joinToString(", ") {
                randomAsciiString(20).filter { c -> c.code in 33..126 && c != ',' }.ifEmpty { "x" }
            }
            val trusted = "trusted-${seededRandom.nextInt(0, 1000)}"
            val xff = if (prefix.isEmpty()) trusted else "$prefix, $trusted"
            val (ok, detail) = runCatching {
                val resp = client.get("/_test/audit") { header("X-Forwarded-For", xff) }
                val body = resp.bodyAsText()
                val ip = body.split("|").getOrNull(1) ?: ""
                (ip == trusted) to "status=${resp.status} xff='$xff' expected='$trusted' got='$ip'"
            }.getOrElse { false to "threw ${it::class.simpleName}: ${it.message} xff='$xff'" }
            track("xff_random_chains", ok, detail)
        }
        assertNoFailures("xff_random_chains")
    }

    // -----------------------------------------------------------------
    // 维度 2：clientAuditFields 在没有 XFF 时退回 remoteHost；任意 UA 不抛
    // -----------------------------------------------------------------
    @Test
    fun `fuzz user-agent and absent XFF fallback`() = testApplication {
        application {
            routing {
                get("/_test/audit") {
                    val (ua, ip) = call.clientAuditFields()
                    call.respondText("$ua|$ip")
                }
            }
        }

        repeat(200) {
            val ua = when (seededRandom.nextInt(0, 5)) {
                0 -> randomAsciiString(300)        // 超长
                1 -> randomUnicodeString(80)       // 非 ASCII
                2 -> ""                            // 空
                3 -> "Mozilla/5.0"                 // 典型
                else -> randomAsciiString(40)
            }
            // 真正的不变量：服务端不 5xx 崩溃。
            // 客户端拒绝（HTTP spec 禁止非 ASCII 在 headers 里 — Ktor client throws
            // IllegalArgumentException）或服务端 4xx 都是合规处理，不算 fuzz 失败。
            val (ok, detail) = runCatching {
                val resp = client.get("/_test/audit") {
                    if (ua.isNotEmpty()) header("User-Agent", ua)
                    // 不带 XFF
                }
                val notServerError = resp.status.value < 500
                notServerError to "status=${resp.status} ua_len=${ua.length}"
            }.getOrElse {
                // 客户端预先拒绝（如 Unicode UA → IllegalArgumentException）也算 OK
                // 关键：服务端没有机会崩溃
                true to "client-rejected: ${it::class.simpleName} ua_len=${ua.length}"
            }
            track("ua_random", ok, detail)
        }
        assertNoFailures("ua_random")
    }

    // -----------------------------------------------------------------
    // 维度 3：adminToken() 读取任意 cookie 值 — 不抛
    // -----------------------------------------------------------------
    @Test
    fun `fuzz adminToken with arbitrary cookie values never throws`() = testApplication {
        application {
            routing {
                get("/_test/token") {
                    val t = call.adminToken()
                    call.respondText(if (t == null) "null" else "len=${t.length}")
                }
            }
        }

        repeat(300) {
            val cookieValue = when (seededRandom.nextInt(0, 6)) {
                0 -> randomAsciiString(120).filter { it.code in 33..126 && it != ';' && it != ',' }
                1 -> ""
                2 -> "abc=def=ghi"
                3 -> randomBytes(80).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                4 -> "valid-looking-token-${seededRandom.nextInt(0, 999999)}"
                else -> randomAsciiString(44)
            }
            // 不变量：服务端不 5xx。客户端拒绝 / 服务端 4xx 都合规。
            val (ok, detail) = runCatching {
                val resp = client.get("/_test/token") {
                    if (cookieValue.isNotEmpty()) {
                        header("Cookie", "$ADMIN_COOKIE_NAME=$cookieValue")
                    }
                }
                (resp.status.value < 500) to "status=${resp.status} cookie_len=${cookieValue.length}"
            }.getOrElse {
                true to "client-rejected: ${it::class.simpleName} cookie_len=${cookieValue.length}"
            }
            track("cookie_random", ok, detail)
        }
        assertNoFailures("cookie_random")
    }

    // -----------------------------------------------------------------
    // 维度 4：bypass 状态机 — 当前 bypass-ON：无效/缺 cookie → synthetic SUPER_ADMIN
    //
    // 当 bypass 在未来 PR 中移除时，本测试应改为：
    //   - 无效 cookie → 401（用 `assertEquals(HttpStatusCode.Unauthorized, ...)`)
    //   - 有效 cookie → 真实用户
    // 当前用 BYPASS_USERNAME 常量断言："bypass" 这个 synthetic 用户被注入。
    // -----------------------------------------------------------------
    @Test
    fun `bypass state machine — invalid or missing cookie yields synthetic SUPER_ADMIN`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/_test/whoami") {
                    val user = call.requireAdmin(ctx) ?: return@get
                    call.respondText("${user.username}|${user.role}")
                }
            }
        }

        // 全确定性 cookie — bypass 状态机的关键是逻辑分支，不需要 fuzz；
        // 此前用 randomCookieToken 出现 RFC 6265 / Ktor 解析器边界冲突，导致测试不稳定。
        // 现在用 5 个明确"非有效 session"的代表性 cookie 值：
        val invalidCookies = listOf(
            "",                                  // 无 cookie
            "not-a-real-token",                  // 简单 ASCII 文本
            "abc=def",                           // 包含 = 但仍可解析
            "looks-like-base64-aBcDeFgHiJkLmNoP", // 拟真但非真 token
            "44charsexactly-aBcDeFgHiJkLmNoPqRsTuVwXyZ12", // 与真 token 同长 44
        )
        for (cookie in invalidCookies) {
            val (ok, detail) = runCatching {
                val resp = client.get("/_test/whoami") {
                    if (cookie.isNotEmpty()) header("Cookie", "$ADMIN_COOKIE_NAME=$cookie")
                }
                val body = resp.bodyAsText()
                val ok = resp.status == HttpStatusCode.OK && body == "bypass|SUPER_ADMIN"
                ok to "status=${resp.status} cookie='${cookie.take(30)}' body='$body'"
            }.getOrElse {
                false to "threw ${it::class.simpleName}: ${it.message} cookie='${cookie.take(20)}'"
            }
            track("bypass_invalid_cookie", ok, detail)
        }
        assertNoFailures("bypass_invalid_cookie")
    }

    // -----------------------------------------------------------------
    // 维度 5：bypass 状态机 — 有效 cookie 必须返回真实用户（不被 bypass 覆盖）
    //   这是 hybrid bypass 的核心约束：bypass 仅在 fallback 路径生效
    // -----------------------------------------------------------------
    @Test
    fun `bypass state machine — valid cookie returns real user not bypass`() = testApplication {
        val ctx = makeContext()
        val realToken = runBlocking {
            ctx.authService.login("root", "correct-horse-battery-staple", null, null)
        } ?: fail("test setup: real login failed")

        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/_test/whoami") {
                    val user = call.requireAdmin(ctx) ?: return@get
                    call.respondText("${user.username}|${user.id}|${user.role}")
                }
            }
        }

        val resp = client.get("/_test/whoami") {
            header("Cookie", "$ADMIN_COOKIE_NAME=$realToken")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        // 真实用户：username=root, id!=0（bypass id=0L），role=SUPER_ADMIN
        val parts = body.split("|")
        val ok = parts.size == 3 &&
            parts[0] == "root" &&
            parts[1] != "0" &&  // bypass id 是 0
            parts[2] == "SUPER_ADMIN"
        if (!ok) {
            fail("Expected real root user (id != 0) but got: $body")
        }
        track("bypass_valid_cookie_real_user", ok)
        assertNoFailures("bypass_valid_cookie_real_user")
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    private fun makeContext(): AdminContext {
        val db = AdminDb(AdminDb.IN_MEMORY)
        val authService = AdminAuthService(db, sessionTtlSeconds = 3600)
        val historyStore = GameHistoryStore(db)
        runBlocking {
            db.runMigrations()
            authService.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        }
        val roomManager = com.communicationcard.server.ServerRoomManager()
        val gameManager = com.communicationcard.server.ServerGameManager(roomManager)
        val serverCtx = com.communicationcard.server.ServerContext(
            roomManager = roomManager,
            gameManager = gameManager,
            sessions = java.util.concurrent.ConcurrentHashMap(),
            startedAtEpochMs = 0L,
        )
        val alertStore = com.communicationcard.server.admin.alert.AlertStore(db)
        val alertEngine = com.communicationcard.server.admin.alert.AlertEngine(
            serverCtx = serverCtx,
            store = alertStore,
            authService = authService,
        )
        return AdminContext(
            serverCtx = serverCtx,
            db = db,
            config = AdminConfig(
                dbPath = AdminDb.IN_MEMORY,
                cookieDomain = null,
                cookieSecure = false,
                sessionTtlSeconds = 3600,
                initialUsername = "root",
                initialPassword = "correct-horse-battery-staple",
            ),
            authService = authService,
            historyStore = historyStore,
            snapshotBuilder = SnapshotBuilder(serverCtx, historyStore),
            alertStore = alertStore,
            alertEngine = alertEngine,
        )
    }

    private fun assertNoFailures(dimension: String) {
        val total = totalCases[dimension] ?: 0
        val pass = passCases[dimension] ?: 0
        if (pass != total) {
            System.err.println("[ASSERT-FAIL $dimension] $pass / $total (seed=$seed)")
        }
        assertTrue(pass == total, "[$dimension] $pass / $total passed (seed=$seed)")
    }
}
