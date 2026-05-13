package com.communicationcard.server.admin

import com.communicationcard.server.ServerContext
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

/**
 * Admin 模块装配入口。在 [com.communicationcard.server.gameModule] 内调用。
 *
 * 责任：
 * 1. 从 application.conf 读 AdminConfig
 * 2. 打开 SQLite + 迁移
 * 3. 若 admin_users 空 → 用 ADMIN_INITIAL_USERNAME/PASSWORD 插入初始账号
 * 4. 装载 /admin-auth/* 路由（PR 2 起追加 /admin/api/*）
 *
 * Fail-fast 原则：
 * - admin.db.path 缺失 → 抛异常（不允许 admin 模块裸奔启动）
 * - admin_users 空 + initial.password 为空 → 抛异常（避免无密码 admin 暴露）
 *
 * 关闭：JVM shutdown hook 关连接（防止 WAL 文件残留）。
 */
fun Application.installAdmin(serverCtx: ServerContext) {
    val config = AdminConfig.fromApplication(this)
    log.info("Admin: db=${config.dbPath} cookieSecure=${config.cookieSecure}")

    val db = AdminDb(config.dbPath)
    val authService = AdminAuthService(db, sessionTtlSeconds = config.sessionTtlSeconds)
    val historyStore = GameHistoryStore(db)
    val alertStore = com.communicationcard.server.admin.alert.AlertStore(db)
    val alertEngine = com.communicationcard.server.admin.alert.AlertEngine(
        serverCtx = serverCtx,
        store = alertStore,
        authService = authService,
    )

    // 同步迁移 + bootstrap：服务启动必须等表准备好再开放路由
    runBlocking {
        db.runMigrations()
        if (config.initialPassword.isNotEmpty()) {
            val inserted = authService.bootstrapInitialAdmin(
                username = config.initialUsername,
                plainPassword = config.initialPassword,
            )
            if (inserted) {
                log.info("Admin: bootstrapped initial admin '${config.initialUsername}'.")
                log.warn("Admin: REMOVE ADMIN_INITIAL_PASSWORD from server.env after first start.")
            }
        } else {
            // 表空但没初始密码 → fail-fast
            // 表非空 → 已有 admin，启动就绪
            val empty = db.withConnection { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM admin_users").use { ps ->
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) == 0 }
                }
            }
            if (empty) {
                error(
                    "Admin: admin_users table empty and ADMIN_INITIAL_PASSWORD not set; " +
                        "refusing to start without any admin account."
                )
            }
        }
    }

    historyStore.start()
    alertEngine.start()

    val snapshotBuilder = SnapshotBuilder(serverCtx, historyStore)
    val ctx = AdminContext(
        serverCtx = serverCtx,
        db = db,
        config = config,
        authService = authService,
        historyStore = historyStore,
        snapshotBuilder = snapshotBuilder,
        alertStore = alertStore,
        alertEngine = alertEngine,
    )

    // 把"游戏结束 → 异步入库"挂到 gameManager
    serverCtx.gameManager.gameEndListener = { room, result ->
        try {
            historyStore.enqueue(GameRecord.capture(room, result))
        } catch (e: Throwable) {
            System.err.println("GameHistoryStore capture failed (ignored): ${e.message}")
        }
    }

    routing {
        adminAuthRoutes(ctx)
        adminApiRoutes(ctx)
    }

    // 关闭钩子：停 alert engine + history 协程 + 让 SQLite 有机会 checkpoint WAL
    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        runBlocking {
            try { alertEngine.stop() } catch (_: Throwable) { /* ignore */ }
            try { historyStore.stop() } catch (_: Throwable) { /* ignore */ }
        }
        try { db.close() } catch (_: Throwable) { /* ignore */ }
    }
}
