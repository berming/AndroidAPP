package com.communicationcard.server.admin

import com.communicationcard.game.network.SerializedGameEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * PR 5d：一条**已序列化**的游戏事件。在 ServerGameManager 派发
 * SerializedGameEvent 时，[GameHistoryStore] 把它按 (roomId, 自增 seq, eventType,
 * payload-JSON) 缓冲；游戏结束时连同 GameRecord 一并写入 game_events 表。
 *
 * eventType 取 SerializedGameEvent 子类的 @SerialName（如 "cards_played" /
 * "player_passed" / "round_won" / "player_finished" / "turn_start"）。
 * payloadJson 是 SerializedGameEvent 子类直接 JSON 编码的结果（保留 type 信息）。
 */
data class GameEventRecord(
    val seq: Int,
    val eventType: String,
    val payloadJson: String,
    val createdAt: Long,
)

/**
 * 给 admin UI 的事件 DTO；payload 解析为 JsonElement 让前端按 eventType 解释。
 */
@Serializable
data class GameEventDto(
    val seq: Int,
    val eventType: String,
    val payload: JsonElement,
    val createdAt: String,           // ISO-8601
)

/**
 * 内部工具：把 SerializedGameEvent 子类反射出 SerialName。
 * 由于 SerializedGameEvent 是 sealed @Serializable + 每个 case 都标注
 * @SerialName，直接用 polymorphic JSON 序列化的 "type" 字段即可。
 */
internal fun SerializedGameEvent.eventTypeName(): String = when (this) {
    is SerializedGameEvent.CardsDealt -> "cards_dealt"
    is SerializedGameEvent.TurnStart -> "turn_start"
    is SerializedGameEvent.CardsPlayed -> "cards_played"
    is SerializedGameEvent.PlayerPassed -> "player_passed"
    is SerializedGameEvent.RoundWon -> "round_won"
    is SerializedGameEvent.PlayerFinished -> "player_finished"
    is SerializedGameEvent.ScoreUpdate -> "score_update"
}
