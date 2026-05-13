package com.communicationcard.server.admin.alert

import com.communicationcard.game.network.RoomStatus
import com.communicationcard.server.ServerContext

/**
 * 告警规则接口。每条规则：
 * - [name]：写入 alerts 表的 `rule` 列；同时是 cooldown 去重的 key
 * - [cooldownMs]：同 (rule, roomId) 在窗口内只发一次
 * - [evaluate]：给定服务端运行时上下文，返回 0..N 条 [AlertCandidate]
 *
 * 设计约束（CLAUDE.md 约束 9）：evaluate 必须 lock-free 或只读弱一致快照。
 * 严禁在规则内做 `mutexFor(room).withLock`——会把告警调度的 10s tick 卡到
 * 跟着游戏关键路径走，告警变成 DoS 风险。
 */
interface AlertRule {
    val name: String
    val severity: String
    val cooldownMs: Long
    fun evaluate(ctx: ServerContext, nowMs: Long): List<AlertCandidate>
}

/**
 * 内置规则集（MVP）。新增规则 = 新增一个 object + append 到 [ALL_RULES]。
 * 复杂的"滑动窗口断网风暴"等推到 PR 5（需要更细粒度的全局计数器）。
 */
object AllAlertRules {
    val ALL: List<AlertRule> = listOf(
        RoomStuckRule,
        JvmHeapHighRule,
        DisconnectRatioHighRule,
    )
}

/**
 * 房间 IN_GAME 状态下连续 5 分钟无任何 action（人 / AI 都没动）→ WARN。
 *
 * 触发后 cooldown 5 分钟避免同一卡死房间一直刷告警。
 */
object RoomStuckRule : AlertRule {
    override val name = "ROOM_STUCK"
    override val severity = "WARN"
    override val cooldownMs: Long = 5 * 60 * 1000L
    const val STUCK_THRESHOLD_MS: Long = 5 * 60 * 1000L

    override fun evaluate(ctx: ServerContext, nowMs: Long): List<AlertCandidate> {
        val rooms = ctx.roomManager.allRoomsSnapshot().filter { it.status == RoomStatus.IN_GAME }
        return rooms.mapNotNull { room ->
            val state = room.gameState ?: return@mapNotNull null
            val age = nowMs - state.lastActionAt
            if (age < STUCK_THRESHOLD_MS) return@mapNotNull null
            AlertCandidate(
                rule = name,
                severity = severity,
                roomId = room.roomId,
                message = "房间 ${room.roomCode} 已 ${age / 60_000} 分钟无任何动作（IN_GAME 阶段）",
                payload = mapOf(
                    "roomCode" to room.roomCode,
                    "phase" to (state.phase),
                    "currentPlayerIndex" to state.currentPlayerIndex.toString(),
                    "ageMs" to age.toString(),
                ),
            )
        }
    }
}

/**
 * JVM 堆使用率 > 85% → WARN。
 * cooldown 1 分钟（heap 长期高位时每 1 分钟提醒一次，避免被淹没）。
 */
object JvmHeapHighRule : AlertRule {
    override val name = "JVM_HEAP_HIGH"
    override val severity = "WARN"
    override val cooldownMs: Long = 60 * 1000L
    const val HEAP_THRESHOLD_PCT: Double = 0.85

    override fun evaluate(ctx: ServerContext, nowMs: Long): List<AlertCandidate> {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val max = rt.maxMemory()
        val pct = if (max > 0) used.toDouble() / max else 0.0
        if (pct <= HEAP_THRESHOLD_PCT) return emptyList()
        return listOf(
            AlertCandidate(
                rule = name,
                severity = severity,
                message = "JVM heap 使用率 ${"%.1f".format(pct * 100)}%（超过 ${
                    "%.0f".format(HEAP_THRESHOLD_PCT * 100)
                }% 阈值）",
                payload = mapOf(
                    "usedMb" to (used / MB).toString(),
                    "maxMb" to (max / MB).toString(),
                    "pct" to "%.4f".format(pct),
                ),
            )
        )
    }

    private const val MB = 1024L * 1024L
}

/**
 * 所有房间人类玩家中断线比例 > 30% → INFO（运维注意而非告急）。
 * 仅在样本足够（≥10 人）时触发，避免小流量误报。
 */
object DisconnectRatioHighRule : AlertRule {
    override val name = "DISCONNECT_RATIO_HIGH"
    override val severity = "INFO"
    override val cooldownMs: Long = 60 * 1000L
    const val MIN_SAMPLE_SIZE: Int = 10
    const val RATIO_THRESHOLD: Double = 0.30

    override fun evaluate(ctx: ServerContext, nowMs: Long): List<AlertCandidate> {
        var totalHumans = 0
        var disconnected = 0
        for (room in ctx.roomManager.allRoomsSnapshot()) {
            for (p in room.players) {
                if (p.isAI) continue
                totalHumans++
                if (!p.isConnected) disconnected++
            }
        }
        if (totalHumans < MIN_SAMPLE_SIZE) return emptyList()
        val ratio = disconnected.toDouble() / totalHumans
        if (ratio <= RATIO_THRESHOLD) return emptyList()
        return listOf(
            AlertCandidate(
                rule = name,
                severity = severity,
                message = "断线玩家比例 $disconnected/$totalHumans = ${
                    "%.0f".format(ratio * 100)
                }%（超过 ${"%.0f".format(RATIO_THRESHOLD * 100)}% 阈值）",
                payload = mapOf(
                    "disconnected" to disconnected.toString(),
                    "totalHumans" to totalHumans.toString(),
                    "ratio" to "%.4f".format(ratio),
                ),
            )
        )
    }
}
