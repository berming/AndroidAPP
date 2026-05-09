package com.communicationcard.game.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 网络消息协议
 */
@Serializable
sealed class GameMessage {

    companion object {
        /**
         * 协议版本号。所有 client→server 的会话建立消息（如 Reconnect）都携带此字段；
         * 服务端在握手时校验：版本不匹配 → 拒绝连接（避免老客户端误读新协议字段）。
         *
         * 升级规则（PR-H3 起）：任一兼容性破坏的协议变更（字段类型变 / 移除字段 /
         * 枚举值改名）必须 +1。仅添加字段且字段有默认值不必 +1。
         */
        const val PROTOCOL_VERSION = 1

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

        fun fromJson(jsonString: String): GameMessage {
            return json.decodeFromString(serializer(), jsonString)
        }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}

// ==================== 房间消息 ====================

@Serializable
@SerialName("room.create")
data class CreateRoom(
    val playerName: String,
    val roomName: String = "",
    val maxPlayers: Int = 6
) : GameMessage()

@Serializable
@SerialName("room.created")
data class RoomCreated(
    val room: RoomInfo
) : GameMessage()

@Serializable
@SerialName("room.join")
data class JoinRoom(
    val roomCode: String,
    val playerName: String
) : GameMessage()

@Serializable
@SerialName("room.joined")
data class RoomJoined(
    val room: RoomInfo,
    val playerId: String
) : GameMessage()

@Serializable
@SerialName("room.leave")
data class LeaveRoom(
    val roomId: String
) : GameMessage()

@Serializable
@SerialName("room.update")
data class RoomUpdate(
    val room: RoomInfo
) : GameMessage()

@Serializable
@SerialName("room.ready")
data class PlayerReady(
    val isReady: Boolean
) : GameMessage()

@Serializable
@SerialName("room.start")
data class StartGameRequest(
    val roomId: String
) : GameMessage()

@Serializable
@SerialName("room.kick")
data class KickPlayer(
    val playerId: String
) : GameMessage()

@Serializable
@SerialName("room.add_ai")
data class AddAI(
    val roomId: String
) : GameMessage()

@Serializable
@SerialName("room.list")
class ListRooms : GameMessage()

@Serializable
@SerialName("room.list_result")
data class RoomListResult(
    val rooms: List<RoomInfo>
) : GameMessage()

// ==================== 游戏消息 ====================

@Serializable
@SerialName("game.start")
data class GameStart(
    val state: SerializedGameState
) : GameMessage()

@Serializable
@SerialName("game.action")
data class GameAction(
    val action: PlayerAction
) : GameMessage()

@Serializable
@SerialName("game.action_result")
data class GameActionResult(
    val success: Boolean,
    val error: String? = null,
    val state: SerializedGameState? = null
) : GameMessage()

@Serializable
@SerialName("game.event")
data class GameEventMessage(
    val event: SerializedGameEvent
) : GameMessage()

@Serializable
@SerialName("game.sync")
data class GameSync(
    val state: SerializedGameState
) : GameMessage()

@Serializable
@SerialName("game.turn_timeout")
data class TurnTimeout(
    val playerId: Int
) : GameMessage()

@Serializable
@SerialName("game.end")
data class GameEnd(
    val result: SerializedGameResult
) : GameMessage()

// ==================== 聊天消息 ====================

@Serializable
@SerialName("chat.text")
data class TextChatMessage(
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isTeamOnly: Boolean = false
) : GameMessage()

@Serializable
@SerialName("chat.quick")
data class QuickChatMessage(
    val senderId: String,
    val senderName: String,
    val quickType: QuickMessageType,
    val timestamp: Long
) : GameMessage()

// ==================== 系统消息 ====================

@Serializable
@SerialName("sys.heartbeat")
data class Heartbeat(
    val timestamp: Long
) : GameMessage()

@Serializable
@SerialName("sys.reconnect")
data class Reconnect(
    val sessionToken: String,
    // PR-H3 引入：服务端在 handleReconnect 时校验该字段；不匹配 → 拒绝重连。
    // 默认值让旧客户端（不发该字段）仍按 v1 处理，到下次升 PROTOCOL_VERSION 时再断兼容。
    val protocolVersion: Int = PROTOCOL_VERSION
) : GameMessage()

@Serializable
@SerialName("sys.reconnect_success")
data class ReconnectSuccess(
    val state: SerializedGameState?
) : GameMessage()

@Serializable
@SerialName("sys.error")
data class ErrorMessage(
    val code: Int,
    val message: String
) : GameMessage()

@Serializable
@SerialName("sys.player_disconnected")
data class PlayerDisconnected(
    val playerId: String,
    val playerName: String
) : GameMessage()

@Serializable
@SerialName("sys.player_reconnected")
data class PlayerReconnected(
    val playerId: String,
    val playerName: String
) : GameMessage()

// ==================== 数据类 ====================

@Serializable
data class RoomInfo(
    val roomId: String,
    val roomCode: String,
    val roomName: String = "",
    val hostId: String,
    val players: List<RoomPlayer>,
    val maxPlayers: Int,
    val status: RoomStatus
)

@Serializable
data class RoomPlayer(
    val id: String,
    val name: String,
    val isReady: Boolean,
    val isConnected: Boolean,
    val isAI: Boolean,
    val team: String? = null,  // "TEAM_A" or "TEAM_B"
    val seatIndex: Int
)

@Serializable
enum class RoomStatus {
    WAITING,
    STARTING,
    IN_GAME,
    FINISHED
}

@Serializable
sealed class PlayerAction {
    @Serializable
    @SerialName("play")
    data class PlayCards(
        val playerId: Int,
        val cards: List<SerializedCard>
    ) : PlayerAction()

