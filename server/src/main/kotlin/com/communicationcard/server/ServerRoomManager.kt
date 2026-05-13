package com.communicationcard.server

import com.communicationcard.game.network.*
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 服务端房间管理器
 */
class ServerRoomManager {

    private val rooms = ConcurrentHashMap<String, ServerRoom>()
    private val roomsByCode = ConcurrentHashMap<String, ServerRoom>()
    private val playerToRoom = ConcurrentHashMap<String, String>()
    private val aiCounter = AtomicInteger(0)

    /**
     * 创建房间
     */
    fun createRoom(session: GameSession, playerName: String, roomName: String, maxPlayers: Int): ServerRoom {
        val roomId = generateRoomId()
        val roomCode = generateRoomCode()
        val finalRoomName = roomName.ifEmpty { "${playerName}的房间" }

        val room = ServerRoom(
            roomId = roomId,
            roomCode = roomCode,
            roomName = finalRoomName,
            hostId = session.id,
            maxPlayers = maxPlayers.coerceIn(4, 12)
        )

        // 添加房主作为第一个玩家
        val hostPlayer = ServerPlayer(
            id = session.id,
            name = playerName,
            session = session,
            isReady = true, // 房主自动准备
            isAI = false,
            seatIndex = 0,
            team = "TEAM_A"
        )
        room.players.add(hostPlayer)

        session.roomId = roomId
        session.playerName = playerName
        session.seatIndex = 0

        rooms[roomId] = room
        roomsByCode[roomCode] = room
        playerToRoom[session.id] = roomId

        println("Room created: $roomCode (ID: $roomId) by $playerName")
        return room
    }

    /**
     * 加入房间
     */
    fun joinRoom(session: GameSession, roomCode: String, playerName: String): Result<ServerRoom> {
        val room = roomsByCode[roomCode]
            ?: return Result.failure(Exception("房间不存在"))

        // 串行化座位分配 + 玩家加入，防止两个客户端同时加入时分到同一座位
        synchronized(room) {
            if (room.status != RoomStatus.WAITING) {
                return Result.failure(Exception("游戏已开始，无法加入"))
            }

            if (room.players.size >= room.maxPlayers) {
                return Result.failure(Exception("房间已满"))
            }

            // 检查是否已在房间中
            if (room.players.any { it.id == session.id }) {
                return Result.failure(Exception("你已在房间中"))
            }

            val seatIndex = findNextSeat(room)
            val team = if (seatIndex % 2 == 0) "TEAM_A" else "TEAM_B"

            val player = ServerPlayer(
                id = session.id,
                name = playerName,
                session = session,
                isReady = false,
                isAI = false,
                seatIndex = seatIndex,
                team = team
            )
            room.players.add(player)

            session.roomId = room.roomId
            session.playerName = playerName
            session.seatIndex = seatIndex

            playerToRoom[session.id] = room.roomId
        }

        println("Player $playerName joined room ${room.roomCode}")
        return Result.success(room)
    }

    /**
     * 离开房间
     */
    fun leaveRoom(session: GameSession): ServerRoom? {
        val roomId = session.roomId ?: return null
        val room = rooms[roomId] ?: return null

        room.players.removeIf { it.id == session.id }
        playerToRoom.remove(session.id)
        session.roomId = null

        println("Player ${session.playerName} left room ${room.roomCode}")

        // 如果房间空了，删除房间
        if (room.players.none { !it.isAI }) {
            rooms.remove(roomId)
            roomsByCode.remove(room.roomCode)
            println("Room ${room.roomCode} deleted (empty)")
            return null
        }

        // 如果房主离开，转移房主
        if (room.hostId == session.id) {
            val newHost = room.players.firstOrNull { !it.isAI }
            if (newHost != null) {
                room.hostId = newHost.id
                println("Host transferred to ${newHost.name}")
            }
        }

        return room
    }

    /**
     * 设置准备状态
     */
    fun setReady(session: GameSession, isReady: Boolean): ServerRoom? {
        val roomId = session.roomId ?: return null
        val room = rooms[roomId] ?: return null

        // 仅在等待中允许更改准备状态
        if (room.status != RoomStatus.WAITING) return null

        room.players.find { it.id == session.id }?.isReady = isReady
        return room
    }

