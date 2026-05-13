package com.communicationcard.server.admin

import com.communicationcard.server.ServerContext

/**
 * Admin 模块的 DI 容器。在 [installAdmin] 入口处构造一次，路由 + 后续模块
 * 共享同一份。
 *
 * 持有：
 * - [serverCtx]：游戏运行时的 roomManager / gameManager / sessions 引用
 *   （PR 2 起 SnapshotBuilder 用）
 * - [db]：admin SQLite 连接
 * - [config]：从 application.conf 读出的强类型配置
 * - [authService]：登录 / 校验 / 改密
 *
 * PR 2/3 会扩展：`snapshotBuilder` / `historyStore` / `alertEngine` 等字段。
 */
data class AdminContext(
    val serverCtx: ServerContext,
    val db: AdminDb,
    val config: AdminConfig,
    val authService: AdminAuthService,
)