    @Serializable
    @SerialName("pass")
    data class Pass(
        val playerId: Int
    ) : PlayerAction()
}

@Serializable
data class SerializedCard(
    val rank: String,
    val suit: String,
    val deckIndex: Int = 0
)

@Serializable
data class SerializedCardGroup(
    val cards: List<SerializedCard>,
    val type: String,
    val primaryRank: String
)

@Serializable
data class SerializedPlayer(
    val id: Int,
    val name: String,
    val type: String,  // "HUMAN", "AI", "REMOTE"
    val team: String,  // "TEAM_A", "TEAM_B"
    val hand: List<SerializedCard>,
    val handSize: Int,
    val collectedScore: Int,
    val hasFinished: Boolean,
    val finishOrder: Int,
    val remoteId: String? = null
)

@Serializable
data class SerializedGameState(
    val phase: String,
    val currentPlayerIndex: Int,
    val players: List<SerializedPlayer>,
    val lastPlayedGroup: SerializedCardGroup? = null,
    val lastPlayerId: Int? = null,
    val roundWinnerId: Int? = null,
    val consecutivePasses: Int,
    val currentRoundScore: Int,
    val teamAScore: Int,
    val teamBScore: Int,
    val version: Long
)

@Serializable
sealed class SerializedGameEvent {
    @Serializable
    @SerialName("cards_dealt")
    data class CardsDealt(val playerCount: Int) : SerializedGameEvent()

    @Serializable
    @SerialName("turn_start")
    data class TurnStart(val playerId: Int) : SerializedGameEvent()

    @Serializable
    @SerialName("cards_played")
    data class CardsPlayed(
        val playerId: Int,
        val cardGroup: SerializedCardGroup
    ) : SerializedGameEvent()

    @Serializable
    @SerialName("player_passed")
    data class PlayerPassed(val playerId: Int) : SerializedGameEvent()

    @Serializable
    @SerialName("round_won")
    data class RoundWon(
        val playerId: Int,
        val score: Int
    ) : SerializedGameEvent()

    @Serializable
    @SerialName("player_finished")
    data class PlayerFinished(
        val playerId: Int,
        val order: Int
    ) : SerializedGameEvent()

    @Serializable
    @SerialName("score_update")
    data class ScoreUpdate(
        val teamAScore: Int,
        val teamBScore: Int
    ) : SerializedGameEvent()
}

@Serializable
data class SerializedGameResult(
    val winner: String?,  // "TEAM_A", "TEAM_B", or null for draw
    val teamAScore: Int,
    val teamBScore: Int,
    val trigger: String  // "TEAM_ALL_FINISHED" or "SCORE_REACHED_200"
)

@Serializable
enum class QuickMessageType(val text: String) {
    NICE_PLAY("好牌！"),
    HELP_TEAMMATE("队友上！"),
    PASS("要不起"),
    BOMB_WARNING("小心炸弹"),
    GOOD_GAME("GG"),
    HURRY_UP("快点啊"),
    SORRY("抱歉"),
    THANKS("谢谢")
}