    /**
     * 踢出玩家
     */
    fun kickPlayer(hostSession: GameSession, targetPlayerId: String): Result<ServerRoom> {
        val roomId = hostSession.roomId ?: return Result.failure(Exception("不在房间中"))
        val room = rooms[roomId] ?: return Result.failure(Exception("房间不存在"))

        if (room.hostId != hostSession.id) {
            return Result.failure(Exception("只有房主可以踢人"))
        }

        val targetPlayer = room.players.find { it.id == targetPlayerId }
            ?: return Result.failure(Exception("玩家不存在"))

        room.players.remove(targetPlayer)
        playerToRoom.remove(targetPlayerId)

        // 通知被踢玩家
        targetPlayer.session?.let { session ->
            session.roomId = null
        }

        println("Player ${targetPlayer.name} kicked from room ${room.roomCode}")
        return Result.success(room)
    }

    /**
     * 添加AI玩家
     */
    fun addAI(hostSession: GameSession): Result<ServerRoom> {
        val roomId = hostSession.roomId ?: return Result.failure(Exception("不在房间中"))
        val room = rooms[roomId] ?: return Result.failure(Exception("房间不存在"))

        if (room.hostId != hostSession.id) {
            return Result.failure(Exception("只有房主可以添加AI"))
        }

        if (room.players.size >= room.maxPlayers) {
            return Result.failure(Exception("房间已满"))
        }

        val seatIndex = findNextSeat(room)
        val team = if (seatIndex % 2 == 0) "TEAM_A" else "TEAM_B"
        val aiNumber = aiCounter.incrementAndGet()

        val aiPlayer = ServerPlayer(
            id = "AI_$aiNumber",
            name = "电脑$aiNumber",
            session = null,
            isReady = true,
            isAI = true,
            seatIndex = seatIndex,
            team = team
        )
        room.players.add(aiPlayer)

        println("AI player added to room ${room.roomCode}")
        return Result.success(room)
    }

    /**
     * 检查是否可以开始游戏
     * 至少1名真人玩家，所有真人玩家都已准备（房主自动算准备）
     * 服务端会自动用AI补足到6人
     */
    fun canStartGame(room: ServerRoom): Boolean {
        val humanPlayers = room.players.filter { !it.isAI }
        if (humanPlayers.isEmpty()) return false
        return humanPlayers.all { it.isReady || it.id == room.hostId }
    }

    /**
     * 自动填充AI直到达到指定人数
     */
    fun fillWithAI(room: ServerRoom, targetCount: Int = 6) {
        while (room.players.size < targetCount && room.players.size < room.maxPlayers) {
            val seatIndex = findNextSeat(room)
            val team = if (seatIndex % 2 == 0) "TEAM_A" else "TEAM_B"
            val aiNumber = aiCounter.incrementAndGet()

            val aiPlayer = ServerPlayer(
                id = "AI_$aiNumber",
                name = "电脑$aiNumber",
                session = null,
                isReady = true,
                isAI = true,
                seatIndex = seatIndex,
                team = team
            )
            room.players.add(aiPlayer)
        }
        // 按座位号排序
        room.players.sortBy { it.seatIndex }
    }

    /**
     * 获取房间
     */
    fun getRoom(roomId: String): ServerRoom? = rooms[roomId]

    fun getRoomByCode(code: String): ServerRoom? = roomsByCode[code]

    /**
     * 列出所有等待中的房间
     */
    fun listWaitingRooms(): List<RoomInfo> {
        return rooms.values
            .filter { it.status == RoomStatus.WAITING }
            .map { it.toRoomInfo() }
    }

    fun getRoomByPlayer(playerId: String): ServerRoom? {
        val roomId = playerToRoom[playerId] ?: return null
        return rooms[roomId]
    }

    /**
     * 直接删除房间（用于游戏结束后无人连接时清理资源）
     */
    fun deleteRoom(roomId: String) {
        val room = rooms.remove(roomId) ?: return
        roomsByCode.remove(room.roomCode)
        room.players.forEach { playerToRoom.remove(it.id) }
        println("Room ${room.roomCode} deleted")
    }

    /**
     * 清除某玩家ID到房间的映射（用于显式离开后阻止其用旧token重连回原房间）
     */
    fun forgetPlayerMapping(playerId: String) {
        playerToRoom.remove(playerId)
    }

