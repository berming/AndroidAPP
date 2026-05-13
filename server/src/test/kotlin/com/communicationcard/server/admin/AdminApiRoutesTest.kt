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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminApiRoutesTest {

    /**
     * 构造一个 admin 上下文 + bootstrap 一个 SUPER_ADMIN 账号；返回
     * (ctx, "正确密码") 元组方便测试登录拿 cookie。
     */
    private fun makeContext(): AdminContext {
        val db = AdminDb(AdminDb.IN_MEMORY)
        val authService = AdminAuthService(db, sessionTtlSeconds = 3600)
        val historyStore = GameHistoryStore(db)
        runBlocking {
            db.runMigrations()
            authService.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
            historyStore.start()
        }
        val roomManager = ServerRoomManager()
        val gameManager = ServerGameManager(roomManager)
        val serverCtx = ServerContext(
            roomManager = roomManager,
            gameManager = gameManager,
            sessions = ConcurrentHashMap<String, GameSession>(),
            startedAtEpochMs = System.currentTimeMillis() - 60_000,
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
        )
    }

    @Test
    fun `GET overview without cookie returns 401`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/overview").status)
    }

    @Test
    fun `GET overview with valid cookie returns 200 and uptime fields`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = login(httpClient)

        val resp = httpClient.get("/admin/api/overview") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val dto: OverviewDto = resp.body()
        assertTrue(dto.uptimeSeconds >= 0)
        assertEquals(0, dto.totalRooms)
        assertTrue(dto.jvmHeapMaxMb > 0)
        // PR 0 启动元数据：startedAtEpochMs 应该可序列化成 ISO-8601
        assertTrue(dto.serverStartedAt.contains("T"))
    }

    @Test
    fun `GET rooms returns empty array when no rooms`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = login(httpClient)

        val resp = httpClient.get("/admin/api/rooms") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val list: List<RoomSummaryDto> = resp.body()
        assertEquals(0, list.size)
    }

    @Test
    fun `GET room of unknown id returns 404`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = login(httpClient)

        val resp = httpClient.get("/admin/api/rooms/ghost-id") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET games with empty store returns empty list`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = login(httpClient)

        val resp = httpClient.get("/admin/api/games?limit=10") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val list: List<GameSummaryDto> = resp.body()
        assertEquals(0, list.size)
    }

    @Test
    fun `GET games-id with non-numeric id returns 400`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = login(httpClient)

        val resp = httpClient.get("/admin/api/games/not-a-number") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `GET games-id 404 when game absent`() = testApplication {
        val ctx = makeContext()
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        val token = login(httpClient)

        val resp = httpClient.get("/admin/api/games/99999") { cookie(ADMIN_COOKIE_NAME, token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `OPS_ADMIN role still has MONITOR_READ + GAME_HISTORY_READ on PR 2 endpoints`() = testApplication {
        // 直接插入一个 OPS_ADMIN 账号验证权限矩阵
        val ctx = makeContext()
        runBlocking {
            ctx.db.withConnection { conn ->
                conn.prepareStatement(
                    "INSERT INTO admin_users (username, password_hash, role, is_active, created_at) " +
                        "VALUES (?, ?, 'OPS_ADMIN', 1, ?)"
                ).use { ps ->
                    ps.setString(1, "ops")
                    ps.setString(2, AdminAuthService.hashPassword("ops-password-9012"))
                    ps.setLong(3, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
        }
        application {
            install(ContentNegotiation) { json() }
            routing {
                adminAuthRoutes(ctx)
                adminApiRoutes(ctx)
            }
        }
        val httpClient = createClient { install(ClientContentNegotiation) { json() } }
        // 用 ops 登录
        val loginResp = httpClient.post("/admin-auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("ops", "ops-password-9012"))
        }
        val opsToken = loginResp.headers["Set-Cookie"]!!.substringAfter("$ADMIN_COOKIE_NAME=").substringBefore(";")

        assertEquals(
            HttpStatusCode.OK,
            httpClient.get("/admin/api/overview") { cookie(ADMIN_COOKIE_NAME, opsToken) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            httpClient.get("/admin/api/games?limit=1") { cookie(ADMIN_COOKIE_NAME, opsToken) }.status,
        )
    }

    private suspend fun login(client: io.ktor.client.HttpClient): String {
        val resp = client.post("/admin-auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root", "correct-horse-battery-staple"))
        }
        return resp.headers["Set-Cookie"]!!.substringAfter("$ADMIN_COOKIE_NAME=").substringBefore(";")
    }
}
