package com.communicationcard.game.web.viewmodel

import com.communicationcard.game.network.RoomInfo
import com.communicationcard.game.network.SerializedCardGroup
import com.communicationcard.game.network.SerializedGameResult
import com.communicationcard.game.network.SerializedGameState
import com.communicationcard.game.web.net.WebSocketTransport
import com.communicationcard.game.web.storage.Statistics
import com.communicationcard.game.web.storage.UserPreferences

/**
 * 整个 Web 客户端的屏幕状态，由 [AppViewModel] 持有并切换。
 *
 * 设计原则：每个屏幕需要的"完整渲染数据"都直接放在这里 —— Composable 接收到 Screen
 * 后不再需要外部依赖即可绘制。这样把状态管理集中到 ViewModel，避免散落 remember{}。
 */
sealed class Screen {

    /** 入口：5 大菜单（与 Android MainActivity 对齐）。 */
    data object Home : Screen()

    /** 设置：音效 / 震动 / 出牌动画 / 速度 / 昵称（持久化到 localStorage）。 */
    data class Settings(val prefs: UserPreferences) : Screen()

    /** 统计：总场次 / 胜率 / 连胜 / 累计得分（持久化到 localStorage）。 */
    data class Stats(val stats: Statistics) : Screen()

    /** 帮助：规则 / 关于 / 项目链接。 */
    data object Help : Screen()

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
        /** 提示按钮高亮的卡 IDs（与 selectedCardIds 共存：提示后用户可微调选择）。 */
        val hintedCardIds: Set<String> = emptySet(),
        /**
         * Stage 4：每个玩家最近打出的一手（Android 客户端有此特性，Web 之前缺失）。
         * Server 协议没有 per-player.lastPlayedGroup 字段，所以 vm 在 client 侧观察
         * `state.lastPlayedGroup` + `lastPlayerId` 的变化推断维护。
         */
        val perPlayerLastPlay: Map<Int, SerializedCardGroup> = emptyMap(),
        val message: String? = null,
        /**
         * 多人模式：本玩家是否处于 AI 托管 / 暂离状态（feature_spec G34/G35）。
         * 单机模式恒为 null（GameScreen 据此决定不显示托管按钮）。
         */
        val imAiSubstitute: Boolean? = null,
    ) : Screen() {
        enum class Mode { SinglePlayer, Multiplayer }
    }

    /** 结算：显示队伍最终分数 + 各玩家明细（与 Android 端结算页对齐）。 */
    data class Settlement(
        val result: SerializedGameResult,
        val mode: Game.Mode,
        /** 每个玩家的最终状态：座位、名字、队伍、已收分、是否走完、完成顺序。 */
        val playerBreakdown: List<PlayerSummary> = emptyList(),
    ) : Screen() {
        data class PlayerSummary(
            val seatIndex: Int,
            val name: String,
            val team: String,
            val collectedScore: Int,
            val handSize: Int,
            val hasFinished: Boolean,
            val finishOrder: Int,
        )
    }
}