    /**
     * 获取房间内的所有会话
     */
    fun getRoomSessions(roomId: String): List<GameSession> {
        val room = rooms[roomId] ?: return emptyList()
        return room.players.mapNotNull { it.session }
    }

    // ============================================================
    //  PR 2 (admin 后台) 暴露给 SnapshotBuilder 的弱一致快照接口。
    //  全部 lock-free：直接读 ConcurrentHashMap.values，纳秒级。
    //  允许"读到刚删的房间"或"漏读刚加的房间"——admin 监控可接受此误差。
    // ============================================================

    /**
     * 当前所有房间（不区分状态）；调用方自行 filter。
     */
    internal fun allRoomsSnapshot(): List<ServerRoom> = rooms.values.toList()

    /**
     * 按 [RoomStatus] 分组的房间计数。`/admin/api/overview` 用。
     */
    internal fun snapshotCounts(): Map<RoomStatus, Int> {
        val counts = EnumMap<RoomStatus, Int>(RoomStatus::class.java)
        RoomStatus.values().forEach { counts[it] = 0 }
        for (room in rooms.values) {
            counts.merge(room.status, 1, Int::plus)
        }
        return counts
    }

    private fun findNextSeat(room: ServerRoom): Int {
        val usedSeats = room.players.map { it.seatIndex }.toSet()
        for (i in 0 until room.maxPlayers) {
            if (i !in usedSeats) return i
        }
        return room.players.size
    }

    private fun generateRoomId(): String {
        return "room_${System.currentTimeMillis()}_${Random.nextInt(10000)}"
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        // 防止与现有房间码冲突（4位编码空间约一百万，碰撞概率小但确实存在）
        repeat(8) {
            val code = (1..4).map { chars.random() }.joinToString("")
            if (!roomsByCode.containsKey(code)) return code
        }
        // 退化方案：附加序号确保唯一
        return (1..6).map { chars.random() }.joinToString("")
    }
}

/**
 * 服务端房间
 */
class ServerRoom(
    val roomId: String,
    val roomCode: String,
    val roomName: String,
    var hostId: String,
    val maxPlayers: Int
) {
    // 使用线程安全的 CopyOnWriteArrayList，避免在并发广播/迭代时抛 ConcurrentModificationException
    val players: MutableList<ServerPlayer> = java.util.concurrent.CopyOnWriteArrayList()
    var status: RoomStatus = RoomStatus.WAITING
    var gameState: ServerGameState? = null

    /**
     * 房间级 AI 出牌延迟（毫秒）。仅 host 可改（feature_spec G37）。
     * 默认 400ms（GameMessage.AI_DELAY_DEFAULT_MS）；范围 [100, 1000]。
     * 影响所有补位 AI 座位 + 玩家被托管时的 AI（除非该玩家自己设了 takeoverAiDelayMs）。
     */
    @Volatile var serverAiDelayMs: Int = com.communicationcard.game.network.GameMessage.AI_DELAY_DEFAULT_MS

    fun toRoomInfo(): RoomInfo {
        return RoomInfo(
            roomId = roomId,
            roomCode = roomCode,
            roomName = roomName,
            hostId = hostId,
            players = players.map { it.toRoomPlayer() },
            maxPlayers = maxPlayers,
            status = status,
            serverAiDelayMs = serverAiDelayMs
        )
    }
}

/**
 * 服务端玩家
 */
data class ServerPlayer(
    val id: String,
    val name: String,
    var session: GameSession?,
    var isReady: Boolean,
    val isAI: Boolean,
    val seatIndex: Int,
    val team: String
) {
    var isConnected: Boolean = true  // AI players are always "connected"
    var isAISubstitute: Boolean = false
    /**
     * 该玩家被 AI 接管时的出牌延迟（毫秒）。仅本玩家可改（feature_spec G38）。
     * 默认 400ms；范围 [100, 1000]。覆盖房间级 serverAiDelayMs。
     */
    @Volatile var takeoverAiDelayMs: Int = com.communicationcard.game.network.GameMessage.AI_DELAY_DEFAULT_MS

    fun toRoomPlayer(): RoomPlayer {
        return RoomPlayer(
            id = id,
            name = name,
            isReady = isReady,
            isConnected = isConnected,
            isAI = isAI,
            team = team,
            seatIndex = seatIndex,
            takeoverAiDelayMs = takeoverAiDelayMs,
            isAISubstitute = isAISubstitute
        )
    }
}
