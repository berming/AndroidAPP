package com.communicationcard.server.admin

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `/admin/api/...` 监控端点（PR 2）。
 *
 * 6 个 GET 端点 + 1 个权限策略：所有路由都先调
 * [requirePermission]（`MONITOR_READ` 或 `GAME_HISTORY_READ`）。
 *
 * 设计原则（CLAUDE.md 约束 9 + 10）：
 * - 房间维度数据走 [SnapshotBuilder.room]：短暂持锁取 [RoomSnapshot]，
 *   锁外渲染 DTO
 * - 跨房间数据（overview / players / sessions）lock-free 读
 *   [java.util.concurrent.ConcurrentHashMap]
 * - DTO 字段全部脱敏（前 8 hex UUID）；不暴露 hands 内容
 */
fun Route.adminApiRoutes(ctx: AdminContext) {
    route("/admin/api") {
        get("/overview") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.overview())
        }

        get("/rooms") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.rooms())
        }

        get("/rooms/{id}") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            val id = call.parameters["id"].orEmpty()
            val detail = ctx.snapshotBuilder.room(id)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("房间不存在"))
            } else {
                call.respond(detail)
            }
        }

        get("/players") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.players())
        }

        get("/sessions") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.sessions())
        }

        get("/games") {
            call.requirePermission(ctx, AdminPermission.GAME_HISTORY_READ) ?: return@get
            val from = call.request.queryParameters["from"]?.toLongOrNull()
            val to = call.request.queryParameters["to"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(ctx.historyStore.listSummaries(from, to, limit))
        }

        get("/games/{id}") {
            call.requirePermission(ctx, AdminPermission.GAME_HISTORY_READ) ?: return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("id 不是数字"))
            val detail = ctx.historyStore.findDetail(id)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("游戏不存在"))
            } else {
                call.respond(detail)
            }
        }

        // PR 5b: Dashboard 趋势图聚合
        get("/stats/trend") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            call.respond(ctx.historyStore.trendByDay(days))
        }

        // PR 5d: 单局逐手出牌事件流（按 seq 排序，最早在前）
        get("/games/{id}/events") {
            call.requirePermission(ctx, AdminPermission.GAME_HISTORY_READ) ?: return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("id 不是数字"))
            call.respond(ctx.historyStore.listEvents(id))
        }

        // PR 3: 告警 ----------------------------------------------------

        get("/alerts") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            val unackOnly = call.request.queryParameters["unack"]?.toBooleanStrictOrNull() ?: false
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val list = if (unackOnly) {
                ctx.alertStore.listUnacked(limit)
            } else {
                ctx.alertStore.listRecent(limit)
            }
            call.respond(list)
        }

        post("/alerts/{id}/ack") {
            val user = call.requirePermission(ctx, AdminPermission.ALERT_ACK) ?: return@post
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("id 不是数字"))
            val ok = ctx.alertStore.ack(id, user.id, System.currentTimeMillis())
            if (ok) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("告警不存在或已 ack"))
            }
        }

        // PR 5a: SSE 告警推送 -----------------------------------------
        // 替代 admin UI 30s 轮询。EventSource 客户端会自动重连，所以
        // 服务端只需 keep-alive；通过 25s 心跳防 Caddy / Nginx 反代断连。
        // 数据流：AlertEngine.runOnce → store.insertIfNotCoolingDown 成功 →
        //         _alertFlow.tryEmit(dto) → 此处 collect → wire 'data: ...\n\n'
        get("/alerts/stream") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = SSE_CONTENT_TYPE) {
                // 写一个初始 retry hint：默认 3s（标准 EventSource 重连间隔）
                write("retry: 3000\n\n")
                flush()
                coroutineScope {
                    val heartbeat = launch {
                        while (isActive) {
                            delay(HEARTBEAT_INTERVAL_MS)
                            write(": ping\n\n")
                            flush()
                        }
                    }
                    try {
                        ctx.alertEngine.alertFlow.collect { dto ->
                            write("data: ${SSE_JSON.encodeToString(dto)}\n\n")
                            flush()
                        }
                    } catch (_: Throwable) {
                        // 客户端断连：write/flush 抛 IOException；正常退出 lambda
                    } finally {
                        heartbeat.cancel()
                    }
                }
            }
        }
    }
}

private val SSE_CONTENT_TYPE = ContentType.parse("text/event-stream")
private val SSE_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private const val HEARTBEAT_INTERVAL_MS: Long = 25_000L
