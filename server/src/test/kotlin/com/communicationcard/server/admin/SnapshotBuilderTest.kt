package com.communicationcard.server.admin

import com.communicationcard.server.GameSession
import com.communicationcard.server.ServerContext
import com.communicationcard.server.ServerGameManager
import com.communicationcard.server.ServerRoomManager
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotBuilderTest {

    private lateinit var db: AdminDb
    private lateinit var historyStore: GameHistoryStore
    private lateinit var roomManager: ServerRoomManager
    private lateinit var gameManager: ServerGameManager
    private lateinit var sessions: ConcurrentHashMap<String, GameSession>
    private lateinit var builder: SnapshotBuilder

    @BeforeTest
    fun setup() = runTest {
        db = AdminDb(AdminDb.IN_MEMORY)
        db.runMigrations()
        historyStore = GameHistoryStore(db)
        historyStore.start()
        roomManager = ServerRoomManager()
        gameManager = ServerGameManager(roomManager)
        sessions = ConcurrentHashMap()
        val ctx = ServerContext(
            roomManager = roomManager,
            gameManager = gameManager,
            sessions = sessions,
            startedAtEpochMs = 1_000L,
        )
        builder = SnapshotBuilder(ctx, historyStore)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `overview on empty state has zero counts and computes uptime`() = runTest {
        val now = System.currentTimeMillis()
        val dto = builder.overview()

        assertEquals(0, dto.totalRooms)
        assertEquals(0, dto.totalPlayers)
        assertEquals(0, dto.totalSessions)
        assertEquals(0L, dto.totalGameCount)
        assertEquals(0, dto.todayGameCount)
        // startedAtEpochMs = 1000 → uptime 应该是 (now - 1000) / 1000 秒
        assertTrue(dto.uptimeSeconds in ((now - 1000) / 1000)..((now + 1000) / 1000))
        assertTrue(dto.jvmHeapMaxMb > 0, "JVM 一定有 maxMemory")
    }

    @Test
    fun `overview roomsByStatus map contains all RoomStatus keys with zero counts`() = runTest {
        val dto = builder.overview()
        // PR 0 起 ServerRoomManager.snapshotCounts 总是初始化全部 RoomStatus 枚举位
        listOf("WAITING", "STARTING", "IN_GAME", "FINISHED").forEach {
            assertNotNull(dto.roomsByStatus[it], "$it 应作为 key 出现，值 0")
            assertEquals(0, dto.roomsByStatus[it])
        }
    }

    @Test
    fun `rooms returns empty list when no rooms exist`() = runTest {
        assertEquals(emptyList(), builder.rooms())
    }

    @Test
    fun `room of unknown id returns null`() = runTest {
        assertNull(builder.room("does-not-exist"))
    }

    @Test
    fun `players returns empty list when no rooms`() = runTest {
        assertEquals(emptyList(), builder.players())
    }

    @Test
    fun `sessions returns empty list when no sessions`() = runTest {
        assertEquals(emptyList(), builder.sessions())
    }

    @Test
    fun `maskId truncates UUID and keeps AI_N format`() {
        assertEquals("fa8c91d2...", maskId("fa8c91d2-1234-5678-9abc-def012345678"))
        assertEquals("AI_3", maskId("AI_3"))
        assertEquals("AI_42", maskId("AI_42"))
        assertEquals("short", maskId("short"))  // <= 8 字符不截
    }
}
