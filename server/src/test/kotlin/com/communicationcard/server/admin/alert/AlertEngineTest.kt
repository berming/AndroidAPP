package com.communicationcard.server.admin.alert

import com.communicationcard.server.GameSession
import com.communicationcard.server.ServerContext
import com.communicationcard.server.ServerGameManager
import com.communicationcard.server.ServerRoomManager
import com.communicationcard.server.admin.AdminAuthService
import com.communicationcard.server.admin.AdminDb
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertEngineTest {

    private lateinit var db: AdminDb
    private lateinit var store: AlertStore
    private lateinit var authService: AdminAuthService
    private lateinit var serverCtx: ServerContext

    @BeforeTest
    fun setup() = runTest {
        db = AdminDb(AdminDb.IN_MEMORY)
        db.runMigrations()
        store = AlertStore(db)
        authService = AdminAuthService(db, sessionTtlSeconds = 3600)
        authService.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        val rm = ServerRoomManager()
        val gm = ServerGameManager(rm)
        serverCtx = ServerContext(
            roomManager = rm,
            gameManager = gm,
            sessions = ConcurrentHashMap<String, GameSession>(),
            startedAtEpochMs = System.currentTimeMillis(),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `runOnce with empty rule list inserts nothing`() = runTest {
        val engine = AlertEngine(
            serverCtx = serverCtx,
            store = store,
            authService = authService,
            rules = emptyList(),
        )
        engine.runOnce()
        assertEquals(0, store.countUnacked())
    }

    @Test
    fun `runOnce with always-firing rule inserts on first tick and skips on second within cooldown`() = runTest {
        var ticks = 0
        val rule = object : AlertRule {
            override val name = "TEST_FIRE"
            override val severity = "INFO"
            override val cooldownMs = 60_000L
            override fun evaluate(ctx: ServerContext, nowMs: Long): List<AlertCandidate> {
                ticks++
                return listOf(AlertCandidate(name, severity, message = "fire-$ticks"))
            }
        }
        // 用 1 元素 LongArray 包装可变时钟（lambda 闭包能读，外面能改）。
        // 关键避坑：变量名不能叫 `clock` —— 它会与 AlertEngine 构造的命名参数
        // `clock = ...` 撞名，Kotlin 1.9.24 在 lambda 闭包内解析 set operator 时
        // 报 "No set method providing array access"
        val clockMs = longArrayOf(1_000L)
        val engine = AlertEngine(
            serverCtx = serverCtx,
            store = store,
            authService = authService,
            rules = listOf(rule),
            clock = { clockMs[0] },
        )

        engine.runOnce()
        engine.runOnce()
        engine.runOnce()

        assertEquals(1, store.countUnacked(), "cooldown 内重复 tick 应只入一条")
        // 推进时钟跳过 cooldown（显式 set 而非 += 防 1.9.24 quirks）
        clockMs[0] = clockMs[0] + 60_001
        engine.runOnce()
        assertEquals(2, store.countUnacked())
    }

    @Test
    fun `runOnce shields one rule from another's exception`() = runTest {
        val good = object : AlertRule {
            override val name = "GOOD"; override val severity = "INFO"; override val cooldownMs = 1_000L
            override fun evaluate(ctx: ServerContext, nowMs: Long) =
                listOf(AlertCandidate(name, severity, message = "ok"))
        }
        val bad = object : AlertRule {
            override val name = "BAD"; override val severity = "WARN"; override val cooldownMs = 1_000L
            override fun evaluate(ctx: ServerContext, nowMs: Long): List<AlertCandidate> =
                throw IllegalStateException("boom")
        }
        val engine = AlertEngine(
            serverCtx = serverCtx,
            store = store,
            authService = authService,
            rules = listOf(bad, good),
        )
        engine.runOnce()
        // GOOD 仍然成功入库；BAD 异常被吞
        assertEquals(1, store.countUnacked())
        assertEquals("GOOD", store.listUnacked(1).first().rule)
    }

    @Test
    fun `alertFlow emits AlertDto when rule fires successfully`() = runTest {
        // PR 5a：SSE 推送通道。AlertEngine.runOnce 内 store.insertIfNotCoolingDown
        // 返回非 null id → tryEmit(dto) 到 alertFlow；admin UI 的 SSE 端点订阅
        val rule = object : AlertRule {
            override val name = "TEST_FIRE"
            override val severity = "INFO"
            override val cooldownMs = 60_000L
            override fun evaluate(ctx: ServerContext, nowMs: Long) =
                listOf(AlertCandidate(name, severity, message = "sse hello"))
        }
        val engine = AlertEngine(
            serverCtx = serverCtx,
            store = store,
            authService = authService,
            rules = listOf(rule),
        )

        // 先把 collect 协程跑起来（注册订阅），然后才 emit；
        // SharedFlow replay=0 + extraBuffer=64：subscribe 之后的 emit 收得到
        val received = async { engine.alertFlow.first() }
        yield()

        engine.runOnce()

        val dto = received.await()
        assertEquals("TEST_FIRE", dto.rule)
        assertEquals("INFO", dto.severity)
        assertEquals("sse hello", dto.message)
        assertTrue(dto.id > 0, "id 应是 SQLite AUTOINCREMENT 出来的正数")
    }

    @Test
    fun `runOnce reaps expired admin sessions`() = runTest {
        // 制造一个过期 session
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_sessions (token, admin_id, created_at, expires_at, last_used_at) " +
                    "VALUES ('expired-token', 1, 1, 2, 1)"
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "INSERT INTO admin_sessions (token, admin_id, created_at, expires_at, last_used_at) " +
                    "VALUES ('future-token', 1, 1, ${System.currentTimeMillis() + 100_000_000}, 1)"
            ).use { it.executeUpdate() }
        }
        val engine = AlertEngine(
            serverCtx = serverCtx, store = store, authService = authService, rules = emptyList(),
        )
        engine.runOnce()

        val remaining = db.withConnection { conn ->
            conn.prepareStatement("SELECT token FROM admin_sessions").use { ps ->
                val out = mutableListOf<String>()
                ps.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString(1)) }
                out
            }
        }
        assertTrue("expired-token" !in remaining)
        assertTrue("future-token" in remaining)
    }
}
