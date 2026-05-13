package com.communicationcard.server.admin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GameHistoryStore 端到端：enqueue → Channel → IO 协程 → SQLite insert →
 * 查询 API。
 *
 * **不使用 kotlinx.coroutines.test.runTest**：runTest 用 TestScope 的虚拟时钟，
 * 不推进 Dispatchers.IO 上的真实协程，导致 `withTimeout(5_000) { while
 * (store.countAll() < N) delay(20) }` 在虚拟 5s 内永远等不到 IO 协程完成 →
 * `TimeoutCancellationException: Timed out after 5s of _virtual_ time`。
 * 这里改用 `runBlocking` 走真实时钟。
 */
class GameHistoryStoreTest {

    private lateinit var db: AdminDb
    private lateinit var store: GameHistoryStore

    @BeforeTest
    fun setup() {
        db = AdminDb(AdminDb.IN_MEMORY)
        store = GameHistoryStore(db)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { store.stop() }
        db.close()
    }

    @Test
    fun `enqueue then async insert ends up in games table`() = runBlocking {
        db.runMigrations()
        store.start()

        store.enqueue(record("room-1", "AAAA", winnerTeam = "TEAM_A"))
        store.enqueue(record("room-2", "BBBB", winnerTeam = "TEAM_B"))

        // 等待最多 5 秒 IO 协程消费 2 条（真实时钟）
        withTimeout(5_000) {
            while (store.countAll() < 2) delay(20)
        }

        assertEquals(2L, store.countAll())
        val summaries = store.listSummaries(from = null, to = null, limit = 10)
        assertEquals(2, summaries.size)
        // 默认 ORDER BY started_at DESC，所以 room-2 在前（它 startedAt 更新）
        assertEquals("BBBB", summaries[0].roomCode)
        assertEquals("TEAM_B", summaries[0].winnerTeam)
    }

    @Test
    fun `listSummaries respects from-to time window`() = runBlocking {
        db.runMigrations()
        store.start()

        val base = 10_000_000L
        store.enqueue(record("r-old", "OLD1", startedAt = base + 1000))
        store.enqueue(record("r-mid", "MID1", startedAt = base + 2000))
        store.enqueue(record("r-new", "NEW1", startedAt = base + 3000))

        withTimeout(5_000) {
            while (store.countAll() < 3) delay(20)
        }

        val window = store.listSummaries(from = base + 1500, to = base + 2500, limit = 10)
        assertEquals(1, window.size)
        assertEquals("MID1", window[0].roomCode)
    }

    @Test
    fun `countSince counts only games newer than threshold`() = runBlocking {
        db.runMigrations()
        store.start()

        val cutoff = 50_000L
        store.enqueue(record("r1", "AAA1", startedAt = cutoff - 1000))
        store.enqueue(record("r2", "BBB1", startedAt = cutoff + 1000))
        store.enqueue(record("r3", "CCC1", startedAt = cutoff + 2000))

        withTimeout(5_000) {
            while (store.countAll() < 3) delay(20)
        }

        assertEquals(2, store.countSince(cutoff))
    }

    @Test
    fun `findDetail returns players in seat order`() = runBlocking {
        db.runMigrations()
        store.start()

        val record = record(
            "r-detail", "DET1",
            players = listOf(
                player(seat = 2, name = "C", team = "TEAM_B"),
                player(seat = 0, name = "A", team = "TEAM_A"),
                player(seat = 1, name = "B", team = "TEAM_A"),
            ),
        )
        store.enqueue(record)
        withTimeout(5_000) { while (store.countAll() < 1) delay(20) }

        val summary = store.listSummaries(null, null, 10).first()
        val detail = store.findDetail(summary.id)
        assertNotNull(detail)
        assertEquals(3, detail.players.size)
        // 应按 seat 排
        assertEquals(listOf(0, 1, 2), detail.players.map { it.seatIndex })
        assertEquals(listOf("A", "B", "C"), detail.players.map { it.name })
    }

    @Test
    fun `findDetail returns null for unknown id`() = runBlocking {
        db.runMigrations()
        store.start()
        assertNull(store.findDetail(999_999_999L))
    }

    @Test
    fun `enqueue after stop is harmless`() = runBlocking {
        db.runMigrations()
        store.start()
        store.stop()

        // 关 Channel 后再 enqueue：trySend 失败但不抛
        store.enqueue(record("r-after-stop", "ZZZZ"))
        // 不应崩
        assertTrue(true)
    }

    // === fixture helpers ===

    private fun record(
        roomId: String,
        roomCode: String,
        startedAt: Long = System.currentTimeMillis(),
        winnerTeam: String = "TEAM_A",
        players: List<GamePlayerRecord> = listOf(
            player(seat = 0, name = "Alice", team = "TEAM_A"),
            player(seat = 1, name = "Bob",   team = "TEAM_B"),
        ),
    ): GameRecord = GameRecord(
        roomId = roomId,
        roomCode = roomCode,
        startedAt = startedAt,
        endedAt = startedAt + 60_000,
        durationMs = 60_000,
        playerCount = players.size,
        humanCount = players.count { !it.isAI },
        aiCount = players.count { it.isAI },
        winnerTeam = winnerTeam,
        trigger = "TEAM_ALL_FINISHED",
        teamAScore = 200,
        teamBScore = 0,
        finalVersion = 1L,
        players = players,
    )

    private fun player(
        seat: Int,
        name: String,
        team: String,
        isAI: Boolean = false,
    ) = GamePlayerRecord(
        seatIndex = seat,
        playerIdMasked = "abcd1234...",
        name = name,
        team = team,
        isAI = isAI,
        wasSubstituted = false,
        finished = true,
        finishOrder = seat + 1,
        collectedScore = 50,
        finalHandSize = 0,
    )
}
