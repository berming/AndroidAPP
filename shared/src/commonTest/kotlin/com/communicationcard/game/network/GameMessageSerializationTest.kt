package com.communicationcard.game.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 协议层 DTO 序列化金标测试（feature_spec / client_implementation_guide §7.2）。
 *
 * **覆盖**：v3 引入的 AI 托管 / 速度配置消息（feature_spec G34-G38）。
 * **目的**：
 *  - 防回归：DTO 字段名 / @SerialName / 默认值漂移会立即被发现
 *  - 协议契约：新客户端实现者可以照此格式手抄到非 Kotlin 端
 *
 * 不覆盖业务逻辑（那是 ServerGameManagerTest / CardRulesTest 的事）。
 */
class GameMessageSerializationTest {

    private val json = GameMessage.json

    @Test
    fun setRoomAISpeed_roundTrip() {
        val msg = SetRoomAISpeed(delayMs = 400)
        val encoded = json.encodeToString(GameMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"room.set_ai_speed\""), "type discriminator 缺失")
        assertTrue(encoded.contains("\"delayMs\":400"), "delayMs 字段名漂移")
        val decoded = json.decodeFromString(GameMessage.serializer(), encoded)
        assertIs<SetRoomAISpeed>(decoded)
        assertEquals(400, decoded.delayMs)
    }

    @Test
    fun setMyTakeoverSpeed_roundTrip() {
        val msg = SetMyTakeoverSpeed(delayMs = 1000)
        val encoded = json.encodeToString(GameMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"player.set_takeover_speed\""))
        val decoded = json.decodeFromString(GameMessage.serializer(), encoded)
        assertIs<SetMyTakeoverSpeed>(decoded)
        assertEquals(1000, decoded.delayMs)
    }

    @Test
    fun toggleAITakeover_roundTrip_enabled() {
        val msg = ToggleAITakeover(enabled = true)
        val encoded = json.encodeToString(GameMessage.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"player.toggle_ai_takeover\""))
        assertTrue(encoded.contains("\"enabled\":true"))
        val decoded = json.decodeFromString(GameMessage.serializer(), encoded)
        assertIs<ToggleAITakeover>(decoded)
        assertTrue(decoded.enabled)
    }

    @Test
    fun toggleAITakeover_roundTrip_disabled() {
        val msg = ToggleAITakeover(enabled = false)
        val encoded = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString(GameMessage.serializer(), encoded)
        assertIs<ToggleAITakeover>(decoded)
        assertEquals(false, decoded.enabled)
    }

    @Test
    fun protocolVersion_isThree() {
        // 改 PROTOCOL_VERSION 必须同改本文件，提醒新增防回归测试 + 客户端兼容审查
        assertEquals(3, GameMessage.PROTOCOL_VERSION)
    }

    @Test
    fun aiDelayConstants_match_spec() {
        // feature_spec G36-G38 要求的档位常量
        assertEquals(400, GameMessage.AI_DELAY_DEFAULT_MS)
        assertEquals(100, GameMessage.AI_DELAY_MIN_MULTIPLAYER_MS)
        assertEquals(50, GameMessage.AI_DELAY_MIN_SINGLEPLAYER_MS)
        assertEquals(1000, GameMessage.AI_DELAY_MAX_MS)
    }

    @Test
    fun roomInfo_serverAiDelayMs_defaultsToProtocolDefault() {
        // 反序列化老服务端 JSON（无 serverAiDelayMs 字段）→ 应得默认 400
        val legacyJson = """
            {
                "roomId": "r1", "roomCode": "ABCD", "roomName": "test",
                "hostId": "host1", "players": [], "maxPlayers": 6, "status": "WAITING"
            }
        """.trimIndent()
        val ri = json.decodeFromString(RoomInfo.serializer(), legacyJson)
        assertEquals(GameMessage.AI_DELAY_DEFAULT_MS, ri.serverAiDelayMs,
            "老服务端 JSON 缺字段时反序列化必须取默认值，避免 NPE")
    }

    @Test
    fun roomPlayer_takeoverFields_defaultsForLegacy() {
        // 反序列化老服务端 JSON（无 takeoverAiDelayMs / isAISubstitute）→ 默认值
        val legacyJson = """
            {
                "id": "alice", "name": "Alice", "isReady": false,
                "isConnected": true, "isAI": false, "seatIndex": 0
            }
        """.trimIndent()
        val rp = json.decodeFromString(RoomPlayer.serializer(), legacyJson)
        assertEquals(GameMessage.AI_DELAY_DEFAULT_MS, rp.takeoverAiDelayMs)
        assertEquals(false, rp.isAISubstitute)
    }
}
