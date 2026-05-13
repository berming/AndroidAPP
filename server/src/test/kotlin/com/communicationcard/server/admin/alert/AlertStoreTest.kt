package com.communicationcard.server.admin.alert

import com.communicationcard.server.admin.AdminAuthService
import com.communicationcard.server.admin.AdminDb
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertStoreTest {

    private lateinit var db: AdminDb
    private lateinit var store: AlertStore
    private var adminId: Long = -1

    @BeforeTest
    fun setup() = runTest {
        db = AdminDb(AdminDb.IN_MEMORY)
        db.runMigrations()
        store = AlertStore(db)
        // 给一个 admin 让 ack 测试能 INSERT WITH FK 满足
        val auth = AdminAuthService(db, sessionTtlSeconds = 3600)
        auth.bootstrapInitialAdmin("root", "correct-horse-battery-staple")
        adminId = db.withConnection { conn ->
            conn.prepareStatement("SELECT id FROM admin_users WHERE username = 'root'").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insertIfNotCoolingDown writes when no recent same-rule row`() = runTest {
        val id = store.insertIfNotCoolingDown(
            candidate = candidate("ROOM_STUCK", roomId = "r1"),
            cooldownMs = 60_000,
            nowMs = 1000,
        )
        assertNotNull(id)
        assertTrue(id > 0)
        assertEquals(1, store.countUnacked())
    }

    @Test
    fun `insertIfNotCoolingDown skips when same rule + room recently inserted`() = runTest {
        val first = store.insertIfNotCoolingDown(
            candidate = candidate("ROOM_STUCK", roomId = "r1"),
            cooldownMs = 60_000,
            nowMs = 1000,
        )
        assertNotNull(first)

        val second = store.insertIfNotCoolingDown(
            candidate = candidate("ROOM_STUCK", roomId = "r1", message = "second message"),
            cooldownMs = 60_000,
            nowMs = 1500,  // 仍在 60s cooldown 内
        )
        assertNull(second, "cooldown 内同 rule+room 不应入库")

        assertEquals(1, store.countUnacked())
    }

    @Test
    fun `insertIfNotCoolingDown emits second row after cooldown expires`() = runTest {
        store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r1"), 60_000, 1000)
        // 推进时间超过 cooldown
        val id2 = store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r1"), 60_000, 1000 + 60_001)
        assertNotNull(id2)
        assertEquals(2, store.countUnacked())
    }

    @Test
    fun `insertIfNotCoolingDown does not collide across different rooms`() = runTest {
        val a = store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r1"), 60_000, 1000)
        val b = store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r2"), 60_000, 1000)
        assertNotNull(a); assertNotNull(b)
        assertEquals(2, store.countUnacked())
    }

    @Test
    fun `insertIfNotCoolingDown treats null roomId as its own cooldown bucket`() = runTest {
        // JVM_HEAP_HIGH 没有 roomId
        val first = store.insertIfNotCoolingDown(candidate("JVM_HEAP_HIGH"), 60_000, 1000)
        val second = store.insertIfNotCoolingDown(candidate("JVM_HEAP_HIGH"), 60_000, 1500)
        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun `ack marks alert acked and excludes from listUnacked`() = runTest {
        store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r1"), 60_000, 1000)
        val unack1 = store.listUnacked(10)
        assertEquals(1, unack1.size)

        val ok = store.ack(unack1[0].id, ackedByAdminId = adminId, nowMs = 2000)
        assertTrue(ok)

        assertEquals(0, store.listUnacked(10).size)
        // listRecent 仍能看到
        assertEquals(1, store.listRecent(10).size)
    }

    @Test
    fun `ack twice on same alert returns false the second time`() = runTest {
        store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r1"), 60_000, 1000)
        val id = store.listUnacked(10)[0].id
        assertTrue(store.ack(id, adminId, 2000))
        assertFalse(store.ack(id, adminId, 3000))
    }

    @Test
    fun `listUnacked returns most recent first`() = runTest {
        store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r1", message = "old"), 60_000, 1000)
        store.insertIfNotCoolingDown(candidate("ROOM_STUCK", roomId = "r2", message = "newer"), 60_000, 5000)
        val list = store.listUnacked(10)
        assertEquals(2, list.size)
        assertEquals("newer", list[0].message)
    }

    @Test
    fun `payload survives JSON roundtrip`() = runTest {
        store.insertIfNotCoolingDown(
            candidate("ROOM_STUCK", roomId = "r1", payload = mapOf("phase" to "PLAYING", "ageMs" to "300000")),
            60_000, 1000,
        )
        val dto = store.listUnacked(1).first()
        assertNotNull(dto.payload)
        assertEquals("PLAYING", dto.payload?.get("phase"))
        assertEquals("300000", dto.payload?.get("ageMs"))
    }

    private fun candidate(
        rule: String,
        roomId: String? = null,
        message: String = "test alert",
        payload: Map<String, String> = emptyMap(),
    ): AlertCandidate = AlertCandidate(
        rule = rule,
        severity = "WARN",
        roomId = roomId,
        message = message,
        payload = payload,
    )
}
