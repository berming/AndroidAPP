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
 * 4. 装载 `/admin-auth/...` 路由（PR 2 起追加 `/admin/api/...`）
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

    // 把"游戏结束 → 异步入库"挂到 gameManager。
    // pr-reviewer PR #61 P2 #5：拆为 captureProvider（锁内只做 immutable 拷贝）
    // 和 consumer（锁外 trySend）。即便 consumer 未来回归到阻塞实现，也只阻塞
    // broadcast 协程，不会卡住房间 mutex。
    serverCtx.gameManager.gameEndCaptureProvider = { room, result ->
        try {
            GameRecord.capture(room, result)
        } catch (e: Throwable) {
            System.err.println("GameRecord.capture failed (ignored): ${e.message}")
            null
        }
    }
    serverCtx.gameManager.gameEndConsumer = { record ->
        // record 在 ServerGameManager 层是 Any，admin 层知道实际类型
        historyStore.enqueue(record as GameRecord)
    }

    // PR 5d: 把每手出牌 / pass / 回合结束等事件按 roomId 缓冲到 historyStore
    serverCtx.gameManager.gameEventListener = { room, event ->
        try {
            historyStore.recordEvent(room.roomId, event)
        } catch (e: Throwable) {
            System.err.println("GameHistoryStore recordEvent failed (ignored): ${e.message}")
        }
    }

    routing {
        adminAuthRoutes(ctx)
        adminApiRoutes(ctx)
    }

    // 关闭钩子：按"先断输入 → 停消费者 → 关连接"顺序：
    // (pr-reviewer PR #61 P2 #6)
    // 1. 先 null 掉 gameEnd / gameEvent hook：防止 in-flight 游戏完成后再触发
    //    listener → 给已关闭 Channel trySend（功能无害但是 wasted op）
    // 2. 等当前 broadcast 中的 listener invoke 完成（这里靠 mutexFor(room) 顺序保证，
    //    不引入额外 sleep）
    // 3. 停 alertEngine（停周期 tick）+ historyStore（关 Channel → IO 协程 join）
    // 4. 最后 db.close()，让 SQLite WAL 有机会 checkpoint
    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        serverCtx.gameManager.gameEndCaptureProvider = null
        serverCtx.gameManager.gameEndConsumer = null
        serverCtx.gameManager.gameEventListener = null
        runBlocking {
            try { alertEngine.stop() } catch (_: Throwable) { /* ignore */ }
            try { historyStore.stop() } catch (_: Throwable) { /* ignore */ }
        }
        try { db.close() } catch (_: Throwable) { /* ignore */ }
    }
}
