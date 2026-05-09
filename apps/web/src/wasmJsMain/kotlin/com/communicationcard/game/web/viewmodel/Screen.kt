package com.communicationcard.game.web.viewmodel

import com.communicationcard.game.network.RoomInfo
import com.communicationcard.game.network.SerializedGameResult
import com.communicationcard.game.network.SerializedGameState
import com.communicationcard.game.web.net.WebSocketTransport

/**
 * 整个 Web 客户端的屏幕状态，由 [AppViewModel] 持有并切换。
 *
 * 设计原则：每个屏幕需要的"完整渲染数据"都直接放在这里 —— Composable 接收到 Screen
 * 后不再需要外部依赖即可绘制。这样把状态管理集中到 ViewModel，避免散落 remember{}。
 */
sealed class Screen {

    /** 入口：选择单机 AI / 联网多人。 */
    data object Home : Screen()

    /** 联网模式：服务端 URL / 昵称输入 + 房间列表 + 创建/加入。 */
    data class Lobby(
        val serverUrl: String,
        val nickname: String,
        val connectionState: WebSocketTransport.State,
        val rooms: List<RoomInfo>,
        val joinRoomCode: String = "",
        val errorMessage: String? = null,
    ) : Screen()

    /** 联网模式：在房间内等待开局。 */
    data class Room(
        val room: RoomInfo,
        val localPlayerId: String,
        val isReady: Boolean,
    ) : Screen()

    /** 游戏中：单机和联网共用，state 来源不同（本地或服务器）。 */
    data class Game(
        val state: SerializedGameState,
        val localSeatIndex: Int,
        val mode: Mode,
        val selectedCardIds: Set<String> = emptySet(),
        val message: String? = null,
    ) : Screen() {
        enum class Mode { SinglePlayer, Multiplayer }
    }

    /** 结算：显示队伍最终分数。 */
    data class Settlement(
        val result: SerializedGameResult,
        val mode: Game.Mode,
    ) : Screen()
}
