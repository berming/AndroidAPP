package com.communicationcard.server.admin.alert

import com.communicationcard.server.GameSession
import com.communicationcard.server.ServerContext
import com.communicationcard.server.ServerGameManager
import com.communicationcard.server.ServerGameState
import com.communicationcard.server.ServerPlayer
import com.communicationcard.server.ServerRoom
import com.communicationcard.server.ServerRoomManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertRuleTest {

    private fun makeServerCtx(rooms: List<ServerRoom> = emptyList()): ServerContext {
        val roomManager = ServerRoomManager()
        roomManager.injectRooms(rooms)
        return ServerContext(
            roomManager = roomManager,
            gameManager = ServerGameManager(roomManager),
            sessions = ConcurrentHashMap<String, GameSession>(),
            startedAtEpochMs = 0L,
        )
    }

    // === RoomStuckRule ===

    @Test
    fun `RoomStuckRule emits nothing when no rooms`() {
        val ctx = makeServerCtx()
        val out = RoomStuckRule.evaluate(ctx, 1_000_000_000L)
        assertEquals(emptyList(), out)
    }

    @Test
    fun `RoomStuckRule emits nothing when room is WAITING`() {
        val room = makeRoom(roomCode = "WAIT", inGame = false, lastActionAt = 0L)
        val out = RoomStuckRule.evaluate(makeServerCtx(listOf(room)), 1_000_000_000L)
        assertEquals(emptyList(), out)
    }

    @Test
    fun `RoomStuckRule emits nothing when last action was recent`() {
        val now = 1_000_000_000L
        // 1 分钟前的动作不算卡
        val room = makeRoom(roomCode = "FRSH", inGame = true, lastActionAt = now - 60_000)
        val out = RoomStuckRule.evaluate(makeServerCtx(listOf(room)), now)
        assertEquals(emptyList(), out)
    }

    @Test
    fun `RoomStuckRule emits one candidate per stuck room`() {
        val now = 1_000_000_000L
        // 6 分钟前的动作 → 卡（超过 5 min 阈值）
        val r1 = makeRoom(roomCode = "STK1", inGame = true, lastActionAt = now - 6 * 60_000)
        val r2 = makeRoom(roomCode = "STK2", inGame = true, lastActionAt = now - 7 * 60_000)
        val r3 = makeRoom(roomCode = "FINE", inGame = true, lastActionAt = now - 10_000)

        val out = RoomStuckRule.evaluate(makeServerCtx(listOf(r1, r2, r3)), now)
        assertEquals(2, out.size)
        val codes = out.mapNotNull { it.payload["roomCode"] }.toSet()
        assertEquals(setOf("STK1", "STK2"), codes)
        out.forEach { c ->
            assertEquals("ROOM_STUCK", c.rule)
            assertEquals("WARN", c.severity)
            assertTrue(c.message.contains("无任何动作"))
        }
    }

    // === JvmHeapHighRule（无法控制实际 heap，只测 threshold 函数）===

    @Test
    fun `JvmHeapHighRule emits at most one candidate`() {
        val ctx = makeServerCtx()
        val out = JvmHeapHighRule.evaluate(ctx, 0L)
        // 测试环境 JVM heap 通常远低于 85%，应是 emptyList
        // 不强制：如果 CI 内存吃紧偶发触发，那也只能 1 条（无 roomId 区分）
        assertTrue(out.size <= 1)
    }

    // === DisconnectRatioHighRule ===

    @Test
    fun `DisconnectRatioHighRule needs minimum sample size`() {
        // 仅 5 人（< MIN_SAMPLE_SIZE=10）即使全断也不报
        val room = makeRoom(
            roomCode = "TINY",
            inGame = true,
            lastActionAt = 0L,
            players = (0 until 5).map { i ->
                player(seat = i, connected = false, ai = false)
            },
        )
        val out = DisconnectRatioHighRule.evaluate(makeServerCtx(listOf(room)), 0L)
        assertEquals(emptyList(), out)
    }

    @Test
    fun `DisconnectRatioHighRule below threshold yields no candidate`() {
        // 12 人，断 3 个 → 25% < 30%
        val players = (0 until 12).map { i -> player(seat = i, connected = i >= 3, ai = false) }
        val room = makeRoom(roomCode = "QUIET", inGame = true, lastActionAt = 0L, players = players)
        val out = DisconnectRatioHighRule.evaluate(makeServerCtx(listOf(room)), 0L)
        assertEquals(emptyList(), out)
    }

    @Test
    fun `DisconnectRatioHighRule above threshold yields one candidate`() {
        // 12 人，断 5 个 → 41% > 30%
        val players = (0 until 12).map { i -> player(seat = i, connected = i >= 5, ai = false) }
        val room = makeRoom(roomCode = "DROP", inGame = true, lastActionAt = 0L, players = players)
        val out = DisconnectRatioHighRule.evaluate(makeServerCtx(listOf(room)), 0L)
        assertEquals(1, out.size)
        assertEquals("DISCONNECT_RATIO_HIGH", out[0].rule)
        assertEquals("INFO", out[0].severity)
        assertEquals("5", out[0].payload["disconnected"])
        assertEquals("12", out[0].payload["totalHumans"])
    }

    @Test
    fun `DisconnectRatioHighRule ignores AI players in sample`() {
        // 5 真人（不够 sample size）+ 10 AI（不计入）→ 没告警
        val players = mutableListOf<ServerPlayer>().apply {
            (0 until 5).forEach { add(player(seat = it, connected = false, ai = false)) }
            (5 until 15).forEach { add(player(seat = it, connected = false, ai = true)) }
        }
        val room = makeRoom(roomCode = "MIXAI", inGame = true, lastActionAt = 0L, players = players)
        val out = DisconnectRatioHighRule.evaluate(makeServerCtx(listOf(room)), 0L)
        assertEquals(emptyList(), out, "AI 不应纳入 totalHumans 计数")
    }

    // === fixture helpers ===

    private fun makeRoom(
        roomCode: String,
        inGame: Boolean,
        lastActionAt: Long,
        players: List<ServerPlayer> = listOf(player(seat = 0, connected = true, ai = false)),
    ): ServerRoom {
        val room = ServerRoom(
            roomId = "room-$roomCode",
            roomCode = roomCode,
            roomName = roomCode,
            hostId = "host",
            maxPlayers = 6,
        )
        players.forEach { room.players.add(it) }
        if (inGame) {
            room.status = com.communicationcard.game.network.RoomStatus.IN_GAME
            room.gameState = ServerGameState(
                phase = "PLAYING",
                currentPlayerIndex = 0,
                hands = mutableMapOf(),
                lastPlayedGroup = null,
                lastPlayerId = null,
                consecutivePasses = 0,
                currentRoundScore = 0,
                teamAScore = 0,
                teamBScore = 0,
                finishOrder = mutableListOf(),
                version = 1,
            ).also { it.lastActionAt = lastActionAt }
        }
        return room
    }

    private fun player(seat: Int, connected: Boolean, ai: Boolean): ServerPlayer {
        val p = ServerPlayer(
            id = if (ai) "AI_$seat" else "human-$seat",
            name = if (ai) "AI$seat" else "P$seat",
            session = null,
            isReady = true,
            isAI = ai,
            seatIndex = seat,
            team = if (seat % 2 == 0) "TEAM_A" else "TEAM_B",
        )
        p.isConnected = connected
        return p
    }

    /**
     * 反射注入 fake rooms 到 ServerRoomManager 私有的 rooms map。
     * 测试唯一用途；生产 createRoom 仍走正规路径。
     */
    private fun ServerRoomManager.injectRooms(rooms: List<ServerRoom>) {
        if (rooms.isEmpty()) return
        val field = ServerRoomManager::class.java.getDeclaredField("rooms")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(this) as java.util.concurrent.ConcurrentHashMap<String, ServerRoom>
        rooms.forEach { map[it.roomId] = it }
    }
}
