package com.communicationcard.server.admin

import com.communicationcard.server.GameSession
import com.communicationcard.server.ServerContext
import com.communicationcard.server.ServerGameManager
import com.communicationcard.server.ServerRoomManager
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeCookieValue
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminAuthRoutesTest {

    private fun makeContext(): AdminContext {
        val db = AdminDb(AdminDb.IN_MEMORY)
        val authService = AdminAuthService(db, sessionTtlSeconds = 3600)
        val historyStore = GameHistoryStore(db)
        runBlocking {
            db.runMigrations()
            authService.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        }
        val roomManager = ServerRoomManager()
        val gameManager = ServerGameManager(roomManager)
        val serverCtx = ServerContext(
            roomManager = roomManager,
            gameManager = gameManager,
            sessions = ConcurrentHashMap<String, GameSession>(),
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
            // cookieSecure=false for tests（ktor-server-test-host 不模拟 https）
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

    @Test
    fun `POST login with wrong password returns 401`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        val resp = client.post("/admin-auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root", "WRONG"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST login with correct password sets cookie and returns user`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        val resp = client.post("/admin-auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root", "correct-horse-battery-staple"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        val setCookie = resp.headers["Set-Cookie"]
        assertNotNull(setCookie, "应设 Set-Cookie 头")
        assertTrue(setCookie.contains(ADMIN_COOKIE_NAME), "cookie 名应是 $ADMIN_COOKIE_NAME")
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true), "cookie 必须 HttpOnly")
        assertTrue(setCookie.contains("SameSite=Lax", ignoreCase = true), "cookie 必须 SameSite=Lax")

        val body: LoginResponse = resp.body()
        assertEquals("root", body.user.username)
        assertEquals("SUPER_ADMIN", body.user.role)
        assertTrue(body.user.permissions.contains("MONITOR_READ"))
    }

    @Test
    fun `GET me without cookie returns 401`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }

        val resp = client.get("/admin-auth/me")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `login then me returns user permissions`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }

        val token = loginAndExtractToken(httpClient)

        val meResp = httpClient.get("/admin-auth/me") {
            cookie(ADMIN_COOKIE_NAME, token)
        }
        assertEquals(HttpStatusCode.OK, meResp.status)
        val user: AdminUserDto = meResp.body()
        assertEquals("root", user.username)
        assertEquals("SUPER_ADMIN", user.role)
        // SUPER_ADMIN 拿全部权限
        AdminPermission.entries.forEach { assertTrue(user.permissions.contains(it.name)) }
    }

    @Test
    fun `logout invalidates the cookie`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }

        val token = loginAndExtractToken(httpClient)

        // 先验证可用
        var meResp = httpClient.get("/admin-auth/me") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.OK, meResp.status)

        // 登出
        val logoutResp = httpClient.post("/admin-auth/logout") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.NoContent, logoutResp.status)

        // 旧 token 不再可用
        meResp = httpClient.get("/admin-auth/me") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.Unauthorized, meResp.status)
    }

    @Test
    fun `change-password with correct old password returns 204 and new password works`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }

        val token = loginAndExtractToken(httpClient)

        val resp = httpClient.post("/admin-auth/change-password") {
            cookie(ADMIN_COOKIE_NAME, token)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(
                oldPassword = "correct-horse-battery-staple",
                newPassword = "new-pw-after-change-12",
            ))
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)

        // 新密码可登录；旧密码不行
        assertEquals(
            HttpStatusCode.Unauthorized,
            httpClient.post("/admin-auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root", "correct-horse-battery-staple"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            httpClient.post("/admin-auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root", "new-pw-after-change-12"))
            }.status,
        )
    }

    @Test
    fun `change-password with wrong old password returns 401`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = loginAndExtractToken(httpClient)

        val resp = httpClient.post("/admin-auth/change-password") {
            cookie(ADMIN_COOKIE_NAME, token)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(
                oldPassword = "GARBAGE",
                newPassword = "new-pw-not-applied-12",
            ))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `change-password with too short new password returns 400`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = loginAndExtractToken(httpClient)

        val resp = httpClient.post("/admin-auth/change-password") {
            cookie(ADMIN_COOKIE_NAME, token)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(
                oldPassword = "correct-horse-battery-staple",
                newPassword = "short",
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `session cookie URI_ENCODING never throws for realistic base64url tokens`() {
        // Regression guard: CookieEncoding.RAW in Ktor 2.3.6 has stricter character
        // validation that threw IAE for some cookie values, causing admin login to return
        // HTTP 500. URI_ENCODING must not throw and must round-trip base64url tokens
        // unchanged (A-Za-z0-9-_ are all URL-safe, so no percent-encoding happens).
        repeat(50) {
            val token = AdminAuthService.generateToken()
            val encoded = encodeCookieValue(token, CookieEncoding.URI_ENCODING)
            assertEquals(token, encoded,
                "URI_ENCODING must preserve base64url token unchanged: $token")
        }
    }

    @Test
    fun `login Set-Cookie token is valid base64url and authenticates subsequent requests`() = testApplication {
        // Regression: admin login was returning HTTP 500 due to IAE during cookie encoding.
        // Verifies: (a) login succeeds, (b) Set-Cookie token is valid base64url format,
        // (c) token round-trips and authenticates GET /admin-auth/me.
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing { adminAuthRoutes(ctx) }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }

        val loginResp = httpClient.post("/admin-auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root", "correct-horse-battery-staple"))
        }
        // (a) Must not 500 — a cookie encoding IAE would appear here
        assertEquals(HttpStatusCode.OK, loginResp.status,
            "login must not throw during cookie encoding (was HTTP 500 with IAE before fix)")

        val setCookieHeader = checkNotNull(loginResp.headers["Set-Cookie"]) { "Set-Cookie must be present" }
        val rawToken = setCookieHeader.substringAfter("$ADMIN_COOKIE_NAME=").substringBefore(";")

        // (b) Token must be exactly 43-char base64url (32 bytes, no padding), A-Za-z0-9-_ only
        assertEquals(43, rawToken.length,
            "base64url(32 bytes) without padding must be 43 chars, got: '$rawToken'")
        assertTrue(rawToken.all { it.isLetterOrDigit() || it == '-' || it == '_' },
            "token must only contain base64url characters: '$rawToken'")

        // (c) Token extracted from Set-Cookie must authenticate the next request
        val meResp = httpClient.get("/admin-auth/me") {
            cookie(ADMIN_COOKIE_NAME, rawToken)
        }
        assertEquals(HttpStatusCode.OK, meResp.status,
            "token from Set-Cookie header must work for authenticated requests")
    }

    private suspend fun loginAndExtractToken(client: io.ktor.client.HttpClient): String {
        val resp: HttpResponse = client.post("/admin-auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root", "correct-horse-battery-staple"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val setCookie = resp.headers["Set-Cookie"]!!
        return setCookie.substringAfter("$ADMIN_COOKIE_NAME=").substringBefore(";")
    }
}
