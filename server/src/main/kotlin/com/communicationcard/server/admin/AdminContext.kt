package com.communicationcard.server.admin

import com.communicationcard.server.ServerContext
import com.communicationcard.server.admin.alert.AlertEngine
import com.communicationcard.server.admin.alert.AlertStore

/**
 * Admin 模块的 DI 容器。在 [installAdmin] 入口处构造一次，路由 + 后续模块
 * 共享同一份。
 */
data class AdminContext(
    val serverCtx: ServerContext,
    val db: AdminDb,
    val config: AdminConfig,
    val authService: AdminAuthService,
    val historyStore: GameHistoryStore,
    val snapshotBuilder: SnapshotBuilder,
    val alertStore: AlertStore,
    val alertEngine: AlertEngine,
)
