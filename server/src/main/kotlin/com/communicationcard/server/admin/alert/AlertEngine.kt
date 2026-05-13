package com.communicationcard.server.admin.alert

import com.communicationcard.server.ServerContext
import com.communicationcard.server.admin.AdminAuthService
import com.communicationcard.server.admin.AlertDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 内置告警引擎：每 [tickIntervalMs] 毫秒跑一次所有规则，候选告警通过
 * [AlertStore.insertIfNotCoolingDown] 去重写入 SQLite。
 *
 * 设计原则（CLAUDE.md 约束 9）：
 * - 单一调度协程跑 [Dispatchers.IO]：规则 evaluate 应是 lock-free 弱一致读
 * - 任何异常**吞掉**且 logger.warn，避免一条规则崩了影响其他规则
 * - 同一个 tick 顺手清理过期 admin sessions（PR 1 的 `reapExpiredSessions`），
 *   省一个 scheduler
 *
 * PR 5a：[alertFlow] 是一个 SharedFlow<AlertDto>，每次成功插入 alerts 行就
 * tryEmit 一份 DTO。SSE 端点 `/admin/api/alerts/stream` 订阅这个 Flow 把
 * 新告警实时推到 admin UI（Vue AlertWatcher 用 EventSource 接收）。
 *
 * BufferOverflow.DROP_OLDEST + extraBufferCapacity=64：防止订阅者断连导致
 * 引擎 emit 阻塞；超出缓冲的告警丢弃（但已落 alerts 表，admin 仍可拉到）。
 */
class AlertEngine(
    private val serverCtx: ServerContext,
    private val store: AlertStore,
    private val authService: AdminAuthService,
    private val rules: List<AlertRule> = AllAlertRules.ALL,
    private val tickIntervalMs: Long = DEFAULT_TICK_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    private val _alertFlow = MutableSharedFlow<AlertDto>(
        replay = 0,
        extraBufferCapacity = ALERT_FLOW_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * SSE 端点订阅这个 Flow；每次成功插入 alerts 行后这里会 emit 一份 DTO。
     */
    val alertFlow: SharedFlow<AlertDto> = _alertFlow.asSharedFlow()

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            // 启动延迟一小段，避免 application 刚起来时 JVM heap stats 抖动误报
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    runOnceInternal()
                } catch (e: Throwable) {
                    System.err.println("AlertEngine tick failed (ignored): ${e.message}")
                }
                delay(tickIntervalMs)
            }
        }
    }

    suspend fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * 立即跑一次所有规则（测试用 + 启动顺手跑一遍）。
     */
    suspend fun runOnce() = runOnceInternal()

    private suspend fun runOnceInternal() {
        val now = clock()
        for (rule in rules) {
            val candidates = try {
                rule.evaluate(serverCtx, now)
            } catch (e: Throwable) {
                System.err.println("AlertRule ${rule.name} evaluate failed: ${e.message}")
                emptyList()
            }
            for (c in candidates) {
                try {
                    val id = store.insertIfNotCoolingDown(c, rule.cooldownMs, now)
                    if (id != null) {
                        // 成功插入（非 cooldown 抑制）→ broadcast 到 SSE 订阅者
                        _alertFlow.tryEmit(c.toDto(id, now))
                    }
                } catch (e: Throwable) {
                    System.err.println("AlertStore insert failed for ${rule.name}: ${e.message}")
                }
            }
        }
        // 顺手清理过期 admin sessions（每个 tick 都跑没关系，DELETE 一条 SQL）
        try {
            authService.reapExpiredSessions()
        } catch (e: Throwable) {
            System.err.println("reapExpiredSessions failed: ${e.message}")
        }
    }

    private fun AlertCandidate.toDto(id: Long, createdAtMs: Long): AlertDto = AlertDto(
        id = id,
        rule = rule,
        severity = severity,
        roomId = roomId,
        playerIdMasked = playerIdMasked,
        message = message,
        payload = payload.takeIf { it.isNotEmpty() },
        createdAt = Instant.ofEpochMilli(createdAtMs).toString(),
        ackedAt = null,
        ackedBy = null,
    )

    companion object {
        const val DEFAULT_TICK_MS: Long = 10_000L
        const val INITIAL_DELAY_MS: Long = 5_000L
        private const val ALERT_FLOW_BUFFER: Int = 64
    }
}
