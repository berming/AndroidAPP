package com.communicationcard.server.admin.alert

import com.communicationcard.server.ServerContext
import com.communicationcard.server.admin.AdminAuthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 内置告警引擎：每 [tickIntervalMs] 毫秒跑一次所有规则，候选告警通过
 * [AlertStore.insertIfNotCoolingDown] 去重写入 SQLite。
 *
 * 设计原则（CLAUDE.md 约束 9）：
 * - 单一调度协程跑 [Dispatchers.IO]：规则 evaluate 应是 lock-free 弱一致读
 * - 任何异常**吞掉**且 logger.warn，避免一条规则崩了影响其他规则
 * - 同一个 tick 顺手清理过期 admin sessions（PR 1 的 `reapExpiredSessions`），
 *   省一个 scheduler
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
                    store.insertIfNotCoolingDown(c, rule.cooldownMs, now)
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

    companion object {
        const val DEFAULT_TICK_MS: Long = 10_000L
        const val INITIAL_DELAY_MS: Long = 5_000L
    }
}
